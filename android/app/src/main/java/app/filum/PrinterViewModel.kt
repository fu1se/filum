package app.filum

import app.filum.data.ConnState
import app.filum.data.CrealityStatus
import app.filum.data.Faults
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import app.filum.data.PrinterClient
import app.filum.data.Timelapse
import app.filum.data.TimelapseRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CameraMode(@androidx.annotation.StringRes private val titleRes: Int) {
    AUTO(R.string.camera_auto),
    GO2RTC(R.string.camera_go2rtc),
    WEBRTC(R.string.camera_webrtc),
    MJPEG(R.string.camera_mjpeg),
    PAGE(R.string.camera_page),
    OFF(R.string.camera_off);

    /** Текст — при обращении, а не при создании enum: язык может смениться на ходу. */
    val title get() = tr(titleRes)
}

/** Сохранённый принтер (их может быть несколько; активный — один). */
data class SavedPrinter(
    val id: String,
    val name: String,
    val host: String,
    val cameraMode: CameraMode = CameraMode.AUTO,
    val cameraUrl: String = "",
) {
    val title get() = name.ifBlank { host }

    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("id", id).put("name", name).put("host", host)
        .put("cameraMode", cameraMode.name).put("cameraUrl", cameraUrl)

    companion object {
        fun fromJson(o: org.json.JSONObject) = SavedPrinter(
            id = o.optString("id"), name = o.optString("name"), host = o.optString("host"),
            cameraMode = runCatching { CameraMode.valueOf(o.optString("cameraMode")) }.getOrDefault(CameraMode.AUTO),
            cameraUrl = o.optString("cameraUrl"),
        )
    }
}

data class AppSettings(
    val host: String = "",
    val cameraMode: CameraMode = CameraMode.AUTO,
    /** Пустая строка — адрес по умолчанию для выбранного режима. */
    val cameraUrl: String = "",
    val slicerUrl: String = SlicerViewModel.DEFAULT_SLICER_URL,
    /** Уведомления о печати (фоновый сервис PrintMonitorService). */
    val notifications: Boolean = true,
    /** Имя активного принтера (необязательно). */
    val name: String = "",
) {
    /**
     * Режим камеры с учётом «Авто»: WebRTC на :8000, если принтер его поддерживает (K2, Hi, новые K1C),
     * иначе MJPEG на :8080 (K1, K1 Max, Ender-3 V3 KE). [webrtc] — флаг webrtcSupport служб Creality.
     */
    fun effectiveCameraMode(webrtc: Boolean?): CameraMode =
        if (cameraMode == CameraMode.AUTO) (if (webrtc == true) CameraMode.WEBRTC else CameraMode.MJPEG) else cameraMode

    fun resolvedCameraUrl(webrtc: Boolean? = null): String = cameraUrl.ifBlank {
        when (effectiveCameraMode(webrtc)) {
            CameraMode.AUTO -> ""
            // поток go2rtc не указан — страница возьмёт первый из /api/streams
            CameraMode.GO2RTC -> "http://${hostOnly()}:1984/stream.html?mode=webrtc,mse"
            CameraMode.WEBRTC -> "http://${hostOnly()}:8000/call/webrtc_local"
            CameraMode.MJPEG -> "http://${hostOnly()}:8080/?action=stream"
            CameraMode.PAGE -> "http://${hostOnly()}:4408/camera.html"
            CameraMode.OFF -> ""
        }
    }

    fun hostOnly() = host.substringBefore(':')
}

class PrinterViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val client = PrinterClient(viewModelScope)

    /** Текущая ошибка с экрана принтера (службы Creality, :9999). */
    val creality = CrealityStatus(viewModelScope)

    private val _settings = MutableStateFlow(
        AppSettings(
            host = prefs.getString("host", "").orEmpty(),
            cameraMode = runCatching { CameraMode.valueOf(prefs.getString("cameraMode", "")!!) }
                .getOrDefault(CameraMode.AUTO),
            cameraUrl = prefs.getString("cameraUrl", "").orEmpty(),
            slicerUrl = prefs.getString("slicerUrl", null)?.ifBlank { null } ?: SlicerViewModel.DEFAULT_SLICER_URL,
            notifications = prefs.getBoolean("notifications", true),
        )
    )

    init {
        // имя активного принтера
        _settings.value = _settings.value.copy(name = loadPrinters().firstOrNull { it.id == prefs.getString("active", null) }?.name.orEmpty())
    }
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // ---------- Несколько принтеров ----------

    private val _printers = MutableStateFlow(loadPrinters())
    val printers: StateFlow<List<SavedPrinter>> = _printers.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString("active", null) ?: _printers.value.firstOrNull()?.id)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /** Список из prefs; при первом запуске после обновления — из старых одиночных настроек. */
    private fun loadPrinters(): List<SavedPrinter> {
        prefs.getString("printers", null)?.let { raw ->
            return runCatching {
                val a = org.json.JSONArray(raw)
                (0 until a.length()).map { SavedPrinter.fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        val host = prefs.getString("host", "").orEmpty()
        return if (host.isBlank()) emptyList() else listOf(SavedPrinter(
            id = java.util.UUID.randomUUID().toString(), name = "", host = host,
            cameraMode = _settings.value.cameraMode, cameraUrl = _settings.value.cameraUrl,
        ))
    }

    private fun storePrinters(list: List<SavedPrinter>, active: String?) {
        _printers.value = list
        _activeId.value = active
        val p = list.firstOrNull { it.id == active }
        prefs.edit()
            .putString("printers", org.json.JSONArray(list.map { it.toJson() }).toString())
            .putString("active", active)
            // активный принтер — в прежние ключи: их читают сервис уведомлений и слайсер
            .putString("host", p?.host.orEmpty())
            .putString("cameraMode", (p?.cameraMode ?: CameraMode.AUTO).name)
            .putString("cameraUrl", p?.cameraUrl.orEmpty())
            .commit()
    }

    /** Переключиться на другой сохранённый принтер. */
    fun switchPrinter(id: String) {
        val p = _printers.value.firstOrNull { it.id == id } ?: return
        storePrinters(_printers.value, id)
        applyActive(p)
    }

    fun deletePrinter(id: String) {
        val rest = _printers.value.filter { it.id != id }
        val next = if (_activeId.value == id) rest.firstOrNull() else rest.firstOrNull { it.id == _activeId.value }
        storePrinters(rest, next?.id)
        if (next != null) applyActive(next) else applyActive(SavedPrinter("", "", ""))
    }

    private fun applyActive(p: SavedPrinter) {
        val changed = p.host != _settings.value.host
        _settings.value = _settings.value.copy(host = p.host, cameraMode = p.cameraMode, cameraUrl = p.cameraUrl, name = p.name)
        if (changed) {
            _timelapses.value = TimelapseUi()
            if (p.host.isBlank()) { client.disconnect(); creality.disconnect() }
            else { client.connect(p.host); creality.connect(p.host) }
        }
    }

    init {
        // первый запуск после обновления: одиночные настройки → список принтеров
        if (prefs.getString("printers", null) == null && _printers.value.isNotEmpty()) storePrinters(_printers.value, _activeId.value)
        Faults.init(app)
        if (_settings.value.host.isNotBlank()) {
            client.connect(_settings.value.host)
            creality.connect(_settings.value.host)
        }
    }

    // ---------- Таймлапсы ----------

    val timelapseRepo = TimelapseRepository()

    data class TimelapseUi(val items: List<Timelapse>? = null, val loading: Boolean = false, val error: String? = null)

    private val _timelapses = MutableStateFlow(TimelapseUi())
    val timelapses: StateFlow<TimelapseUi> = _timelapses.asStateFlow()

    fun loadTimelapses() {
        val host = _settings.value.hostOnly()
        if (host.isBlank() || _timelapses.value.loading) return
        _timelapses.value = _timelapses.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _timelapses.value = try {
                TimelapseUi(items = timelapseRepo.list(host))
            } catch (e: Exception) {
                _timelapses.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun timelapseUrl(t: Timelapse) = timelapseRepo.videoUrl(_settings.value.hostOnly(), t)

    /** Скачивание в «Фильмы/Filum Timelapse» через системный DownloadManager (с уведомлением). */
    fun downloadTimelapse(t: Timelapse) {
        val app = getApplication<Application>()
        val safeName = t.gcodeName.substringBeforeLast('.').replace(Regex("[^\\w.\\-]+"), "_")
        val date = t.dateTime.replace(Regex("[^0-9]"), "").take(12)
        val req = DownloadManager.Request(Uri.parse(timelapseUrl(t)))
            .setTitle(tr(R.string.timelapse_download_title, t.gcodeName))
            .setMimeType("video/mp4")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "Filum Timelapse/${safeName}_$date.mp4")
        try {
            (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(app, tr(R.string.timelapse_downloading), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(app, tr(R.string.download_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /** Сохраняет настройки; [asNew] — добавить как новый принтер и сделать его активным. */
    fun saveSettings(s: AppSettings, asNew: Boolean = false) {
        val list = _printers.value
        val active = _activeId.value?.takeIf { id -> !asNew && list.any { it.id == id } }
        val entry = SavedPrinter(active ?: java.util.UUID.randomUUID().toString(), s.name.trim(), s.host, s.cameraMode, s.cameraUrl)
        storePrinters(if (active != null) list.map { if (it.id == active) entry else it } else list + entry, entry.id)
        val hostChanged = s.host != _settings.value.host
        prefs.edit()
            .putString("slicerUrl", s.slicerUrl)
            .putBoolean("notifications", s.notifications)
            .commit()  // сразу на диск: сервис уведомлений читает адрес отсюда
        if (s.host != _settings.value.host) _timelapses.value = TimelapseUi()
        _settings.value = s
        if (hostChanged || client.connection.value == ConnState.Disconnected) {
            if (s.host.isBlank()) client.disconnect() else client.connect(s.host)
        }
        if (s.host.isBlank()) creality.disconnect() else creality.connect(s.host)
    }

    fun reconnect() {
        val h = _settings.value.host
        if (h.isNotBlank()) client.connect(h)
    }

    override fun onCleared() {
        client.disconnect()
    }
}
