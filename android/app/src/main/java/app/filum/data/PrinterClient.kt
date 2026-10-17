package app.filum.data

import app.filum.tr
import app.filum.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class ConnState { Disconnected, Connecting, Connected }

data class LogLine(val time: Long, val dir: Char, val text: String)

/**
 * Клиент Moonraker (JSON-RPC по WebSocket, ws://host:7125/websocket) —
 * тот же API, через который работает Fluidd/Mainsail.
 */
class PrinterClient(private val scope: CoroutineScope) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(PrinterState())
    val state: StateFlow<PrinterState> = _state.asStateFlow()

    private val _connection = MutableStateFlow(ConnState.Disconnected)
    val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    /** Последняя ошибка из ответов G-code (с уточнениями вроде слота CFS); сбрасывается, когда печать идёт. */
    private val _fault = MutableStateFlow<ActiveFault?>(null)
    val fault: StateFlow<ActiveFault?> = _fault.asStateFlow()

    private val _files = MutableStateFlow<List<GcodeFile>?>(null)
    val files: StateFlow<List<GcodeFile>?> = _files.asStateFlow()

    /** Писать в консоль и обновления статуса (их очень много). */
    @Volatile var verboseLog = false

    private val objects = HashMap<String, JSONObject>()
    private var hostname: String? = null
    private val nextId = AtomicInteger(1)
    private val pending = HashMap<Int, (Any?, JSONObject?) -> Unit>()

    private var ws: WebSocket? = null
    private var url: String? = null
    private var generation = 0
    private var reconnectJob: Job? = null
    private var subscribeJob: Job? = null

    @Synchronized
    fun connect(host: String) {
        disconnectInternal()
        val h = host.trim()
        if (h.isEmpty()) return
        url = "ws://${if (h.contains(':')) h else "$h:$MOONRAKER_PORT"}/websocket"
        synchronized(objects) { objects.clear() }
        hostname = null
        caps = PrinterCaps()
        _fault.value = null
        _state.value = PrinterState()
        _files.value = null
        open()
    }

    @Synchronized
    fun disconnect() {
        disconnectInternal()
        url = null
    }

    private fun disconnectInternal() {
        generation++
        reconnectJob?.cancel()
        subscribeJob?.cancel()
        ws?.close(1000, null)
        ws = null
        synchronized(pending) { pending.clear() }
        _connection.value = ConnState.Disconnected
    }

    @Synchronized
    private fun open() {
        val u = url ?: return
        val gen = ++generation
        _connection.value = ConnState.Connecting
        addLog('!', tr(R.string.log_connecting, u))
        ws = http.newWebSocket(Request.Builder().url(u).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                _connection.value = ConnState.Connected
                addLog('!', tr(R.string.log_connected))
                call("printer.info") { r, _ -> hostname = (r as? JSONObject)?.optString("hostname"); publish() }
                discover()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) onLost(tr(R.string.log_closed, code))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) onLost(tr(R.string.log_conn_error, t.message ?: t.javaClass.simpleName))
            }
        })
    }

    private fun onLost(reason: String) {
        _connection.value = ConnState.Connecting
        addLog('!', tr(R.string.log_retry, reason))
        subscribeJob?.cancel()
        synchronized(pending) { pending.clear() }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            open()
        }
    }

    /** Возможности принтера: объекты Klipper и команды G-code; затем подписка. */
    private fun discover() {
        call("printer.objects.list") { r, _ ->
            val list = (r as? JSONObject)?.optJSONArray("objects")
            if (list == null) { subscribe(); return@call }
            val objects = (0 until list.length()).map { list.optString(it) }.toSet()
            call("printer.gcode.help") { h, _ ->
                val cmds = (h as? JSONObject)?.keys()?.asSequence()?.map { it.uppercase() }?.toSet().orEmpty()
                caps = PrinterCaps(known = true, objects = objects, commands = cmds)
                subscribe()
            }
        }
    }

    @Volatile private var caps = PrinterCaps()
    val capabilities get() = caps

    /** Подписка на объекты Klipper; если Klipper ещё не готов — повторяем. */
    private fun subscribe() {
        val objs = JSONObject()
        caps.subscribeList().forEach { objs.put(it, JSONObject.NULL) }
        call("printer.objects.subscribe", JSONObject().put("objects", objs)) { result, error ->
            if (result is JSONObject) {
                result.optJSONObject("status")?.let { merge(it) }
            } else {
                addLog('!', tr(R.string.log_klipper_not_ready, error?.optString("message")))
                subscribeJob?.cancel()
                subscribeJob = scope.launch { delay(2000); subscribe() }
            }
        }
    }

    private fun handleMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }

        if (msg.has("id")) {
            val cb = synchronized(pending) { pending.remove(msg.optInt("id")) }
            val error = msg.optJSONObject("error")
            if (error != null) addLog('!', tr(R.string.log_error, error.optString("message")))
            cb?.invoke(if (error == null) msg.opt("result") else null, error)
            return
        }

        val params = msg.optJSONArray("params")
        when (msg.optString("method")) {
            "notify_status_update" -> {
                params?.optJSONObject(0)?.let { merge(it) }
                if (verboseLog) addLog('<', text)
            }
            "notify_gcode_response" -> {
                val line = params?.optString(0).orEmpty()
                addLog('<', line)
                Faults.parseResponse(line)?.let { _fault.value = ActiveFault(it, System.currentTimeMillis()) }
            }
            "notify_klippy_ready" -> { addLog('!', tr(R.string.log_klipper_ready)); discover() }
            "notify_klippy_shutdown" -> { addLog('!', tr(R.string.klipper_shutdown)); setWebhooks("shutdown") }
            "notify_klippy_disconnected" -> {
                addLog('!', tr(R.string.log_klipper_disconnected))
                setWebhooks("disconnected")
                subscribeJob?.cancel()
                subscribeJob = scope.launch { delay(2000); subscribe() }
            }
            "notify_filelist_changed" -> if (_files.value != null) requestFiles()
            else -> if (verboseLog) addLog('<', text)
        }
    }

    private fun merge(status: JSONObject) {
        synchronized(objects) {
            for (name in status.keys()) {
                val diff = status.optJSONObject(name) ?: continue
                val obj = objects.getOrPut(name) { JSONObject() }
                for (k in diff.keys()) obj.put(k, diff.opt(k))
            }
        }
        publish()
    }

    private fun setWebhooks(state: String) {
        synchronized(objects) { objects.getOrPut("webhooks") { JSONObject() }.put("state", state) }
        publish()
    }

    private fun publish() {
        val s = synchronized(objects) { PrinterState.from(objects, hostname, caps) }
        // печать возобновилась или началась новая — прошлая ошибка уже не актуальна
        if (s.isPrinting && _state.value.printState != "printing") _fault.value = null
        _state.value = s
    }

    private fun addLog(dir: Char, text: String) {
        val line = LogLine(System.currentTimeMillis(), dir, text.take(2000))
        _log.update { (it + line).takeLast(400) }
    }

    fun clearLog() { _log.value = emptyList() }

    // ---------- JSON-RPC ----------

    private fun call(
        method: String,
        params: JSONObject? = null,
        callback: ((Any?, JSONObject?) -> Unit)? = null,
    ): Boolean {
        val socket = ws
        if (socket == null || _connection.value != ConnState.Connected) {
            addLog('!', tr(R.string.log_not_sent, method))
            return false
        }
        val id = nextId.getAndIncrement()
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", method).put("id", id)
        if (params != null) msg.put("params", params)
        if (callback != null) synchronized(pending) { pending[id] = callback }
        return socket.send(msg.toString())
    }

    // ---------- Команды ----------

    /** Диаметр сопла: команда прошивки Creality, сохраняет user_nozzle_diameter в printer.cfg без перезапуска. */
    fun setNozzleDiameter(mm: Double) = gcode("SET_NOZZLE_DIAMETER VALUE=%.2f".format(java.util.Locale.US, mm))

    fun gcode(script: String) {
        addLog('>', script.replace("\n", " ⏎ "))
        call("printer.gcode.script", JSONObject().put("script", script)) { _, _ -> }
    }

    fun pause() { addLog('>', tr(R.string.pause)); call("printer.print.pause") }
    fun resume() { addLog('>', tr(R.string.resume)); call("printer.print.resume") }
    fun stop() { addLog('>', tr(R.string.log_cancel_print)); call("printer.print.cancel") }

    fun setLight(on: Boolean) = caps.lightPin?.let { gcode("SET_PIN PIN=$it VALUE=${if (on) 1 else 0}") }

    fun setNozzleTemp(t: Int) = gcode("M104 S$t")
    fun setBedTemp(t: Int) = gcode("M140 S$t")
    /** Макрос K2: ≤40° — поддержание вентилятором камеры, >40° — нагреватель камеры. */
    fun setChamberTemp(t: Int) = gcode("M141 S$t")

    /**
     * fan: 0 — обдув модели, 1 — боковой, 2 — камерный (макросы M106/M107 Creality с P0–P2).
     * На принтерах со стандартным `[fan]` Klipper — обычные M106/M107 без P.
     */
    fun setFan(fan: Int, pct: Int) {
        val s = (pct.coerceIn(0, 100) * 255 + 50) / 100
        gcode(if (caps.crealityFans) { if (pct <= 0) "M107 P$fan" else "M106 P$fan S$s" }
              else { if (pct <= 0) "M107" else "M106 S$s" })
    }

    fun setSpeedPct(pct: Int) = gcode("M220 S$pct")
    fun setFlowPct(pct: Int) = gcode("M221 S$pct")

    fun home(axes: String = "") = gcode(if (axes.isEmpty()) "G28" else "G28 $axes")
    fun move(axis: Char, dist: Double, feed: Int) =
        gcode("G91\nG1 $axis${fmt(dist)} F$feed\nG90")
    fun extrude(mm: Double) = gcode("M83\nG1 E${fmt(mm)} F300")
    fun motorsOff() = gcode("M84")

    fun requestFiles() {
        call("server.files.list", JSONObject().put("root", "gcodes")) { result, _ ->
            if (result is JSONArray) _files.value = parseGcodeFiles(result)
        }
    }

    fun printFile(path: String) {
        addLog('>', tr(R.string.log_print_file, path))
        call("printer.print.start", JSONObject().put("filename", path))
    }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    companion object {
        const val MOONRAKER_PORT = 7125

    }
}
