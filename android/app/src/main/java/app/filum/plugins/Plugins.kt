package app.filum.plugins

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import app.filum.R
import app.filum.data.PrinterClient
import app.filum.data.PrinterState
import app.filum.notify.PrintMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Плагины приложения: файлы `filesDir/plugins/<id>.js`, флаги «включён» в SharedPreferences, данные плагинов —
 * в своих SharedPreferences. Один [PluginHost] на процесс; принтер подключает [PrinterViewModel] через [attach].
 */
object Plugins {
    lateinit var host: PluginHost
        private set
    private lateinit var app: Context
    private val dir get() = File(app.filesDir, "plugins").apply { mkdirs() }
    private val prefs get() = app.getSharedPreferences("plugins", Context.MODE_PRIVATE)

    @Volatile var client: PrinterClient? = null
        private set

    private val errors = mutableMapOf<String, String?>()

    /** Последние строки лога каждого плагина — для менеджера. */
    private val _logs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val logs: StateFlow<Map<String, List<String>>> = _logs.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        host = PluginHost(Services())
        for ((meta, src) in installed()) if (isEnabled(meta.id)) loadSource(meta.id, src)
        publish()
    }

    /** Подключает клиент принтера: хуки gcode.send / print.start и события состояния. */
    fun attach(c: PrinterClient) {
        client = c
        c.gcodeFilter = { script, cb -> host.filterGcode(script, cb) }
        c.printFilter = { file, cb -> host.allowPrint(file, cb) }
    }

    fun detach(c: PrinterClient) {
        if (client !== c) return
        c.gcodeFilter = null
        c.printFilter = null
        client = null
    }

    fun onState(s: PrinterState, connected: Boolean) = host.onState(stateJson(s, connected))

    // ---------- установка ----------

    fun installed(): List<Pair<PluginMeta, String>> =
        dir.listFiles { f -> f.extension == "js" }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val src = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            runCatching { PluginMeta.parse(src) to src }.getOrNull()
        }

    /** Проверяет заголовок, ничего не сохраняя (для диалога подтверждения). */
    fun inspect(source: String): PluginMeta = PluginMeta.parse(source)

    fun install(source: String) {
        val meta = PluginMeta.parse(source)
        File(dir, "${meta.id}.js").writeText(source)
        prefs.edit().putBoolean("enabled_${meta.id}", true).apply()
        loadSource(meta.id, source)
        publish()
    }

    fun setEnabled(id: String, on: Boolean) {
        prefs.edit().putBoolean("enabled_$id", on).apply()
        if (on) File(dir, "$id.js").takeIf { it.exists() }?.let { loadSource(id, it.readText()) }
        else { host.unload(id); errors.remove(id) }
        publish()
    }

    fun delete(id: String) {
        host.unload(id)
        File(dir, "$id.js").delete()
        prefs.edit().remove("enabled_$id").apply()
        app.deleteSharedPreferences("plugin_data_$id")
        errors.remove(id)
        _logs.update { it - id }
        publish()
    }

    fun source(id: String): String? = File(dir, "$id.js").takeIf { it.exists() }?.readText()

    /** Примеры из assets/plugins. */
    fun examples(): List<Pair<PluginMeta, String>> =
        app.assets.list("plugins").orEmpty().filter { it.endsWith(".js") }.sorted().mapNotNull { n ->
            val src = app.assets.open("plugins/$n").bufferedReader().use { it.readText() }
            runCatching { PluginMeta.parse(src) to src }.getOrNull()
        }

    private fun isEnabled(id: String) = prefs.getBoolean("enabled_$id", false)

    private fun loadSource(id: String, src: String) {
        errors[id] = null
        host.load(src) { err ->
            errors[id] = err
            if (err != null) {
                log(id, 'e', err)
                prefs.edit().putBoolean("enabled_$id", false).apply()
                host.unload(id)
            }
            publish()
        }
    }

    private fun publish() {
        host.publishList(installed().map { (m, _) -> PluginInfo(m, isEnabled(m.id), errors[m.id]) })
    }

    private fun log(id: String, level: Char, text: String) {
        val line = "${if (level == 'e') "✖" else if (level == 'w') "!" else "·"} $text"
        _logs.update { m -> m + (id to (m[id].orEmpty() + line).takeLast(50)) }
        client?.pluginLog(id, level, text)
    }

    private fun stateJson(s: PrinterState, connected: Boolean) = JSONObject()
        .put("connected", connected)
        .put("klippyState", s.klippyState)
        .put("printState", s.printState)
        .put("printMessage", s.printMessage)
        .put("fileName", s.fileName)
        .put("progress", s.progress)
        .put("layer", s.layer).put("totalLayers", s.totalLayers)
        .put("printTime", s.jobTimeSec).put("timeLeft", s.leftTimeSec)
        .put("nozzleTemp", s.nozzleTemp).put("nozzleTarget", s.nozzleTarget)
        .put("bedTemp", s.bedTemp).put("bedTarget", s.bedTarget)
        .put("chamberTemp", s.chamberTemp)
        .put("nozzleDiameter", s.nozzleDiameter)
        .put("zOffset", s.gcodeZOffset)
        .put("speedPct", s.speedPct).put("flowPct", s.flowPct)
        .put("light", s.light)
        .put("hostname", s.hostname)
        // выбранное устройство на драйвере-плагине (id плагина) или null — обычный принтер
        .put("device", app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("driver", null)?.ifEmpty { null })

    // ---------- что умеют плагины ----------

    private class Services : PluginServices {
        private val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        private val main = Handler(Looper.getMainLooper())

        override fun sendGcode(script: String) {
            client?.sendRaw(script) ?: throw IllegalStateException("printer is not connected")
        }

        override fun query(objects: List<String>, timeoutMs: Long): JSONObject? {
            val c = client ?: return null
            val latch = CountDownLatch(1)
            var out: JSONObject? = null
            c.query(objects) { out = it; latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return out
        }

        override fun state(): JSONObject = client?.let { stateJson(it.state.value, it.isConnected) } ?: JSONObject()

        override fun toast(text: String) {
            main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        }

        override fun notify(title: String, text: String) {
            PrintMonitorService.createChannels(app)
            val n = NotificationCompat.Builder(app, PrintMonitorService.CH_EVENTS)
                .setSmallIcon(R.drawable.ic_stat_printer)
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).build()
            app.getSystemService(NotificationManager::class.java).notify(("plugin" + title).hashCode(), n)
        }

        override fun http(method: String, url: String, body: String?, contentType: String?): Pair<Int, String> {
            require(url.startsWith("http://") || url.startsWith("https://")) { "only http(s) URLs" }
            val req = Request.Builder().url(url).apply {
                if (method == "POST") post((body ?: "").toRequestBody(contentType?.toMediaTypeOrNull()))
            }.build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.source()?.let { src -> src.request(MAX_BODY); src.buffer.readUtf8(minOf(src.buffer.size, MAX_BODY)) }
                return r.code to text.orEmpty()
            }
        }

        override fun storageGet(pluginId: String, key: String): String? =
            app.getSharedPreferences("plugin_data_$pluginId", Context.MODE_PRIVATE).getString(key, null)

        override fun storageSet(pluginId: String, key: String, json: String?) {
            app.getSharedPreferences("plugin_data_$pluginId", Context.MODE_PRIVATE).edit()
                .apply { if (json == null) remove(key) else putString(key, json) }.apply()
        }

        override fun log(pluginId: String, level: Char, text: String) = Plugins.log(pluginId, level, text)
    }

    private const val MAX_BODY = 1L shl 20
}

/** Устройство на драйвере-плагине [pluginId] (filum.device.register) для [PrinterClient.connectDevice]. */
class PluginDeviceBackend(private val pluginId: String) : app.filum.data.DeviceBackend {
    private val host get() = Plugins.host

    override fun connect(address: String, listener: app.filum.data.DeviceListener) = host.deviceConnect(pluginId, address, listener)
    override fun disconnect() = host.deviceDisconnect(pluginId)
    override fun command(script: String) = host.deviceCall(pluginId, "command", arrayOf(script))
    override fun jog(axis: Char, distance: Double, feed: Int) = host.deviceCall(pluginId, "jog", arrayOf(axis.toString(), distance, feed))
    override fun home(axes: String) = host.deviceCall(pluginId, "home", arrayOf(axes))
    override fun pause() = host.deviceCall(pluginId, "pause", emptyArray())
    override fun resume() = host.deviceCall(pluginId, "resume", emptyArray())
    override fun stop() = host.deviceCall(pluginId, "stop", emptyArray())
    override fun start(path: String) = host.deviceCall(pluginId, "start", arrayOf(path))

    override fun files(onResult: (List<app.filum.data.GcodeFile>?) -> Unit) = host.deviceCall(pluginId, "files", emptyArray()) { r ->
        onResult((r as? org.json.JSONArray)?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("path").ifBlank { o.optString("name") }.ifBlank { return@mapNotNull null }
                app.filum.data.GcodeFile(path, o.optLong("size", -1).takeIf { it >= 0 }, o.optDouble("modified").takeIf { !it.isNaN() })
            }
        })
    }
}
