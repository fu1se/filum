package app.filum.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.filum.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Обновление приложения из релизов GitHub: проверка, скачивание APK, системный установщик. */
object AppUpdater {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    val current: String get() = BuildConfig.VERSION_NAME

    private fun request(url: String) = Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Filum/${BuildConfig.VERSION_NAME}")
        .build()

    /** Последний релиз (или null, если в нём нет APK). */
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        http.newCall(request(FilumRepo.LATEST_RELEASE)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub: HTTP ${r.code}")
            AppRelease.fromGitHub(JSONObject(r.body!!.string()))
        }
    }

    /** Скачивает APK в cache/updates; [onProgress] — 0..1 (или −1, если размер неизвестен). */
    suspend fun download(ctx: Context, release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "filum-${release.version}.apk")
        http.newCall(Request.Builder().url(release.apkUrl).header("User-Agent", "Filum/${BuildConfig.VERSION_NAME}").build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
            }
        out
    }

    /**
     * Подписан ли скачанный APK тем же ключом, что и установленное приложение. Если нет, Android не установит его
     * поверх (так бывает при переходе со сборок, подписанных другим ключом). null — проверить не удалось.
     */
    fun signatureMatches(ctx: Context, apk: File): Boolean? = runCatching {
        val pm = ctx.packageManager
        val archive = archiveInfo(pm, apk) ?: return null
        val installed = installedInfo(pm, ctx.packageName)
        val a = certs(archive) ?: return null
        val b = certs(installed) ?: return null
        a.isNotEmpty() && a == b
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun certs(info: PackageInfo): Set<String>? {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs?.map { sha256Hex(it.toByteArray()) }?.toSet()
    }

    /** Разрешено ли приложению ставить APK (Android 8+: «Установка неизвестных приложений» для Filum). */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Открывает системный установщик для [apk]. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
