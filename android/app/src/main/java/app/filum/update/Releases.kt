package app.filum.update

import org.json.JSONObject
import java.security.MessageDigest

/** Репозиторий Filum: релизы приложения и каталог проверенных плагинов. */
object FilumRepo {
    const val OWNER_REPO = "fu1se/filum"
    const val LATEST_RELEASE = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    const val PLUGIN_CATALOG = "https://raw.githubusercontent.com/$OWNER_REPO/main/plugins/catalog.json"
}

/** Сравнение версий вида «0.2.1», «v1.4», «1.10» (числа по точкам; лишние хвосты вроде «-beta» игнорируются). */
object Versions {
    fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split('.', '-', '+').map { p -> p.takeWhile { it.isDigit() } }
            .takeWhile { it.isNotEmpty() }.map { it.toInt() }

    /** <0 — [a] старее [b], 0 — одинаковые, >0 — [a] новее. */
    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(candidate: String, current: String) = compare(candidate, current) > 0
}

/** Релиз приложения на GitHub. */
data class AppRelease(val version: String, val tag: String, val notes: String, val apkUrl: String, val apkSize: Long, val page: String) {
    companion object {
        /** Ответ `GET /repos/{owner}/{repo}/releases/latest`; null — в релизе нет APK. */
        fun fromGitHub(o: JSONObject): AppRelease? {
            val tag = o.optString("tag_name").ifBlank { return null }
            val assets = o.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
            return AppRelease(
                version = tag.removePrefix("v"), tag = tag,
                notes = o.optString("body").trim(),
                apkUrl = apk.optString("browser_download_url"),
                apkSize = apk.optLong("size"),
                page = o.optString("html_url"),
            )
        }
    }
}

/**
 * Проверенный плагин из каталога (`plugins/catalog.json` в репозитории Filum). Ссылка указывает на конкретный коммит,
 * а [sha256] — на конкретное содержимое: скачанный файл с другой суммой не устанавливается.
 */
data class CatalogPlugin(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val url: String,
    val sha256: String,
    val homepage: String,
    val grants: List<String>,
) {
    companion object {
        fun parseCatalog(text: String): List<CatalogPlugin> {
            val a = JSONObject(text).optJSONArray("plugins") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                val sha = o.optString("sha256").lowercase()
                if (o.optString("id").isBlank() || !url.startsWith("https://") || !Regex("^[0-9a-f]{64}$").matches(sha)) return@mapNotNull null
                CatalogPlugin(
                    id = o.optString("id"), name = o.optString("name").ifBlank { o.optString("id") },
                    description = o.optString("description"), author = o.optString("author"),
                    version = o.optString("version"), url = url, sha256 = sha,
                    homepage = o.optString("homepage"),
                    grants = o.optJSONArray("grants")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty(),
                )
            }
        }
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
