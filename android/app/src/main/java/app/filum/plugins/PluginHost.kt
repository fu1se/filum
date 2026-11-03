package app.filum.plugins

import app.filum.data.DeviceListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Заголовок плагина: строки `// @key value` в начале файла. */
data class PluginMeta(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    /** Разрешения: gcode — команды принтеру и хуки, которые их меняют; http — сеть; notify — уведомления. */
    val grants: Set<String>,
) {
    companion object {
        val KNOWN_GRANTS = setOf(GRANT_GCODE, GRANT_HTTP, GRANT_NOTIFY, GRANT_DEVICE)
        private val ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")

        /** Разбирает заголовок; бросает [IllegalArgumentException] с понятным текстом. */
        fun parse(source: String): PluginMeta {
            val tags = mutableMapOf<String, MutableList<String>>()
            for (raw in source.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (!line.startsWith("//")) break
                val m = Regex("^//\\s*@(\\w+)\\s*(.*)$").find(line) ?: continue
                tags.getOrPut(m.groupValues[1]) { mutableListOf() } += m.groupValues[2].trim()
            }
            val id = tags["plugin"]?.firstOrNull()
                ?: throw IllegalArgumentException("no '// @plugin <id>' line at the top of the file")
            require(ID.matches(id)) { "bad plugin id '$id': use a-z, 0-9, '.', '_', '-'" }
            val grants = tags["grant"].orEmpty().flatMap { it.split(',', ' ') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val unknown = grants - KNOWN_GRANTS
            require(unknown.isEmpty()) { "unknown grant: ${unknown.joinToString()}" }
            return PluginMeta(
                id = id,
                name = tags["name"]?.firstOrNull()?.ifBlank { null } ?: id,
                version = tags["version"]?.firstOrNull().orEmpty(),
                author = tags["author"]?.firstOrNull().orEmpty(),
                description = tags["description"].orEmpty().joinToString(" "),
                grants = grants,
            )
        }
    }
}

const val GRANT_GCODE = "gcode"
const val GRANT_HTTP = "http"
const val GRANT_NOTIFY = "notify"
const val GRANT_DEVICE = "device"

/** Тип устройства, который зарегистрировал плагин (filum.device.register): его можно выбрать как принтер. */
data class DeviceType(val pluginId: String, val name: String, val addressHint: String)

/** Что приложение даёт плагинам. Реализация — в [AndroidPluginServices]; в тестах — подделка. */
interface PluginServices {
    /** Команда принтеру в обход хуков gcode.send (иначе плагин мог бы зациклиться сам на себе). */
    fun sendGcode(script: String)
    /** Объекты Klipper (printer.objects.query); null — нет связи или таймаут. */
    fun query(objects: List<String>, timeoutMs: Long): JSONObject?
    /** Снимок состояния принтера. */
    fun state(): JSONObject
    fun toast(text: String)
    fun notify(title: String, text: String)
    fun http(method: String, url: String, body: String?, contentType: String?): Pair<Int, String>
    fun storageGet(pluginId: String, key: String): String?
    fun storageSet(pluginId: String, key: String, json: String?)
    fun log(pluginId: String, level: Char, text: String)
}

/** Элемент интерфейса, который добавил плагин (рисуется на вкладке «Управление»). */
sealed interface PluginItem {
    data class Text(val text: String) : PluginItem
    data class Button(val label: String) : PluginItem
    data class Switch(val label: String, val value: Boolean) : PluginItem
    data class Slider(val label: String, val min: Double, val max: Double, val step: Double, val value: Double) : PluginItem
    data class Input(val label: String, val value: String, val button: String) : PluginItem
}

data class PluginSection(val pluginId: String, val id: Int, val title: String, val items: List<PluginItem>)

data class PluginInfo(val meta: PluginMeta, val enabled: Boolean, val error: String?)

/**
 * Плагины на JavaScript (Rhino, режим интерпретатора). Каждый плагин — отдельная область видимости без доступа
 * к Java; весь JS выполняется в одном потоке «filum-plugins», так что плагинам не нужна синхронизация.
 *
 * Плагин регистрирует обработчики через `filum.on(event, fn)`. События:
 * - уведомления: load, unload, state, print.started / finished / cancelled / paused / resumed / error;
 * - хуки, которые меняют данные приложения (нужно разрешение gcode):
 *   gcode.send(script) → строка-замена | false (не отправлять);
 *   print.start(file) → false (не запускать);
 *   slice(request) → изменённый запрос к серверу слайсера;
 *   gcode.file(text, info) → изменённый G-code перед отправкой на принтер.
 * Хуки нескольких плагинов применяются по очереди. Ошибка или таймаут в хуке — данные идут дальше без изменений.
 */
class PluginHost(private val services: PluginServices) {

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "filum-plugins").apply { isDaemon = true } }

    @Volatile private var deadline = 0L

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            @Suppress("DEPRECATION")
            optimizationLevel = -1  // на Android нельзя генерировать байткод
            languageVersion = Context.VERSION_ES6
            setClassShutter { false }  // никаких java.* из JS
            instructionObserverThreshold = 10_000
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (deadline != 0L && System.currentTimeMillis() > deadline) throw TimeoutError()
        }

        override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
            if (featureIndex == Context.FEATURE_ENHANCED_JAVA_ACCESS) false else super.hasFeature(cx, featureIndex)
    }

    private class TimeoutError : Error("plugin took too long")

    private class Loaded(val meta: PluginMeta, val scope: ScriptableObject) {
        val handlers = mutableMapOf<String, MutableList<Function>>()
        val timers = mutableMapOf<Int, ScheduledFuture<*>>()
        /** sectionId → обработчики элементов по индексу. */
        val sectionCallbacks = mutableMapOf<Int, List<Function?>>()
        /** Драйвер устройства (filum.device.register) и подключённый к нему клиент. */
        var driver: Scriptable? = null
        var deviceListener: DeviceListener? = null
    }

    // только из потока плагинов
    private val loaded = LinkedHashMap<String, Loaded>()
    private var nextId = 1

    private val _sections = MutableStateFlow<List<PluginSection>>(emptyList())
    val sections: StateFlow<List<PluginSection>> = _sections.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceType>>(emptyList())
    val devices: StateFlow<List<DeviceType>> = _devices.asStateFlow()

    /** Выбранное устройство, чей плагин ещё не загружен (или перезагружается): подключим после register(). */
    private val pendingDevice = mutableMapOf<String, Pair<String, DeviceListener>>()

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    /** Какие хуки сейчас есть хоть у одного плагина — чтобы без плагинов не гонять данные через поток JS. */
    @Volatile private var hooked: Set<String> = emptySet()

    fun hasHook(event: String) = event in hooked

    // ---------- загрузка ----------

    /** Загружает (или перезагружает) плагин. [onDone] — null или текст ошибки; вызывается в потоке плагинов. */
    fun load(source: String, onDone: (String?) -> Unit = {}) = executor.execute {
        val err = try {
            val meta = PluginMeta.parse(source)
            unloadNow(meta.id)
            loadNow(meta, source)
            null
        } catch (e: Throwable) {
            describe(e)
        }
        onDone(err)
    }

    fun unload(id: String) = executor.execute { unloadNow(id) }

    /** Ждёт, пока выполнятся все поставленные задачи (для тестов). */
    fun flush(timeoutMs: Long = 10_000) {
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun shutdown() {
        executor.execute { loaded.keys.toList().forEach(::unloadNow) }
        executor.shutdown()
    }

    private fun loadNow(meta: PluginMeta, source: String) {
        val p = enter { cx ->
            val scope = cx.initSafeStandardObjects()
            val p = Loaded(meta, scope)
            ScriptableObject.putProperty(scope, "filum", api(cx, p))
            ScriptableObject.putProperty(scope, "console", cx.newObject(scope).also { c ->
                for ((n, lvl) in listOf("log" to 'i', "info" to 'i', "warn" to 'w', "error" to 'e'))
                    fn(c, n) { _, args -> services.log(meta.id, lvl, args.joinToString(" ") { show(it) }); null }
            })
            loaded[meta.id] = p
            withDeadline(LOAD_MS) { cx.evaluateString(scope, source, "${meta.id}.js", 1, null) }
            p
        }
        emit(p, "load", emptyArray())
        refresh()
        services.log(meta.id, 'i', "loaded ${meta.version}".trim())
    }

    private fun unloadNow(id: String) {
        val p = loaded[id] ?: return
        p.deviceListener?.let { l ->
            callDriver(p, "disconnect")
            l.onState(JSONObject().put("connected", false).put("message", "plugin '$id' is not loaded"))
            pendingDevice[id] = (deviceAddress[id] ?: "") to l
        }
        _devices.update { list -> list.filterNot { it.pluginId == id } }
        emit(p, "unload", emptyArray())
        p.timers.values.forEach { it.cancel(false) }
        loaded.remove(id)
        _sections.update { list -> list.filterNot { it.pluginId == id } }
        refresh()
    }

    private fun refresh() {
        hooked = loaded.values.flatMap { it.handlers.filterValues { h -> h.isNotEmpty() }.keys }.toSet()
    }

    /** Список для менеджера плагинов (его ведёт [PluginStore], здесь только публикуем). */
    fun publishList(list: List<PluginInfo>) { _plugins.value = list }

    // ---------- события и хуки ----------

    private var lastState: String? = null
    @Volatile private var pendingState: JSONObject? = null

    /** Новое состояние принтера: события печати и «state» (не чаще, чем плагины успевают). */
    fun onState(state: JSONObject) {
        val firstPending = pendingState == null
        pendingState = state
        if (!firstPending) return
        executor.execute {
            val s = pendingState ?: return@execute
            pendingState = null
            val now = s.optString("printState").ifBlank { null }
            val prev = lastState
            lastState = now
            if (prev != null && now != prev) {
                val file = s.optString("fileName")
                val ev = when {
                    now == "printing" && prev == "paused" -> "print.resumed"
                    now == "printing" -> "print.started"
                    now == "paused" -> "print.paused"
                    now == "complete" && prev in ACTIVE -> "print.finished"
                    now == "cancelled" && prev in ACTIVE -> "print.cancelled"
                    now == "error" -> "print.error"
                    else -> null
                }
                if (ev != null) for (p in loaded.values.toList())
                    emit(p, ev, arrayOf(if (ev == "print.error") s.optString("printMessage") else file))
            }
            if (hasHook("state")) for (p in loaded.values.toList()) emit(p, "state", arrayOf(s))
        }
    }

    /** gcode.send: [onResult] получит изменённую команду или null, если плагин её запретил. */
    fun filterGcode(script: String, onResult: (String?) -> Unit) {
        if (!hasHook("gcode.send")) return onResult(script)
        executor.execute {
            var cur: String? = script
            for (p in withGrant(GRANT_GCODE)) {
                val r = hook(p, "gcode.send", HOOK_MS) { cur!! } ?: continue
                cur = when {
                    r == false -> null
                    r is CharSequence -> r.toString()
                    else -> cur
                }
                if (cur == null) { services.log(p.meta.id, 'w', "blocked: $script"); break }
            }
            onResult(cur)
        }
    }

    /** print.start: [onResult] true — запускать. */
    fun allowPrint(file: String, onResult: (Boolean) -> Unit) {
        if (!hasHook("print.start")) return onResult(true)
        executor.execute {
            var ok = true
            for (p in withGrant(GRANT_GCODE)) {
                if (hook(p, "print.start", HOOK_MS) { file } == false) {
                    services.log(p.meta.id, 'w', "print blocked: $file"); ok = false; break
                }
            }
            onResult(ok)
        }
    }

    /** slice: запрос к серверу слайсера (JSON) → изменённый. */
    fun transformSlice(request: JSONObject, onResult: (JSONObject) -> Unit) {
        if (!hasHook("slice")) return onResult(request)
        executor.execute {
            var cur = request
            for (p in withGrant(GRANT_GCODE)) {
                val r = hook(p, "slice", HOOK_MS) { cur } ?: continue
                if (r is JSONObject) cur = r
            }
            onResult(cur)
        }
    }

    /** gcode.file: текст G-code → изменённый (перед загрузкой на принтер). */
    fun transformGcodeFile(text: String, info: JSONObject, onResult: (String) -> Unit) {
        if (!hasHook("gcode.file")) return onResult(text)
        executor.execute {
            var cur = text
            for (p in withGrant(GRANT_GCODE)) {
                val r = hook(p, "gcode.file", FILE_MS, info) { cur } ?: continue
                if (r is CharSequence) { cur = r.toString(); services.log(p.meta.id, 'i', "G-code changed (${cur.length} chars)") }
            }
            onResult(cur)
        }
    }

    // ---------- устройства (драйверы) ----------

    private val deviceAddress = mutableMapOf<String, String>()

    /** Подключиться к устройству плагина [pluginId] по адресу [address]. */
    fun deviceConnect(pluginId: String, address: String, listener: DeviceListener) = executor.execute {
        deviceAddress[pluginId] = address
        val p = loaded[pluginId]
        if (p?.driver == null) {
            pendingDevice[pluginId] = address to listener
            listener.onState(JSONObject().put("connected", false).put("message", "plugin '$pluginId' is not installed or disabled"))
            return@execute
        }
        p.deviceListener = listener
        callDriver(p, "connect", address)
    }

    fun deviceDisconnect(pluginId: String) = executor.execute {
        pendingDevice.remove(pluginId)
        val p = loaded[pluginId] ?: return@execute
        if (p.deviceListener == null) return@execute
        p.deviceListener = null
        callDriver(p, "disconnect")
    }

    /** Вызов метода драйвера; [onResult] получит результат (JSONObject/JSONArray/строка/null). */
    fun deviceCall(pluginId: String, method: String, args: Array<Any?>, onResult: ((Any?) -> Unit)? = null) = executor.execute {
        val p = loaded[pluginId]
        val r = if (p?.deviceListener != null) callDriver(p, method, *args) else null
        onResult?.invoke(r)
    }

    private fun callDriver(p: Loaded, method: String, vararg args: Any?): Any? {
        val d = p.driver ?: return null
        val f = ScriptableObject.getProperty(d, method) as? Function ?: return null
        val ms = if (method == "files" || method == "connect") QUERY_MS + HOOK_MS else HOOK_MS
        return run(p, ms) { cx ->
            fromJs(cx, p.scope, f.call(cx, p.scope, d, args.map { toJs(cx, p.scope, it) }.toTypedArray()))
        }?.takeUnless { it == Unit }
    }

    /** Нажатие/изменение элемента интерфейса плагина. */
    fun uiEvent(pluginId: String, sectionId: Int, index: Int, value: Any?) = executor.execute {
        val p = loaded[pluginId] ?: return@execute
        val f = p.sectionCallbacks[sectionId]?.getOrNull(index) ?: return@execute
        run(p, HOOK_MS) { cx -> f.call(cx, p.scope, p.scope, arrayOf(toJs(cx, p.scope, value))) }
    }

    private fun withGrant(g: String) = loaded.values.toList().filter { g in it.meta.grants }

    /** Вызывает обработчики [event] плагина [p] по очереди; результат последнего не-undefined. */
    private fun hook(p: Loaded, event: String, ms: Long, vararg extra: Any?, arg: () -> Any): Any? {
        val hs = p.handlers[event] ?: return null
        var result: Any? = null
        for (h in hs.toList()) {
            val r = run(p, ms) { cx ->
                val a = toJs(cx, p.scope, arg())
                val out = h.call(cx, p.scope, p.scope, arrayOf(a, *extra.map { toJs(cx, p.scope, it) }.toTypedArray()))
                fromJs(cx, p.scope, out)
            }
            if (r != null && r != Unit) result = r
        }
        return result
    }

    private fun emit(p: Loaded, event: String, args: Array<Any?>) {
        for (h in p.handlers[event].orEmpty().toList())
            run(p, HOOK_MS) { cx -> h.call(cx, p.scope, p.scope, args.map { toJs(cx, p.scope, it) }.toTypedArray()) }
    }

    /** Выполняет JS плагина с ограничением по времени; ошибки — в лог плагина, наружу null. */
    private fun <T> run(p: Loaded, ms: Long, block: (Context) -> T): T? = try {
        enter { cx -> withDeadline(ms) { block(cx) } }
    } catch (e: Throwable) {
        services.log(p.meta.id, 'e', describe(e))
        null
    }

    private fun <T> enter(block: (Context) -> T): T {
        val cx = factory.enterContext()
        try { return block(cx) } finally { Context.exit() }
    }

    private inline fun <T> withDeadline(ms: Long, block: () -> T): T {
        val outer = deadline
        deadline = System.currentTimeMillis() + ms
        try { return block() } finally { deadline = outer }
    }

    private fun describe(e: Throwable): String = when (e) {
        is TimeoutError -> "timeout: the plugin ran too long"
        is RhinoException -> e.details() + (e.lineNumber().takeIf { it > 0 }?.let { " (line $it)" } ?: "")
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------- API filum.* ----------

    private fun api(cx: Context, p: Loaded): Scriptable {
        val scope = p.scope
        val id = p.meta.id
        fun need(g: String) {
            if (g !in p.meta.grants) throw Context.reportRuntimeError("permission '$g' is not granted: add '// @grant $g'")
        }
        val filum = cx.newObject(scope)
        ScriptableObject.putProperty(filum, "id", id)
        ScriptableObject.putProperty(filum, "version", API_VERSION)

        fn(filum, "on") { _, a ->
            val ev = Context.toString(a.getOrNull(0))
            val f = a.getOrNull(1) as? Function ?: throw Context.reportRuntimeError("filum.on: second argument must be a function")
            if (ev in GCODE_HOOKS) need(GRANT_GCODE)
            if (ev !in EVENTS) throw Context.reportRuntimeError("filum.on: unknown event '$ev'")
            p.handlers.getOrPut(ev) { mutableListOf() } += f
            refresh(); null
        }
        fn(filum, "log") { _, a -> services.log(id, 'i', a.joinToString(" ") { show(it) }); null }
        fn(filum, "notify") { _, a -> need(GRANT_NOTIFY); services.notify(str(a, 0), str(a, 1)); null }

        val timer = { repeat: Boolean -> { _: Context, a: Array<Any?> ->
            val ms = (Context.toNumber(a.getOrNull(0))).toLong().coerceAtLeast(if (repeat) 1000 else 0)
            val f = a.getOrNull(1) as? Function ?: throw Context.reportRuntimeError("second argument must be a function")
            val tid = nextId++
            val task = Runnable {
                if (loaded[id] !== p) return@Runnable
                if (!repeat) p.timers.remove(tid)
                run(p, HOOK_MS) { c -> f.call(c, scope, scope, emptyArray()) }
            }
            p.timers[tid] = if (repeat) executor.scheduleWithFixedDelay(task, ms, ms, TimeUnit.MILLISECONDS)
            else executor.schedule(task, ms, TimeUnit.MILLISECONDS)
            tid
        } }
        fn(filum, "every", timer(true))
        fn(filum, "after", timer(false))
        fn(filum, "cancel") { _, a -> p.timers.remove(Context.toNumber(a.getOrNull(0)).toInt())?.cancel(false); null }

        val printer = cx.newObject(scope)
        ScriptableObject.putProperty(filum, "printer", printer)
        fn(printer, "gcode") { _, a -> need(GRANT_GCODE); services.sendGcode(str(a, 0)); null }
        fn(printer, "state") { c, _ -> toJs(c, scope, services.state()) }
        fn(printer, "query") { c, a ->
            val names = (a.getOrNull(0) as? NativeArray)?.map { Context.toString(it) } ?: listOf(str(a, 0))
            services.query(names, QUERY_MS)?.let { toJs(c, scope, it) }
        }

        val ui = cx.newObject(scope)
        ScriptableObject.putProperty(filum, "ui", ui)
        fn(ui, "toast") { _, a -> services.toast(str(a, 0)); null }
        fn(ui, "section") { c, a -> val sid = nextId++; setSection(c, p, sid, str(a, 0), a.getOrNull(1)); sid }
        fn(ui, "update") { c, a ->
            val sid = Context.toNumber(a.getOrNull(0)).toInt()
            val old = _sections.value.firstOrNull { it.pluginId == id && it.id == sid }
                ?: throw Context.reportRuntimeError("filum.ui.update: no section $sid")
            setSection(c, p, sid, if (a.size > 2) str(a, 2) else old.title, a.getOrNull(1)); null
        }
        fn(ui, "remove") { _, a ->
            val sid = Context.toNumber(a.getOrNull(0)).toInt()
            p.sectionCallbacks.remove(sid)
            _sections.update { l -> l.filterNot { it.pluginId == id && it.id == sid } }; null
        }

        val storage = cx.newObject(scope)
        ScriptableObject.putProperty(filum, "storage", storage)
        fn(storage, "get") { c, a ->
            services.storageGet(id, str(a, 0))?.let { NativeJSON.parse(c, scope, it, REVIVER) } ?: a.getOrNull(1)
        }
        fn(storage, "set") { c, a ->
            val v = a.getOrNull(1)
            services.storageSet(id, str(a, 0), if (v == null || Undefined.isUndefined(v)) null
                else Context.toString(NativeJSON.stringify(c, scope, v, null, null)))
            null
        }

        val device = cx.newObject(scope)
        ScriptableObject.putProperty(filum, "device", device)
        fn(device, "register") { _, a ->
            need(GRANT_DEVICE)
            val d = a.getOrNull(0) as? Scriptable ?: throw Context.reportRuntimeError("filum.device.register: pass an object")
            fun prop(n: String) = ScriptableObject.getProperty(d, n).takeUnless { it == Scriptable.NOT_FOUND || Undefined.isUndefined(it) }
            if (prop("connect") !is Function) throw Context.reportRuntimeError("filum.device.register: connect(address) is required")
            p.driver = d
            val type = DeviceType(id, prop("name")?.let { Context.toString(it) } ?: p.meta.name,
                prop("addressHint")?.let { Context.toString(it) }.orEmpty())
            _devices.update { l -> l.filterNot { it.pluginId == id } + type }
            // устройство уже выбрано в приложении — подключаемся, как только плагин загрузится
            pendingDevice.remove(id)?.let { (addr, l) ->
                executor.execute { if (loaded[id] === p) { p.deviceListener = l; callDriver(p, "connect", addr) } }
            }
            null
        }
        fn(device, "update") { c, a ->
            val l = p.deviceListener ?: return@fn null
            val v = a.getOrNull(0) as? Scriptable ?: return@fn null
            l.onState(JSONObject(Context.toString(NativeJSON.stringify(c, scope, v, null, null))))
            null
        }
        fn(device, "log") { _, a ->
            p.deviceListener?.onLog(if (a.size > 1) str(a, 0).firstOrNull() ?: '<' else '<', str(a, a.size - 1))
            null
        }
        fn(device, "connected") { _, _ -> p.deviceListener != null }

        val http = cx.newObject(scope)
        ScriptableObject.putProperty(filum, "http", http)
        fun response(c: Context, r: Pair<Int, String>): Scriptable = c.newObject(scope).also {
            ScriptableObject.putProperty(it, "status", r.first)
            ScriptableObject.putProperty(it, "body", r.second)
        }
        fn(http, "get") { c, a -> need(GRANT_HTTP); response(c, services.http("GET", str(a, 0), null, null)) }
        fn(http, "post") { c, a ->
            need(GRANT_HTTP)
            val body = a.getOrNull(1)
            val text = if (body is Scriptable) Context.toString(NativeJSON.stringify(c, scope, body, null, null)) else str(a, 1)
            val type = if (a.size > 2) str(a, 2) else if (body is Scriptable) "application/json" else "text/plain"
            response(c, services.http("POST", str(a, 0), text, type))
        }
        return filum
    }

    /** Разбирает массив описаний элементов `[{type:'button', label, onClick}, …]`. */
    private fun setSection(cx: Context, p: Loaded, sid: Int, title: String, raw: Any?) {
        val arr = raw as? NativeArray ?: throw Context.reportRuntimeError("filum.ui: items must be an array")
        val items = mutableListOf<PluginItem>()
        val callbacks = mutableListOf<Function?>()
        for (o in arr) {
            val s = o as? Scriptable ?: continue
            fun prop(n: String): Any? = ScriptableObject.getProperty(s, n).takeUnless { it == Scriptable.NOT_FOUND || Undefined.isUndefined(it) }
            fun text(n: String, def: String = "") = prop(n)?.let { Context.toString(it) } ?: def
            fun num(n: String, def: Double) = prop(n)?.let { Context.toNumber(it) } ?: def
            val item = when (val t = text("type")) {
                "text" -> PluginItem.Text(text("text"))
                "button" -> PluginItem.Button(text("label"))
                "switch" -> PluginItem.Switch(text("label"), prop("value") == true)
                "slider" -> PluginItem.Slider(text("label"), num("min", 0.0), num("max", 100.0), num("step", 1.0), num("value", 0.0))
                "input" -> PluginItem.Input(text("label"), text("value"), text("button", "OK"))
                else -> throw Context.reportRuntimeError("filum.ui: unknown item type '$t'")
            }
            items += item
            callbacks += (prop("onClick") ?: prop("onChange") ?: prop("onSubmit")) as? Function
        }
        p.sectionCallbacks[sid] = callbacks
        val section = PluginSection(p.meta.id, sid, title, items)
        _sections.update { l -> if (l.any { it.pluginId == p.meta.id && it.id == sid }) l.map { if (it.pluginId == p.meta.id && it.id == sid) section else it } else l + section }
    }

    private fun fn(obj: Scriptable, name: String, body: (Context, Array<Any?>) -> Any?) {
        ScriptableObject.putProperty(obj, name, object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? = try {
                body(cx, args) ?: Undefined.instance
            } catch (e: RhinoException) {
                throw e
            } catch (e: Exception) {
                // ошибки сервисов (сеть, нет связи с принтером) — обычный JS Error, его ловит try/catch плагина
                throw ScriptRuntime.constructError("Error", e.message ?: e.javaClass.simpleName)
            }
            override fun getFunctionName() = name
        })
    }

    private fun str(a: Array<Any?>, i: Int): String = a.getOrNull(i)?.takeUnless { Undefined.isUndefined(it) }?.let { Context.toString(it) }.orEmpty()

    private fun show(v: Any?): String = when {
        v == null -> "null"
        Undefined.isUndefined(v) -> "undefined"
        v is Scriptable && v !is Function -> Context.enter().use { Context.toString(NativeJSON.stringify(it, v, v, null, null)) }
        else -> Context.toString(v)
    }

    /** Kotlin → JS: строки и числа как есть, JSONObject/JSONArray через JSON.parse. */
    private fun toJs(cx: Context, scope: Scriptable, v: Any?): Any? = when (v) {
        null -> null
        is JSONObject, is JSONArray -> NativeJSON.parse(cx, scope, v.toString(), REVIVER)
        is Int -> v.toDouble()
        else -> v
    }

    /** JS → Kotlin для результатов хуков: undefined → Unit, объект → JSONObject, строка → String. */
    private fun fromJs(cx: Context, scope: Scriptable, v: Any?): Any? = when {
        v == null -> null
        Undefined.isUndefined(v) -> Unit
        v is Boolean -> v
        v is CharSequence -> v.toString()
        v is Scriptable && v !is Function -> {
            val json = Context.toString(NativeJSON.stringify(cx, scope, v, null, null))
            if (json.startsWith("{")) JSONObject(json) else JSONArray(json)
        }
        else -> Context.toString(v)
    }

    private fun Context.use(block: (Context) -> String): String = try { block(this) } finally { Context.exit() }

    companion object {
        const val API_VERSION = 1
        const val LOAD_MS = 5_000L
        const val HOOK_MS = 2_000L
        const val FILE_MS = 60_000L
        const val QUERY_MS = 5_000L
        private val ACTIVE = setOf("printing", "paused")
        val GCODE_HOOKS = setOf("gcode.send", "print.start", "slice", "gcode.file")
        val EVENTS = GCODE_HOOKS + setOf("load", "unload", "state", "print.started", "print.finished",
            "print.cancelled", "print.paused", "print.resumed", "print.error")
        private val REVIVER = Callable { _, _, _, args -> args[1] }
    }
}
