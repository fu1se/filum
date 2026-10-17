package app.filum.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Текущая ошибка с экрана принтера: поле `err` штатного WebSocket прошивки Creality (:9999). */
data class ActiveFault(val fault: Fault, val since: Long)

/** Что службы Creality сообщают о самом принтере. */
data class CrealityInfo(
    /** Как пришло: «F008», «K1C», «CR-K1 Max»… */
    val model: String?,
    /** Камера по WebRTC на :8000 (K2, Hi, новые K1C); иначе MJPEG на :8080. */
    val webrtc: Boolean,
    val camera: Boolean,
    val cfs: Boolean,
) {
    /** Понятное название: у K2, Hi и Ender-3 V3 прошивка шлёт код платы. */
    val modelName: String? get() = model?.let { MODELS[it] ?: it.removePrefix("CR-") }

    companion object {
        val MODELS = mapOf(
            "F008" to "K2 Plus", "F012" to "K2 Pro", "F021" to "K2",
            "F001" to "Ender-3 V3", "F002" to "Ender-3 V3 Plus", "F005" to "Ender-3 V3 KE", "F018" to "Creality Hi",
        )
    }
}

/**
 * Слушает статус служб прошивки Creality на :9999 — тот же поток, что у экрана принтера и Creality Print.
 * Нужен ради `err: {errcode, key, value}`: туда попадают все ошибки, включая те, что Klipper не видит
 * (ИИ-камера) или видит лишь как обычную паузу (датчик филамента, CFS).
 */
class CrealityStatus(private val scope: CoroutineScope) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _fault = MutableStateFlow<ActiveFault?>(null)
    val fault: StateFlow<ActiveFault?> = _fault.asStateFlow()

    private val _info = MutableStateFlow<CrealityInfo?>(null)
    val info: StateFlow<CrealityInfo?> = _info.asStateFlow()

    private var ws: WebSocket? = null
    private var host: String? = null
    private var generation = 0
    private var retry: Job? = null
    private var lastCode = 0

    @Synchronized
    fun connect(h: String) {
        val clean = h.substringBefore(':').trim()
        if (clean == host && ws != null) return
        disconnect()
        if (clean.isEmpty()) return
        host = clean
        open()
    }

    @Synchronized
    fun disconnect() {
        generation++
        retry?.cancel()
        ws?.close(1000, null)
        ws = null
        host = null
        lastCode = 0
        _fault.value = null
        _info.value = null
    }

    @Synchronized
    private fun open() {
        val h = host ?: return
        val gen = ++generation
        ws = http.newWebSocket(Request.Builder().url("ws://$h:9999").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                if (text.contains("heart_beat")) { webSocket.send("ok"); return }
                val hasErr = text.contains("\"err\"")
                val hasInfo = text.contains("\"model\"") || text.contains("\"webrtcSupport\"")
                if (!hasErr && !hasInfo) return
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (hasInfo) updateInfo(o)
                o.opt("err")?.let(::update)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(gen)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(gen)
        })
    }

    private fun lost(gen: Int) {
        if (gen != generation) return
        retry?.cancel()
        retry = scope.launch { delay(5000); if (gen == generation) open() }
    }

    private fun updateInfo(o: JSONObject) {
        val old = _info.value
        _info.value = CrealityInfo(
            model = o.optString("model").ifBlank { null } ?: old?.model,
            webrtc = if (o.has("webrtcSupport")) o.optInt("webrtcSupport") == 1 else old?.webrtc ?: false,
            camera = if (o.has("video")) o.optInt("video") == 1 else old?.camera ?: true,
            cfs = if (o.has("cfsConnect")) o.optInt("cfsConnect") == 1 else old?.cfs ?: false,
        )
    }

    private fun update(err: Any) {
        // прошивки шлют либо {errcode, key, value}, либо голое число
        val code = when (err) {
            is JSONObject -> err.optInt("errcode")
            is Number -> err.toInt()
            else -> err.toString().toIntOrNull() ?: 0
        }
        if (code == lastCode) return
        lastCode = code
        val value = (err as? JSONObject)?.optString("value")?.ifBlank { null }
        _fault.value = Faults.describe(code, value)?.let { ActiveFault(it, System.currentTimeMillis()) }
    }
}
