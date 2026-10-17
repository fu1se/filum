package app.filum.data

import app.filum.tr
import app.filum.R
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

// ---------- модели данных ----------

data class PresetItem(val name: String, val user: Boolean)

data class Presets(
    val printers: List<PresetItem>,
    val processes: List<PresetItem>,
    val filaments: List<PresetItem>,
    val printer: String?,
    val defProcess: String?,
    val defFilament: String?,
    val syncOk: Boolean,
    val syncCount: Int,
    val syncError: String?,
    val bedW: Float,
    val bedH: Float,
)

data class FilamentSlot(val slot: Int, val type: String?, val color: String?)

data class SlicerModel(
    val id: String,
    val name: String,
    val title: String?,
    val source: String,
    val size: Long,
    val created: Double,
    val plates: List<Int>,
    val slots: List<FilamentSlot>,
    val hasThumb: Boolean,
    val cover: String?,
    val author: String?,
) {
    val displayName get() = title ?: name
}

data class SliceResult(
    val plate: Int,
    val file: String,
    val size: Long,
    val time: String?,
    val grams: String?,
    val layers: String?,
    val uploaded: Boolean,
    val printing: Boolean,
    val uploadError: String?,
)

data class SliceJob(
    val id: String,
    val modelName: String,
    val created: Double,
    val process: String?,
    val state: String,
    val error: String?,
    val results: List<SliceResult>,
    val queuePosition: Int,
    val sliceSeconds: Double?,
    /** Цвета катушек CFS по инструментам T0, T1… (если назначены). */
    val cfsColors: List<String?> = emptyList(),
) {
    val busy get() = state == "queued" || state == "slicing"
}

/** Катушка в CFS: слот T1A…T4D. */
data class CfsSlot(
    val id: String,
    val box: Int,
    val letter: String,
    val empty: Boolean,
    val color: String?,
    val type: String?,
    val brand: String?,
    val name: String?,
    val remain: Int?,
    /** Пресет пластика, подобранный под эту катушку. */
    val preset: String?,
    val rfid: String? = null,
    val minTemp: Int? = null,
    val maxTemp: Int? = null,
    val pressure: Double? = null,
    val humidity: Double? = null,
    val boxTemp: Double? = null,
) {
    val title get() = name ?: type ?: tr(R.string.spool)
}

data class MwHit(val id: Long, val title: String, val cover: String?, val author: String?, val downloads: Int)

data class MwInstance(
    val id: Long,
    val profileId: Long,
    val title: String?,
    val printer: String?,
    val materials: Int,
    val plates: Int,
    val thumb: String?,
)

data class MwDesign(
    val id: Long,
    val title: String,
    val cover: String?,
    val author: String?,
    val defaultInstance: Long?,
    val instances: List<MwInstance>,
)

/** Сетка модели: треугольники без индексов, x,y,z × 3 вершины. */
class MeshData(val positions: FloatArray, val min: FloatArray, val max: FloatArray) {
    val triangles get() = positions.size / 9
}

/** Траектории: по 2 вершины (x,y,z) на отрезок, тип отрезка, границы слоёв. */
class ToolpathData(
    val positions: FloatArray,
    val types: ByteArray,
    val layerZ: FloatArray,
    val layerEnd: IntArray,
    val min: FloatArray,
    val max: FloatArray,
) {
    val segments get() = types.size
    val layers get() = layerZ.size
}

class SlicerException(message: String) : IOException(message)

/** REST-клиент облачного слайсера (slicer-server на TrueNAS). */
class SlicerApi(private val baseUrl: () -> String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun url(path: String): String {
        val base = baseUrl().trim()
        if (base.isEmpty()) throw IllegalStateException(tr(R.string.slicer_not_configured))
        return base.trimEnd('/') + path
    }

    private suspend fun call(req: Request): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val body = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(String(body)).optString("error") }.getOrNull()
                throw SlicerException(msg?.ifBlank { null } ?: "HTTP ${r.code}")
            }
            body
        }
    }

    private suspend fun getJson(path: String) = JSONObject(String(call(Request.Builder().url(url(path)).build())))

    private suspend fun postJson(path: String, body: JSONObject = JSONObject()) = JSONObject(String(call(
        Request.Builder().url(url(path))
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    )))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- пресеты ----------

    suspend fun presets(printer: String?): Presets {
        val o = getJson("/api/presets" + (printer?.let { "?printer=${enc(it)}" } ?: ""))
        fun items(key: String) = o.optJSONArray(key).objects().map { PresetItem(it.optString("name"), it.optBoolean("user")) }
        val d = o.optJSONObject("defaults") ?: JSONObject()
        val s = o.optJSONObject("sync") ?: JSONObject()
        val bed = o.optJSONArray("bed")
        return Presets(
            printers = items("printers"), processes = items("processes"), filaments = items("filaments"),
            printer = o.optStringOrNull("printer"), defProcess = d.optStringOrNull("process"),
            defFilament = d.optStringOrNull("filament"),
            syncOk = s.optBoolean("ok"), syncCount = s.optInt("count"), syncError = s.optStringOrNull("error"),
            bedW = bed?.optDouble(0)?.toFloat() ?: 350f, bedH = bed?.optDouble(1)?.toFloat() ?: 350f,
        )
    }

    suspend fun refreshPresets() { postJson("/api/presets/refresh") }

    // ---------- модели ----------

    suspend fun uploadModel(resolver: ContentResolver, uri: Uri, onProgress: (Float) -> Unit): SlicerModel {
        var name = "model.stl"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("stl", "3mf", "obj", "step", "stp")) {
            throw SlicerException(tr(R.string.unsupported_model, name))
        }
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IOException(tr(R.string.file_open_failed))
                input.source().use { src ->
                    var done = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        val n = src.read(buf, 64 * 1024)
                        if (n < 0) break
                        sink.write(buf, n)
                        done += n
                        if (size > 0) onProgress(done.toFloat() / size)
                    }
                }
            }
        }
        val o = JSONObject(String(call(Request.Builder().url(url("/api/models?name=${enc(name)}")).post(body).build())))
        return parseModel(o)
    }

    suspend fun models(): List<SlicerModel> = getJson("/api/models").optJSONArray("models").objects().map(::parseModel)

    fun modelThumbUrl(m: SlicerModel, plate: Int = 1): String? =
        if (m.hasThumb) url("/api/models/${m.id}/thumb?plate=$plate") else m.cover

    suspend fun mesh(modelId: String): MeshData = withContext(Dispatchers.Default) {
        val b = ByteBuffer.wrap(call(Request.Builder().url(url("/api/models/$modelId/mesh")).build()))
            .order(ByteOrder.LITTLE_ENDIAN)
        expectMagic(b, "K2M1")
        val n = b.int
        val min = FloatArray(3) { b.float }
        val max = FloatArray(3) { b.float }
        val pos = FloatArray(n * 9)
        b.asFloatBuffer().get(pos)
        MeshData(pos, min, max)
    }

    // ---------- нарезка ----------

    suspend fun slice(
        modelId: String, printer: String, process: String, filaments: List<String>,
        plate: Int, arrange: Boolean, cfs: List<String?>, printerHost: String,
    ): SliceJob = parseJob(postJson("/api/slice", JSONObject()
        .put("printer_host", printerHost)
        .put("model_id", modelId).put("printer", printer).put("process", process)
        .put("filaments", JSONArray(filaments)).put("plate", plate).put("arrange", arrange)
        .put("cfs", JSONArray(cfs.map { it ?: JSONObject.NULL }))
        .put("upload", false).put("start", false)))

    suspend fun cfs(printer: String?, printerHost: String): List<CfsSlot> =
        getJson("/api/cfs?host=${enc(printerHost)}" + (printer?.let { "&printer=${enc(it)}" } ?: "")).optJSONArray("slots").objects().map {
            CfsSlot(
                id = it.optString("id"), box = it.optInt("box"), letter = it.optString("letter"),
                empty = it.optBoolean("empty"), color = it.optStringOrNull("color"), type = it.optStringOrNull("type"),
                brand = it.optStringOrNull("brand"), name = it.optStringOrNull("name"),
                remain = if (it.isNull("remain")) null else it.optInt("remain"), preset = it.optStringOrNull("preset"),
            )
        }

    suspend fun job(id: String): SliceJob = parseJob(getJson("/api/jobs/$id"))

    suspend fun jobs(): List<SliceJob> = getJson("/api/jobs").optJSONArray("jobs").objects().map(::parseJob)

    /** Сервер слайсера сам загружает G-code в Moonraker принтера [printerHost]. */
    suspend fun sendToPrinter(jobId: String, plate: Int, start: Boolean, printerHost: String): SliceJob =
        parseJob(postJson("/api/jobs/$jobId/upload", JSONObject().put("plate", plate).put("start", start)
            .put("printer_host", printerHost)))

    fun jobThumbUrl(jobId: String, plate: Int) = url("/api/jobs/$jobId/thumb/$plate")

    suspend fun toolpaths(jobId: String, plate: Int): ToolpathData = withContext(Dispatchers.Default) {
        val b = ByteBuffer.wrap(call(Request.Builder().url(url("/api/jobs/$jobId/preview/$plate")).build()))
            .order(ByteOrder.LITTLE_ENDIAN)
        expectMagic(b, "K2G1")
        val nl = b.int
        val ns = b.int
        val min = FloatArray(3) { b.float }
        val max = FloatArray(3) { b.float }
        val layerZ = FloatArray(nl) { b.float }
        val layerEnd = IntArray(nl) { b.int }
        val segBytes = b.slice().order(ByteOrder.LITTLE_ENDIAN)
        val shorts = segBytes.asShortBuffer()
        val types = ByteArray(ns)
        b.position(b.position() + ns * 8)
        b.get(types)
        // Раскладываем в вершины: z берём из слоя отрезка
        val pos = FloatArray(ns * 6)
        var layer = 0
        val unit = 0.02f
        for (i in 0 until ns) {
            while (layer < nl - 1 && i >= layerEnd[layer]) layer++
            val z = if (nl > 0) layerZ[layer] else 0f
            val o = i * 6
            pos[o] = shorts.get(i * 4) * unit
            pos[o + 1] = shorts.get(i * 4 + 1) * unit
            pos[o + 2] = z
            pos[o + 3] = shorts.get(i * 4 + 2) * unit
            pos[o + 4] = shorts.get(i * 4 + 3) * unit
            pos[o + 5] = z
        }
        ToolpathData(pos, types, layerZ, layerEnd, min, max)
    }

    // ---------- MakerWorld ----------

    suspend fun mwStatus(): Pair<Boolean, String?> =
        getJson("/api/makerworld/status").let { it.optBoolean("logged_in") to it.optStringOrNull("account") }

    /** true — вход выполнен, false — Bambu отправил код на почту. */
    suspend fun mwLogin(account: String, password: String?, code: String?, token: String?): Boolean {
        val body = JSONObject().put("account", account)
        password?.let { body.put("password", it) }
        code?.let { body.put("code", it) }
        token?.let { body.put("token", it) }
        return !postJson("/api/makerworld/login", body).optBoolean("need_code")
    }

    suspend fun mwLogout() { postJson("/api/makerworld/logout") }

    suspend fun mwSearch(query: String, offset: Int): Pair<Int, List<MwHit>> {
        val o = getJson("/api/makerworld/search?q=${enc(query)}&offset=$offset")
        return o.optInt("total") to o.optJSONArray("hits").objects().map {
            MwHit(it.optLong("id"), it.optString("title"), it.optStringOrNull("cover"),
                it.optStringOrNull("author"), it.optInt("downloads"))
        }
    }

    suspend fun mwResolve(link: String): Pair<Long, Long?> {
        val o = getJson("/api/makerworld/resolve?url=${enc(link)}")
        return o.optLong("design_id") to (if (o.isNull("profile_id")) null else o.optLong("profile_id"))
    }

    suspend fun mwDesign(id: Long): MwDesign {
        val o = getJson("/api/makerworld/design/$id")
        return MwDesign(
            id = o.optLong("id"), title = o.optString("title"), cover = o.optStringOrNull("cover"),
            author = o.optStringOrNull("author"),
            defaultInstance = if (o.isNull("default_instance")) null else o.optLong("default_instance"),
            instances = o.optJSONArray("instances").objects().map {
                val plates = it.optJSONArray("plates").objects()
                MwInstance(
                    id = it.optLong("id"), profileId = it.optLong("profile_id"), title = it.optStringOrNull("title"),
                    printer = it.optStringOrNull("printer"), materials = it.optInt("materials", 1),
                    plates = plates.size.coerceAtLeast(1),
                    thumb = plates.firstOrNull()?.optStringOrNull("thumb") ?: it.optStringOrNull("cover"),
                )
            },
        )
    }

    suspend fun mwImport(designId: Long, instanceId: Long): SlicerModel =
        parseModel(postJson("/api/makerworld/import", JSONObject().put("design_id", designId).put("instance_id", instanceId)))

    // ---------- разбор ----------

    private fun parseModel(o: JSONObject): SlicerModel {
        val info = o.optJSONObject("info") ?: JSONObject()
        return SlicerModel(
            id = o.optString("id"), name = o.optString("name"), title = o.optStringOrNull("title"),
            source = o.optString("source"), size = o.optLong("size"), created = o.optDouble("created"),
            plates = info.optJSONArray("plates")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: listOf(1),
            slots = info.optJSONArray("filaments").objects().map {
                FilamentSlot(it.optInt("slot"), it.optStringOrNull("type"), it.optStringOrNull("color"))
            }.ifEmpty { listOf(FilamentSlot(1, null, null)) },
            hasThumb = info.optBoolean("has_thumb"),
            cover = o.optStringOrNull("cover"), author = o.optStringOrNull("author"),
        )
    }

    private fun parseJob(o: JSONObject) = SliceJob(
        id = o.optString("id"), modelName = o.optString("model_name"), created = o.optDouble("created"),
        process = o.optStringOrNull("process"), state = o.optString("state"), error = o.optStringOrNull("error"),
        results = o.optJSONArray("results").objects().map {
            SliceResult(
                plate = it.optInt("plate"), file = it.optString("file"), size = it.optLong("size"),
                time = it.optStringOrNull("time"), grams = it.optStringOrNull("grams"),
                layers = it.optStringOrNull("layers"), uploaded = it.optBoolean("uploaded"),
                printing = it.optBoolean("printing"), uploadError = it.optStringOrNull("upload_error"),
            )
        },
        queuePosition = o.optInt("queue_position"),
        cfsColors = o.optJSONArray("cfs_colors")?.let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.optString(it) } }.orEmpty(),
        sliceSeconds = if (o.has("slice_seconds") && !o.isNull("slice_seconds")) o.optDouble("slice_seconds") else null,
    )

    private fun expectMagic(b: ByteBuffer, magic: String) {
        val m = ByteArray(4).also { b.get(it) }
        if (String(m) != magic) throw SlicerException(tr(R.string.preview_bad_format))
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }
