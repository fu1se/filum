package app.filum

import app.filum.data.PresetItem
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.filum.data.CfsRepository
import app.filum.data.CfsSlot
import app.filum.data.MaterialDef
import app.filum.data.MeshData
import app.filum.data.MwDesign
import app.filum.data.MwHit
import app.filum.data.Presets
import app.filum.data.SliceJob
import app.filum.data.SlicerApi
import app.filum.data.SlicerModel
import app.filum.data.ToolpathData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import app.filum.plugins.Plugins
import org.json.JSONArray

data class SlicerUi(
    val presets: Presets? = null,
    val presetsError: String? = null,
    val printer: String? = null,
    val process: String? = null,
    /** Пластик по слотам модели (для STL — один). */
    val filaments: List<String> = emptyList(),
    val model: SlicerModel? = null,
    val plate: Int = 0,
    val arrange: Boolean = true,
    val recent: List<SlicerModel> = emptyList(),
    val uploading: Float? = null,
    val job: SliceJob? = null,
    val history: List<SliceJob> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    /** Катушки CFS (null — ещё не загружены). */
    val cfs: List<CfsSlot>? = null,
    val cfsError: String? = null,
    /** Катушка для каждого цвета модели (id слота CFS или null). */
    val cfsAssign: List<String?> = emptyList(),
    /** Библиотека пластиков принтера — для правки слотов. */
    val library: List<MaterialDef>? = null,
    val cfsSaving: Boolean = false,
    /** Катушка на внешнем держателе. */
    val rack: CfsSlot? = null,
    /** Диаметр сопла, заданный на принтере. */
    val nozzle: Double? = null,
) {
    /** Пресет принтера нарезан под другое сопло, чем стоит на принтере. */
    val nozzleMismatch: Double? get() {
        val want = nozzle ?: return null
        val have = printer?.let(::presetNozzle) ?: return null
        return if (kotlin.math.abs(want - have) > 0.01) have else null
    }
}

/** «Creality K1 Max 0.4 nozzle» подходит к «K1 Max», но не к «K1»: после модели сразу диаметр сопла. */
fun presetMatchesModel(preset: String, model: String): Boolean =
    Regex("""(^|\s)${Regex.escape(model)}\s+\d+(?:\.\d+)?\s*nozzle""", RegexOption.IGNORE_CASE).containsMatchIn(preset)

/** «Creality K2 Plus 0.6 nozzle - Копировать» → 0.6 */
fun presetNozzle(name: String): Double? =
    Regex("""(\d+(?:\.\d+)?)\s*nozzle""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toDoubleOrNull()

data class MakerWorldUi(
    val loggedIn: Boolean = false,
    val account: String? = null,
    val needCode: Boolean = false,
    val query: String = "",
    val total: Int = 0,
    val hits: List<MwHit> = emptyList(),
    val design: MwDesign? = null,
    val selectedInstance: Long? = null,
    val loading: Boolean = false,
    val importing: Boolean = false,
    val error: String? = null,
)

class SlicerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("slicer", Context.MODE_PRIVATE)
    private val settingsPrefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val api = SlicerApi { settingsPrefs.getString("slicerUrl", null)?.ifBlank { null } ?: DEFAULT_SLICER_URL }

    private val _ui = MutableStateFlow(SlicerUi(
        printer = prefs.getString("printer", null),
        arrange = prefs.getBoolean("arrange", true),
    ))
    val ui: StateFlow<SlicerUi> = _ui.asStateFlow()

    private val _mw = MutableStateFlow(MakerWorldUi())
    val mw: StateFlow<MakerWorldUi> = _mw.asStateFlow()

    /** Запрос переключиться на вкладку слайсера (после «Поделиться» из другого приложения). */
    val openSlicerTab = MutableStateFlow(false)

    private var pollJob: Job? = null
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        loadPresets(_ui.value.printer)
        loadCfs()
        refreshLists()
    }

    fun reload() {
        loaded = false
        ensureLoaded()
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    private fun fail(e: Throwable) = _ui.update { it.copy(message = e.message ?: e.javaClass.simpleName, busy = false) }

    // ---------- пресеты ----------

    /** Диаметр сопла с принтера: слайсер переходит на пресет принтера под это сопло. */
    fun setNozzle(d: Double?) {
        if (d == null || d == _ui.value.nozzle) return
        _ui.update { it.copy(nozzle = d) }
        matchNozzle()
    }

    /** Модель принтера по данным служб Creality («K2 Plus», «K1C»…): пресет принтера подбирается под неё. */
    private var printerModel: String? = null

    fun setPrinterModel(name: String?) {
        if (name.isNullOrBlank() || name == printerModel) return
        printerModel = name
        matchNozzle()
    }

    /** Пресет принтера под модель и сопло: «Creality K2 Plus 0.4 nozzle», ваши ★ — первыми. */
    private fun matchNozzle() {
        val u = _ui.value
        val d = u.nozzle
        val model = printerModel
        fun nozzleOk(n: String) = d == null || presetNozzle(n)?.let { kotlin.math.abs(it - d) < 0.01 } == true
        fun modelOk(n: String) = model == null || presetMatchesModel(n, model)
        val cur = u.printer
        if (cur != null && nozzleOk(cur) && modelOk(cur)) return
        if (d == null && model == null) return
        val pick = u.presets?.printers
            ?.filter { nozzleOk(it.name) && modelOk(it.name) }
            ?.sortedWith(compareBy({ !it.user }, { presetNozzle(it.name)?.let { n -> kotlin.math.abs(n - 0.4) } ?: 9.0 }))
            ?.firstOrNull() ?: return
        if (pick.name != cur) loadPresets(pick.name)
    }

    fun loadPresets(printer: String?, refreshSync: Boolean = false, auto: Boolean = true) = viewModelScope.launch {
        try {
            if (refreshSync) api.refreshPresets()
            val p = api.presets(printer)
            val pr = p.printer ?: return@launch
            prefs.edit().putString("printer", pr).apply()
            fun pick(saved: String?, def: String?, items: List<PresetItem>) =
                saved?.takeIf { s -> items.any { it.name == s } } ?: def ?: items.firstOrNull()?.name
            val process = pick(prefs.getString("process:$pr", null), p.defProcess, p.processes)
            val savedFils = prefs.getString("filaments:$pr", null)
                ?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.orEmpty()
            val firstFil = pick(savedFils.firstOrNull(), p.defFilament, p.filaments)
            _ui.update { u ->
                u.copy(presets = p, presetsError = null, printer = pr, process = process,
                    filaments = fitFilaments(savedFils.filter { s -> p.filaments.any { it.name == s } }
                        .ifEmpty { listOfNotNull(firstFil) }, u.model))
            }
            _ui.update { u -> u.copy(cfs = u.cfs?.map { c -> c.copy(preset = suggestPreset(c)) }) }
            autoAssign(keepManual = true)
            if (auto) matchNozzle()
        } catch (e: Exception) {
            _ui.update { it.copy(presetsError = e.message ?: tr(R.string.slicer_unavailable)) }
        }
    }

    private fun fitFilaments(list: List<String>, model: SlicerModel?): List<String> {
        val slots = model?.slots?.size ?: 1
        if (list.isEmpty()) return list
        return List(slots) { i -> list.getOrElse(i) { list.last() } }
    }

    fun selectPrinter(name: String) = loadPresets(name, auto = false)

    // ---------- CFS ----------

    private val cfsRepo = CfsRepository()
    private fun printerHost() = settingsPrefs.getString("host", "").orEmpty().substringBefore(':').trim()

    /** CFS читается прямо с принтера; сервер слайсера — запасной путь. */
    fun loadCfs() = viewModelScope.launch {
        try {
            val host = printerHost()
            val slots = try {
                require(host.isNotEmpty()) { tr(R.string.printer_address_missing) }
                cfsRepo.slots(host)
            } catch (e: Exception) {
                runCatching { api.cfs(_ui.value.printer, host) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: throw e
            }
            _ui.update { it.copy(cfs = slots.filter { s -> s.box > 0 }.map { s -> s.copy(preset = s.preset ?: suggestPreset(s)) },
                rack = slots.firstOrNull { s -> s.box == 0 }, cfsError = null) }
            autoAssign(keepManual = true)
        } catch (e: Exception) {
            _ui.update { it.copy(cfs = emptyList(), cfsError = e.message ?: tr(R.string.cfs_unavailable)) }
        }
    }

    /** Библиотека перечитывается при каждом открытии редактора: material-sync мог добавить пластики. */
    fun loadLibrary() = viewModelScope.launch {
        try {
            val lib = cfsRepo.library(printerHost())
            _ui.update { it.copy(library = lib) }
        } catch (e: Exception) {
            _ui.update { it.copy(message = tr(R.string.material_library_error, e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** Записывает пластик в слот CFS и перечитывает боксы. */
    fun saveSlot(slot: CfsSlot, m: MaterialDef, color: String, minTemp: Int, maxTemp: Int, pressure: Double, onDone: () -> Unit) {
        _ui.update { it.copy(cfsSaving = true) }
        viewModelScope.launch {
            try {
                cfsRepo.modify(printerHost(), slot, m, color, minTemp, maxTemp, pressure)
                val fresh = cfsRepo.slots(printerHost()).map { s -> s.copy(preset = suggestPreset(s)) }
                _ui.update { it.copy(cfs = fresh.filter { s -> s.box > 0 }, rack = fresh.firstOrNull { s -> s.box == 0 },
                    cfsSaving = false, cfsError = null) }
                val got = fresh.firstOrNull { it.id == slot.id }
                val applied = got != null && got.name == m.name && got.color.equals(color, ignoreCase = true) &&
                    got.minTemp == minTemp && got.maxTemp == maxTemp
                _ui.update { it.copy(message = if (applied) tr(R.string.slot_saved, if (slot.box == 0) tr(R.string.holder) else slot.id.drop(1), m.name) else tr(R.string.slot_not_confirmed)) }
                // пресет назначенного цвета модели — под новый пластик
                _ui.value.cfsAssign.forEachIndexed { i, id -> if (id == slot.id) got?.preset?.let { applyPresetFromSpool(i, it) } }
                onDone()
            } catch (e: Exception) {
                _ui.update { it.copy(cfsSaving = false) }
                fail(e)
            }
        }
    }

    /** Пресет пластика под катушку: по названию (★ ваши впереди), затем по типу. */
    private fun suggestPreset(s: CfsSlot): String? {
        if (s.empty) return null
        val items = _ui.value.presets?.filaments ?: return null
        fun norm(v: String?) = v.orEmpty().lowercase().replace(Regex("[^a-z0-9а-я]+"), "")
        val name = norm(s.name)
        val brand = norm(s.brand)
        val type = norm(s.type)
        var best: String? = null
        var bestScore = -1
        for (p in items) {
            val n = norm(p.name.substringBefore('@'))
            var score = 0
            if (name.isNotEmpty() && name == n) score += 10   // «НИТ PETG» → «НИТ PETG @…»
            if (name.isNotEmpty() && name in n) {
                score += 6
                if (brand.isNotEmpty() && brand != "generic" && brand in n) score += 2
            }
            if (type.isNotEmpty() && Regex("(^|[^a-z])${Regex.escape(type)}([^a-z]|$)").containsMatchIn(p.name.lowercase().replace("-", ""))) score += 3
            if ("generic" in n && name.isEmpty()) score += 1
            if (p.user) score += 1
            if (score > bestScore) { best = p.name; bestScore = score }
        }
        return best.takeIf { bestScore >= 3 }
    }

    /** Назначает катушки цветам модели: ближайший цвет того же типа, без повторов, пока хватает катушек. */
    private fun autoAssign(keepManual: Boolean) {
        val u = _ui.value
        val spools = u.cfs?.filter { !it.empty }.orEmpty()
        val slots = u.model?.slots ?: listOf(null)
        if (spools.isEmpty()) {
            _ui.update { it.copy(cfsAssign = List(slots.size) { null }) }
            return
        }
        val used = mutableSetOf<String>()
        val result = MutableList<String?>(slots.size) { i ->
            u.cfsAssign.getOrNull(i)?.takeIf { id -> keepManual && spools.any { it.id == id } }?.also { used += it }
        }
        for (i in slots.indices) {
            if (result[i] != null) continue
            val want = slots[i]
            val preset = u.filaments.getOrNull(i)
            val best = spools.minByOrNull { s ->
                var score = colorDistance(want?.color, s.color)
                if (want?.type != null && s.type != null && !sameType(want.type, s.type)) score += 400.0
                if (want?.color == null && preset != null && s.preset == preset) score -= 300.0
                if (s.id in used) score += 1000.0
                score
            }
            result[i] = best?.id
            best?.let { used += it.id }
        }
        _ui.update { it.copy(cfsAssign = result) }
        // пресеты под назначенные катушки, если пользователь их ещё не трогал вручную
        result.forEachIndexed { i, id -> spools.firstOrNull { it.id == id }?.preset?.let { applyPresetFromSpool(i, it) } }
    }

    fun assignSpool(modelSlot: Int, spoolId: String?) {
        _ui.update { u ->
            val list = u.cfsAssign.toMutableList()
            while (list.size <= modelSlot) list += null
            list[modelSlot] = spoolId
            u.copy(cfsAssign = list)
        }
        _ui.value.cfs?.firstOrNull { it.id == spoolId }?.preset?.let { applyPresetFromSpool(modelSlot, it) }
    }

    private fun applyPresetFromSpool(slot: Int, preset: String) {
        if (_ui.value.presets?.filaments?.any { it.name == preset } != true) return
        if (slot < _ui.value.filaments.size) selectFilament(slot, preset)
    }

    private fun sameType(a: String, b: String): Boolean {
        fun n(s: String) = s.uppercase().replace("PET-G", "PETG").let { if (it == "PET") "PETG" else it }
        return n(a) == n(b) || n(a).startsWith(n(b)) || n(b).startsWith(n(a))
    }

    private fun colorDistance(a: String?, b: String?): Double {
        if (a == null || b == null) return 0.0
        fun rgb(h: String) = runCatching { h.removePrefix("#").take(6).toInt(16) }.getOrNull()
        val x = rgb(a) ?: return 0.0
        val y = rgb(b) ?: return 0.0
        val r1 = x shr 16 and 255; val g1 = x shr 8 and 255; val b1 = x and 255
        val r2 = y shr 16 and 255; val g2 = y shr 8 and 255; val b2 = y and 255
        val rm = (r1 + r2) / 2.0
        val dr = (r1 - r2).toDouble(); val dg = (g1 - g2).toDouble(); val db = (b1 - b2).toDouble()
        // «redmean» — простая перцептивная метрика
        return kotlin.math.sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db)
    }

    fun selectProcess(name: String) {
        _ui.update { it.copy(process = name) }
        _ui.value.printer?.let { prefs.edit().putString("process:$it", name).apply() }
    }

    fun selectFilament(slot: Int, name: String) {
        _ui.update { u -> u.copy(filaments = u.filaments.toMutableList().also { if (slot < it.size) it[slot] = name }) }
        _ui.value.printer?.let { prefs.edit().putString("filaments:$it", JSONArray(_ui.value.filaments).toString()).apply() }
    }

    fun setPlate(p: Int) = _ui.update { it.copy(plate = p) }

    fun setArrange(v: Boolean) {
        _ui.update { it.copy(arrange = v) }
        prefs.edit().putBoolean("arrange", v).apply()
    }

    // ---------- модели ----------

    fun selectModel(m: SlicerModel) {
        _ui.update {
            it.copy(model = m, plate = if (m.plates.size > 1) m.plates.first() else 0,
                filaments = fitFilaments(it.filaments, m), job = null, cfsAssign = emptyList())
        }
        autoAssign(keepManual = false)
    }

    fun importUri(uri: Uri) = viewModelScope.launch {
        _ui.update { it.copy(uploading = 0f) }
        try {
            val m = api.uploadModel(getApplication<Application>().contentResolver, uri) { p ->
                _ui.update { it.copy(uploading = p) }
            }
            _ui.update { it.copy(uploading = null) }
            selectModel(m)
            refreshLists()
        } catch (e: Exception) {
            _ui.update { it.copy(uploading = null) }
            fail(e)
        }
    }

    fun refreshLists() = viewModelScope.launch {
        try {
            val models = api.models()
            val jobs = api.jobs()
            _ui.update { it.copy(recent = models, history = jobs) }
        } catch (_: Exception) {
        }
    }

    suspend fun mesh(modelId: String): MeshData = api.mesh(modelId)
    suspend fun toolpaths(jobId: String, plate: Int): ToolpathData = api.toolpaths(jobId, plate)

    // ---------- нарезка ----------

    fun slice() {
        val u = _ui.value
        val m = u.model ?: return
        val printer = u.printer ?: return
        val process = u.process ?: return
        if (u.filaments.isEmpty()) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val j = api.slice(m.id, printer, process, u.filaments, u.plate, u.arrange,
                    cfs = List(u.filaments.size) { i -> u.cfsAssign.getOrNull(i) }, printerHost = printerHost(),
                    transform = { req -> suspendCoroutine { c -> Plugins.host.transformSlice(req) { c.resume(it) } } })
                _ui.update { it.copy(job = j, busy = false) }
                poll(j.id)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun openJob(id: String) = viewModelScope.launch {
        try {
            val j = api.job(id)
            _ui.update { it.copy(job = j) }
            if (j.busy) poll(id)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun poll(id: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(1500)
                val j = try { api.job(id) } catch (e: Exception) { continue }
                _ui.update { it.copy(job = j) }
                if (!j.busy) {
                    refreshLists()
                    break
                }
            }
        }
    }

    fun sendToPrinter(jobId: String, plate: Int, start: Boolean, onDone: () -> Unit = {}) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val j = if (Plugins.host.hasHook("gcode.file")) sendViaPlugins(jobId, plate, start)
                    else api.sendToPrinter(jobId, plate, start, printerHost())
                _ui.update { it.copy(job = if (it.job?.id == j.id) j else it.job, busy = false,
                    message = if (start) tr(R.string.print_started) else tr(R.string.gcode_uploaded)) }
                refreshLists()
                onDone()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * Хук плагинов gcode.file: G-code скачивается с сервера слайсера, плагины его меняют, и приложение само
     * загружает результат в Moonraker (сервер слайсера в этом случае файл на принтер не отправляет).
     */
    private suspend fun sendViaPlugins(jobId: String, plate: Int, start: Boolean): SliceJob {
        val job = api.job(jobId)
        val result = job.results.firstOrNull { it.plate == plate } ?: throw IllegalStateException("no plate $plate")
        val text = api.gcodeText(jobId, plate)
        val info = org.json.JSONObject().put("file", result.file).put("plate", plate).put("job", jobId)
            .put("model", job.modelName).put("process", job.process).put("start", start)
        val out = suspendCoroutine { c -> Plugins.host.transformGcodeFile(text, info) { c.resume(it) } }
        withContext(Dispatchers.IO) {
            val host = settingsPrefs.getString("host", "").orEmpty().trim()
            val base = "http://${if (host.contains(':')) host else "$host:7125"}"
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", result.file, out.toByteArray().toRequestBody("text/plain".toMediaType()))
                .addFormDataPart("print", if (start) "true" else "false")
                .build()
            okhttp3.OkHttpClient.Builder().writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(okhttp3.Request.Builder().url("$base/server/files/upload").post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("Moonraker upload: HTTP ${r.code}")
                }
        }
        return job
    }

    // ---------- MakerWorld ----------

    fun mwRefreshStatus() = viewModelScope.launch {
        try {
            val (ok, acc) = api.mwStatus()
            _mw.update { it.copy(loggedIn = ok, account = acc) }
        } catch (e: Exception) {
            _mw.update { it.copy(error = e.message) }
        }
    }

    fun mwLogin(account: String, password: String?, code: String?, token: String?) = viewModelScope.launch {
        _mw.update { it.copy(loading = true, error = null) }
        try {
            val ok = api.mwLogin(account, password, code, token)
            _mw.update { it.copy(loading = false, needCode = !ok, error = if (!ok) tr(R.string.bambu_code_sent) else null) }
            if (ok) mwRefreshStatus()
        } catch (e: Exception) {
            _mw.update { it.copy(loading = false, error = e.message) }
        }
    }

    fun mwLogout() = viewModelScope.launch {
        runCatching { api.mwLogout() }
        mwRefreshStatus()
    }

    fun mwSearch(query: String, more: Boolean = false) = viewModelScope.launch {
        val q = query.trim()
        if (q.isEmpty()) return@launch
        _mw.update { it.copy(query = q, loading = true, error = null, design = null) }
        try {
            if (q.contains("makerworld.com") || q.all { it.isDigit() }) {
                val (id, profile) = api.mwResolve(q)
                openDesign(id, profile)
                return@launch
            }
            val offset = if (more) _mw.value.hits.size else 0
            val (total, hits) = api.mwSearch(q, offset)
            _mw.update { it.copy(total = total, hits = if (more) it.hits + hits else hits, loading = false) }
        } catch (e: Exception) {
            _mw.update { it.copy(loading = false, error = e.message) }
        }
    }

    fun openDesign(id: Long, profileId: Long? = null) = viewModelScope.launch {
        _mw.update { it.copy(loading = true, error = null) }
        try {
            val d = api.mwDesign(id)
            val sel = d.instances.firstOrNull { profileId != null && it.profileId == profileId }?.id
                ?: d.defaultInstance ?: d.instances.firstOrNull()?.id
            _mw.update { it.copy(design = d, selectedInstance = sel, loading = false) }
        } catch (e: Exception) {
            _mw.update { it.copy(loading = false, error = e.message) }
        }
    }

    fun mwSelectInstance(id: Long) = _mw.update { it.copy(selectedInstance = id) }
    fun mwCloseDesign() = _mw.update { it.copy(design = null) }

    fun mwImport(onDone: () -> Unit) {
        val d = _mw.value.design ?: return
        val inst = _mw.value.selectedInstance ?: return
        _mw.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            try {
                val m = api.mwImport(d.id, inst).let { it.copy(title = it.title ?: d.title, cover = it.cover ?: d.cover) }
                _mw.update { it.copy(importing = false) }
                selectModel(m)
                refreshLists()
                onDone()
            } catch (e: Exception) {
                _mw.update { it.copy(importing = false, error = e.message) }
            }
        }
    }

    companion object {
        /** Адреса по умолчанию нет: сервер слайсера у каждого свой (см. slicer-server/README). */
        const val DEFAULT_SLICER_URL = ""
    }
}
