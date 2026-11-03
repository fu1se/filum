package app.filum.notify

import app.filum.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.filum.MainActivity
import app.filum.R
import app.filum.data.CrealityStatus
import app.filum.data.Fault
import app.filum.data.Faults
import app.filum.data.PrinterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Следит за принтером в фоне и показывает уведомления:
 * постоянное — во время печати (прогресс, слой, остаток, пауза/продолжить),
 * разовые — об окончании печати и об ошибках.
 *
 * Своё лёгкое подключение к Moonraker: подписка только на print_stats, virtual_sdcard и
 * webhooks (без температур), плюс ответы G-code — в них прошивка K2 присылает ошибки.
 * И подключение к службам Creality (:9999) ради поля `err` — так приходят ошибки, которых
 * Klipper не видит (ИИ-камера). Пояснения к ошибкам — data/Faults.kt.
 * Android требует уведомление у фонового сервиса и без печати: оно на канале «Мониторинг»
 * с минимальной важностью (без значка в строке состояния), его можно скрыть в настройках.
 */
class PrintMonitorService : Service() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val exec = Executors.newSingleThreadScheduledExecutor()

    private var ws: WebSocket? = null
    private var host = ""
    private var generation = 0
    private var connected = false
    private var reconnect: ScheduledFuture<*>? = null
    private var retryDelay = 3L
    private var nextId = 1

    private val objects = HashMap<String, JSONObject>()
    private var hostname: String? = null
    private var lastState: PrinterState? = null
    /** Отправленная пауза/продолжение: print_stats.state на момент нажатия и время (см. PrinterClient.busy). */
    private var actionFrom: String? = null
    private var actionAt = 0L

    private fun actionPending(s: PrinterState?) =
        actionAt != 0L && s?.printState == actionFrom && System.currentTimeMillis() - actionAt < 5 * 60_000L

    /** Повторное нажатие, пока макрос PAUSE/RESUME ещё идёт, игнорируется. */
    private fun printAction(method: String) {
        if (actionPending(lastState)) return
        actionFrom = lastState?.printState
        actionAt = System.currentTimeMillis()
        call(method) { exec.execute { actionAt = 0L; render() } }
        render()
    }
    private var lastKlippy: String? = null
    private var lastShown: String? = null
    private var lastError: Pair<String, Long>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val creality = CrealityStatus(scope)
    private var notifId = 100

    private val nm get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Faults.init(this)
        createChannels(this)
        scope.launch { creality.fault.collect { f -> if (f != null) exec.execute { notifyFault(f.fault) } } }
        ServiceCompat.startForeground(
            this, ONGOING_ID, monitorNotification(tr(R.string.connecting)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> exec.execute { printAction("printer.print.pause") }
            ACTION_RESUME -> exec.execute { printAction("printer.print.resume") }
            else -> exec.execute {
                val h = prefs(this).getString("host", "").orEmpty().trim()
                // устройство на драйвере-плагине (ЧПУ и т. п.) — не Moonraker, следить нечем
                if (h.isEmpty() || prefs(this).getString("driver", "").orEmpty().isNotEmpty()) { stopSelf(); return@execute }
                if (h != host || ws == null) { host = h; open() }
                creality.connect(h)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        generation++
        ws?.close(1000, null)
        creality.disconnect()
        scope.cancel()
        exec.shutdownNow()
        super.onDestroy()
    }

    // ---------- Moonraker ----------

    private fun open() {
        val gen = ++generation
        ws?.cancel()
        reconnect?.cancel(false)
        connected = false
        synchronized(objects) { objects.clear() }
        val url = "ws://${if (host.contains(':')) host else "$host:7125"}/websocket"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = exec.execute {
                if (gen != generation) return@execute
                connected = true
                retryDelay = 3
                call("printer.info") { r -> hostname = r?.optString("hostname")?.ifBlank { null } }
                subscribe()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = exec.execute {
                if (gen == generation) handle(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = exec.execute {
                if (gen == generation) lost()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = exec.execute {
                if (gen == generation) lost()
            }
        })
    }

    private fun lost() {
        connected = false
        render()
        reconnect?.cancel(false)
        reconnect = exec.schedule({ open() }, retryDelay, TimeUnit.SECONDS)
        retryDelay = (retryDelay * 2).coerceAtMost(60)
    }

    private val callbacks = HashMap<Int, (JSONObject?) -> Unit>()

    private fun call(method: String, params: JSONObject? = null, cb: ((JSONObject?) -> Unit)? = null) {
        val socket = ws ?: return
        val id = nextId++
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", method).put("id", id)
        if (params != null) msg.put("params", params)
        if (cb != null) callbacks[id] = cb
        socket.send(msg.toString())
    }

    private fun subscribe() {
        val objs = JSONObject().put("print_stats", JSONObject.NULL).put("virtual_sdcard", JSONObject.NULL)
            .put("webhooks", JSONObject.NULL)
        call("printer.objects.subscribe", JSONObject().put("objects", objs)) { r ->
            val status = r?.optJSONObject("status")
            if (status == null) exec.schedule({ subscribe() }, 3, TimeUnit.SECONDS) else merge(status)
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (msg.has("id")) {
            callbacks.remove(msg.optInt("id"))?.invoke(msg.optJSONObject("result"))
            return
        }
        val params = msg.optJSONArray("params")
        when (msg.optString("method")) {
            "notify_status_update" -> params?.optJSONObject(0)?.let(::merge)
            "notify_gcode_response" -> Faults.parseResponse(params?.optString(0).orEmpty())?.let(::notifyFault)
            "notify_klippy_ready" -> subscribe()
            "notify_klippy_shutdown" -> setKlippy("shutdown")
            "notify_klippy_disconnected" -> {
                setKlippy("disconnected")
                exec.schedule({ subscribe() }, 3, TimeUnit.SECONDS)
            }
        }
    }

    private fun setKlippy(state: String) {
        synchronized(objects) { objects.getOrPut("webhooks") { JSONObject() }.put("state", state) }
        render()
    }

    private fun merge(status: JSONObject) {
        synchronized(objects) {
            for (name in status.keys()) {
                val diff = status.optJSONObject(name) ?: continue
                val obj = objects.getOrPut(name) { JSONObject() }
                for (k in diff.keys()) obj.put(k, diff.opt(k))
            }
        }
        render()
    }

    // ---------- уведомления ----------

    private fun render() {
        val s = synchronized(objects) { PrinterState.from(objects, hostname) }
        val prev = lastState
        if (connected && s.printState != null) {
            transitions(prev, s)
            lastState = s
        }
        val n = when {
            s.hasActiveJob -> progressNotification(s)
            !connected -> monitorNotification(tr(R.string.notif_no_link_host, host))
            else -> monitorNotification(s.statusText)
        }
        val key = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString() + "|" +
            n.extras.getCharSequence(Notification.EXTRA_TEXT) + "|" + n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) +
            "|" + n.extras.getInt(Notification.EXTRA_PROGRESS)
        if (key != lastShown) {
            lastShown = key
            nm.notify(ONGOING_ID, n)
        }
    }

    /** Разовые уведомления — только на переходах, которые видели сами (не при подключении). */
    private fun transitions(prev: PrinterState?, s: PrinterState) {
        val klippy = s.klippyState
        if (lastKlippy == "ready" && klippy != null && klippy != "ready" && klippy != "startup") {
            notifyFault(Fault(0, if (klippy == "shutdown") tr(R.string.klipper_shutdown) else "Klipper: $klippy", null,
                tr(R.string.notif_restart_klipper), s.klippyMessage))
        }
        lastKlippy = klippy
        if (prev == null || prev.printState == s.printState) return
        val file = (s.fileName ?: prev.fileName)?.substringAfterLast('/')
        when (s.printState) {
            "complete" -> if (prev.hasActiveJob) post(CH_EVENTS, tr(R.string.status_complete), listOfNotNull(file,
                prev.jobTimeSec?.let { tr(R.string.notif_took, fmtDuration(it)) }).joinToString(" · "), NotificationCompat.PRIORITY_HIGH)
            "cancelled" -> if (prev.hasActiveJob) post(CH_EVENTS, tr(R.string.status_cancelled), file, NotificationCompat.PRIORITY_DEFAULT)
            "error" -> notifyFault(Faults.parseResponse("!! " + (s.printMessage ?: ""))
                ?.let { if (it.code == 0) it.copy(title = tr(R.string.status_error)) else it }
                ?: Fault(0, tr(R.string.status_error), null, null, file))
            "paused" -> {
                // пауза из-за ошибки уже пришла уведомлением об ошибке; иначе — просто пауза
                val recent = lastError?.let { System.currentTimeMillis() - it.second < 20_000 } == true
                if (prev.isPrinting && !recent) post(CH_EVENTS, tr(R.string.notif_paused), file, NotificationCompat.PRIORITY_DEFAULT)
            }
        }
    }

    /** Одна ошибка приходит и ответом G-code, и через :9999 — не дублируем в течение минуты. */
    private fun notifyFault(f: Fault) {
        val now = System.currentTimeMillis()
        val id = if (f.code != 0) f.code.toString() else f.title
        val last = lastError
        if (last != null && last.first == id && now - last.second < 60_000) return
        lastError = id to now
        val long = listOfNotNull(f.detail, f.what, f.todo?.let { tr(R.string.notif_todo, it) }).joinToString("\n")
        post(CH_ERRORS, f.title, (f.detail ?: f.what ?: f.todo), NotificationCompat.PRIORITY_HIGH, long.ifBlank { null })
    }

    private fun post(channel: String, title: String, text: String?, priority: Int, long: String? = null) {
        val b = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_printer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(long ?: text))
            .setSubText(hostname ?: host)
            .setPriority(priority)
            .setCategory(if (channel == CH_ERRORS) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp())
            .setAutoCancel(true)
        nm.notify(notifId++, b.build())
    }

    private fun progressNotification(s: PrinterState): Notification {
        val pct = s.progress ?: 0
        val paused = s.isPaused
        val details = listOfNotNull(
            if (s.layer != null && s.totalLayers != null && s.totalLayers > 0) tr(R.string.layer_of, s.layer, s.totalLayers) else null,
            s.leftTimeSec?.takeIf { !paused }?.let { tr(R.string.notif_left, fmtDuration(it)) },
            s.jobTimeSec?.let { tr(R.string.notif_elapsed, fmtDuration(it)) },
        ).joinToString(" · ")
        val b = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_printer)
            .setContentTitle(if (paused) tr(R.string.notif_paused_pct, pct) else tr(R.string.notif_printing_pct, pct))
            .setContentText(s.fileName?.substringAfterLast('/') ?: details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                listOfNotNull(s.fileName?.substringAfterLast('/'), details.ifBlank { null },
                    if (!connected) tr(R.string.notif_no_link) else null).joinToString("\n")))
            .setSubText(hostname ?: host)
            .setProgress(100, pct, s.progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
        if (connected && !actionPending(s)) {
            if (paused) b.addAction(0, tr(R.string.resume), action(ACTION_RESUME))
            else b.addAction(0, tr(R.string.pause), action(ACTION_PAUSE))
        }
        return b.build()
    }

    private fun monitorNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_printer)
            .setContentTitle(hostname ?: "Filum")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(a: String): PendingIntent = PendingIntent.getService(
        this, a.hashCode(), Intent(this, PrintMonitorService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun fmtDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) tr(R.string.duration_h_min, h, m) else tr(R.string.duration_min, m)
    }

    companion object {
        const val ONGOING_ID = 1
        const val CH_PROGRESS = "print_progress"
        const val CH_EVENTS = "print_events"
        const val CH_ERRORS = "printer_errors"
        const val CH_MONITOR = "monitor"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"

        fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun enabled(ctx: Context) = prefs(ctx).getBoolean("notifications", true) &&
            !prefs(ctx).getString("host", "").isNullOrBlank()

        /** Запускает (или переподключает к новому адресу) мониторинг; останавливает, если выключен. */
        fun sync(ctx: Context) {
            val i = Intent(ctx, PrintMonitorService::class.java)
            if (enabled(ctx)) ContextCompat.startForegroundService(ctx, i)
            else ctx.startService(i.setAction(ACTION_STOP))
        }

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(listOf(
                NotificationChannel(CH_PROGRESS, tr(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                    description = tr(R.string.channel_progress_desc)
                },
                NotificationChannel(CH_EVENTS, tr(R.string.channel_events), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = tr(R.string.channel_events_desc)
                },
                NotificationChannel(CH_ERRORS, tr(R.string.channel_errors), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = tr(R.string.channel_errors_desc)
                },
                NotificationChannel(CH_MONITOR, tr(R.string.channel_monitor), NotificationManager.IMPORTANCE_MIN).apply {
                    description = tr(R.string.channel_monitor_desc)
                    setShowBadge(false)
                },
            ))
        }
    }
}
