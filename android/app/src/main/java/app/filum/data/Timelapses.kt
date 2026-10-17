package app.filum.data

import app.filum.tr
import app.filum.R
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Timelapse(
    val id: Long,
    val gcodePath: String,
    val dateTime: String,
    val sizeBytes: Long,
    val durationSec: Int,
    val printTimeSec: Long,
    val videoName: String,
) {
    val gcodeName get() = gcodePath.substringAfterLast('/')
}

/**
 * Таймлапсы K2 пишет прошивка Creality (не Moonraker):
 * список — запрос reqElapseVideoList по штатному WebSocket :9999,
 * видео — HTTP :80 /downloads/video/<имя>.mp4,
 * превью — миниатюра исходного G-code из Moonraker.
 */
class TimelapseRepository {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missingThumbs = HashSet<String>()

    suspend fun list(host: String): List<Timelapse> = withTimeout(10_000) {
        val raw = suspendCancellableCoroutine<JSONArray> { cont ->
            val ws = http.newWebSocket(
                Request.Builder().url("ws://$host:9999").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            JSONObject().put("method", "get")
                                .put("params", JSONObject().put("reqElapseVideoList", 1)).toString()
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("heart_beat")) { webSocket.send("ok"); return }
                        if (!text.contains("elapseVideoList")) return
                        val arr = runCatching { JSONObject(text).optJSONArray("elapseVideoList") }.getOrNull() ?: return
                        webSocket.close(1000, null)
                        if (cont.isActive) cont.resume(arr)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                },
            )
            cont.invokeOnCancellation { ws.cancel() }
        }
        (0 until raw.length()).mapNotNull { i ->
            val o = raw.optJSONObject(i) ?: return@mapNotNull null
            val video = o.optString("videoname").ifBlank { o.optString("video").substringAfterLast('/') }
            if (video.isBlank()) return@mapNotNull null
            Timelapse(
                id = o.optLong("id"),
                gcodePath = o.optString("name").ifBlank { o.optString("gcodename") },
                dateTime = o.optString("dateTime"),
                sizeBytes = o.optLong("size"),
                durationSec = o.optInt("duration"),
                printTimeSec = o.optLong("printtime"),
                videoName = video,
            )
        }.sortedByDescending { it.id }
    }

    fun videoUrl(host: String, t: Timelapse) = "http://$host/downloads/video/${enc(t.videoName)}"

    /** Миниатюра лежит рядом с G-code: <папка>/.thumbs/<имя>-300x300.png */
    private fun thumbUrl(host: String, t: Timelapse): String {
        val rel = t.gcodePath.substringAfter("/gcodes/", t.gcodePath.substringAfterLast('/'))
        val dir = rel.substringBeforeLast('/', "")
        val base = rel.substringAfterLast('/').substringBeforeLast('.')
        val path = (if (dir.isEmpty()) "" else "$dir/") + ".thumbs/$base-300x300.png"
        return "http://$host:${PrinterClient.MOONRAKER_PORT}/server/files/gcodes/" +
            path.split('/').joinToString("/") { enc(it) }
    }

    suspend fun thumbnail(host: String, t: Timelapse): Bitmap? {
        val url = thumbUrl(host, t)
        thumbs.get(url)?.let { return it }
        if (synchronized(missingThumbs) { url in missingThumbs }) return null
        return withContext(Dispatchers.IO) {
            val bmp = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
                }
            }.getOrNull()
            if (bmp != null) thumbs.put(url, bmp) else synchronized(missingThumbs) { missingThumbs += url }
            bmp
        }
    }

    /**
     * Веб-сервер Creality объявляет Accept-Ranges, но всегда отдаёт файл целиком,
     * а индекс MP4 (moov) лежит в конце — потоковое воспроизведение по HTTP не работает.
     * Поэтому сначала скачиваем видео в кэш приложения и играем локальный файл.
     */
    suspend fun cachedVideo(cacheDir: File, host: String, t: Timelapse, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "timelapse").apply { mkdirs() }
            val file = File(dir, t.videoName)
            if (file.exists() && (t.sizeBytes <= 0 || file.length() == t.sizeBytes)) {
                file.setLastModified(System.currentTimeMillis())
                return@withContext file
            }
            val tmp = File(dir, t.videoName + ".part")
            http.newCall(Request.Builder().url(videoUrl(host, t)).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body ?: error(tr(R.string.empty_response))
                val total = body.contentLength().takeIf { it > 0 } ?: t.sizeBytes
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            tmp.renameTo(file)
            trimCache(dir, keep = file)
            file
        }

    /** Держим кэш не больше ~300 МБ, удаляя самые давно открытые ролики. */
    private fun trimCache(dir: File, keep: File, limit: Long = 300L * 1024 * 1024) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
