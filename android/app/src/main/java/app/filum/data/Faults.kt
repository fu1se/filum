package app.filum.data

import androidx.annotation.StringRes

import app.filum.tr
import app.filum.R
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ошибка принтера с пояснением: что случилось и что делать. */
data class Fault(
    /** Код, как на экране принтера: 116, 2839, 101… */
    val code: Int,
    val title: String,
    val what: String?,
    val todo: String?,
    /** Уточнение из прошивки: бокс и слот CFS, текст ошибки Klipper и т. п. */
    val detail: String? = null,
)

/**
 * Ошибки K2 и их пояснения.
 *
 * Откуда берутся коды. Службы прошивки Creality (master-server) показывают текущую ошибку на экране
 * и отдают её в штатном WebSocket :9999 полем `err: {errcode, key, value}`. Источники:
 * - ошибки и предупреждения Klipper, в том числе закрытых модулей CFS (box_wrapper) и моторов, —
 *   ответ G-code `!! {"code":"key839",…}` или `## !{"code":"key358",…}`; код на экране = 2000 + key,
 *   кроме датчика филамента (key358 → 116);
 * - ИИ-камера, отключение питания, запуск печати с CFS — их Klipper не видит вовсе, только `err`.
 * Предупреждения ставят печать на обычную паузу: в Klipper это `paused` с пустым сообщением, поэтому
 * причину надо брать из `err` (или из ответа G-code в момент паузы).
 */
object Faults {

    /** Идентификаторы строк, а не текст: язык может смениться на ходу. */
    private class Info(@StringRes val title: Int, @StringRes val what: Int? = null, @StringRes val todo: Int? = null)

    private val cfsCheckPath = R.string.fault_cfs_check_path

    private val table: Map<Int, Info> = mapOf(
        // ---- ИИ-камера и служебные (master-server) ----
        101 to Info(R.string.fault_101_title,
            R.string.fault_101_what,
            R.string.fault_101_todo),
        103 to Info(R.string.fault_103_title,
            R.string.fault_103_what,
            R.string.fault_103_todo),
        104 to Info(R.string.fault_104_title,
            R.string.fault_104_what,
            R.string.fault_104_todo),
        115 to Info(R.string.fault_115_title,
            R.string.fault_115_what,
            R.string.fault_115_todo),
        116 to Info(R.string.fault_116_title,
            R.string.fault_116_what,
            R.string.fault_116_todo),
        122 to Info(R.string.fault_122_title,
            R.string.fault_122_what,
            R.string.fault_122_todo),
        528 to Info(R.string.fault_528_title,
            R.string.fault_528_what,
            cfsCheckPath),

        // ---- Klipper: печать и движение (2000 + key) ----
        2005 to Info(R.string.fault_2005_title, R.string.fault_2005_what, R.string.fault_restart_klipper_retry),
        2022 to Info(R.string.fault_2022_title, R.string.fault_2022_what,
            R.string.fault_2022_todo),
        2060 to Info(R.string.fault_2060_title, R.string.fault_2060_what, R.string.fault_restart_klipper),
        2096 to Info(R.string.fault_2096_title, R.string.fault_2096_what, R.string.fault_2096_todo),
        2111 to Info(R.string.fault_2111_title, R.string.fault_2111_what, R.string.fault_2111_todo),
        2112 to Info(R.string.fault_2112_title, R.string.fault_2112_what, R.string.fault_2112_todo),
        2353 to Info(R.string.fault_mcu_title, R.string.fault_mcu_what,
            R.string.fault_2353_todo),
        2355 to Info(R.string.fault_mcu_title, R.string.fault_mcu_what,
            R.string.fault_2355_todo),
        2509 to Info(R.string.fault_2509_title, R.string.fault_2509_what,
            R.string.fault_2509_todo),
        2564 to Info(R.string.fault_2564_title, R.string.fault_2564_what,
            R.string.fault_2564_todo),
        2585 to Info(R.string.fault_2585_title, R.string.fault_2585_what, R.string.fault_2585_todo),
        2784 to Info(R.string.fault_2784_title, R.string.fault_2784_what, R.string.fault_2784_todo),
        2789 to Info(R.string.fault_2789_title, R.string.fault_2789_what, R.string.fault_2789_todo),
        2796 to Info(R.string.fault_2796_title, null, R.string.fault_2796_todo),
        2798 to Info(R.string.fault_2798_title, R.string.fault_2798_what, R.string.fault_2798_todo),

        // ---- CFS (box_wrapper: 2000 + key8xx) ----
        2831 to Info(R.string.fault_2831_title, R.string.fault_2831_what, R.string.fault_2831_todo),
        2834 to Info(R.string.fault_2834_title, null, R.string.fault_restart_printer),
        2835 to Info(R.string.fault_2835_title, R.string.fault_2835_what, cfsCheckPath),
        2836 to Info(R.string.fault_2836_title, R.string.fault_2836_what, cfsCheckPath),
        2837 to Info(R.string.fault_2837_title, R.string.fault_2837_what, R.string.fault_2837_todo),
        2838 to Info(R.string.fault_2838_title, R.string.fault_2838_what, cfsCheckPath),
        2839 to Info(R.string.fault_2839_title, R.string.fault_2839_what, R.string.fault_2839_todo),
        2840 to Info(R.string.fault_2840_title, null, R.string.fault_2840_todo),
        2841 to Info(R.string.fault_2841_title, R.string.fault_2841_what, R.string.fault_2841_todo),
        2843 to Info(R.string.fault_2843_title, R.string.fault_2843_what, R.string.fault_2843_todo),
        2844 to Info(R.string.fault_2844_title, R.string.fault_2844_what, R.string.fault_2844_todo),
        2845 to Info(R.string.fault_2845_title, R.string.fault_2845_what, R.string.fault_2845_todo),
        2846 to Info(R.string.fault_2846_title, R.string.fault_2846_what, R.string.fault_2846_todo),
        2847 to Info(R.string.fault_2847_title, R.string.fault_2847_what, R.string.fault_2847_todo),
        2848 to Info(R.string.fault_2848_title, null, cfsCheckPath),
        2849 to Info(R.string.fault_2849_title, R.string.fault_2849_what, R.string.fault_2849_todo),
        2850 to Info(R.string.fault_unload_title, R.string.fault_2850_what, R.string.fault_2850_todo),
        2851 to Info(R.string.fault_unload_title, R.string.fault_2851_what, cfsCheckPath),
        2852 to Info(R.string.fault_2852_title, R.string.fault_2852_what, R.string.fault_2852_todo),
        2853 to Info(R.string.fault_2853_title, null, R.string.fault_restart_or_support),
        2854 to Info(R.string.fault_2854_title, R.string.fault_2854_what, R.string.fault_2854_todo),
        2855 to Info(R.string.fault_2855_title, null, R.string.fault_2855_todo),
        2856 to Info(R.string.fault_2856_title, null, R.string.fault_2856_todo),
        2857 to Info(R.string.fault_2857_title, null, R.string.fault_2857_todo),
        2858 to Info(R.string.fault_2858_title, null, R.string.fault_restart_printer),
        2859 to Info(R.string.fault_2859_title, R.string.fault_2859_what, R.string.fault_2859_todo),
        2860 to Info(R.string.fault_2860_title, null, cfsCheckPath),
        2861 to Info(R.string.fault_2861_title, null, null),
        2862 to Info(R.string.fault_2862_title, null, null),
        2863 to Info(R.string.fault_2863_title, R.string.fault_2863_what, R.string.fault_2863_todo),
        2864 to Info(R.string.fault_2864_title, null, cfsCheckPath),
        2865 to Info(R.string.fault_2849_title, null, R.string.fault_2865_todo),
        2870 to Info(R.string.fault_2870_title, null, R.string.fault_2870_todo),
        2871 to Info(R.string.fault_2871_title, null, R.string.fault_2871_todo),
        2872 to Info(R.string.fault_2872_title, null, null),
        2873 to Info(R.string.fault_2873_title, null, null),
        2874 to Info(R.string.fault_2874_title, null, null),
        2875 to Info(R.string.fault_2875_title, null, R.string.fault_2875_todo),

        3000 to Info(R.string.fault_3000_title, R.string.fault_3000_what, R.string.fault_restart_printer),
        3002 to Info(R.string.fault_3002_title, R.string.fault_3002_what, R.string.fault_3002_todo),
    )

    /** Пропускаемые коды: служебные и не-ошибки. */
    private val ignored = setOf(0, 2016)

    @Volatile private var keys: Map<Int, String> = emptyMap()

    /** Каталог текстов прошивки (assets/firmware_keys.json, собран из исходников K2). */
    fun init(ctx: Context) {
        if (keys.isNotEmpty()) return
        keys = runCatching {
            val o = JSONObject(ctx.assets.open("firmware_keys.json").bufferedReader().readText())
            o.keys().asSequence().associate { it.toInt() to o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    /** Код экрана по ключу Klipper. */
    fun codeForKey(key: Int): Int = if (key == 358) 116 else 2000 + key

    fun describe(code: Int, value: String? = null, firmwareMsg: String? = null): Fault? {
        if (code in ignored) return null
        val info = table[code]
        val raw = firmwareMsg ?: if (code >= 2000) keys[code - 2000] else null
        return Fault(
            code = code,
            title = info?.title?.let { tr(it) } ?: raw?.replaceFirstChar { it.uppercase() } ?: tr(R.string.fault_generic, code),
            what = info?.what?.let { tr(it) },
            todo = info?.todo?.let { tr(it) },
            detail = slotDetail(value),
        )
    }

    /** values `[1, "B"]` → «CFS 1, слот B». */
    private fun slotDetail(value: String?): String? {
        val a = runCatching { JSONArray(value ?: return null) }.getOrNull() ?: return null
        return if (a.length() >= 2 && a.opt(0) is Number && a.optString(1).length == 1) tr(R.string.fault_cfs_slot, a.optInt(0), a.optString(1)) else null
    }

    /**
     * Ответ G-code → ошибка: `!! {"code":"key839",…,"values":[1,"B"]}` или `## !{"code":"key358",…}`.
     * Обычные ошибки Klipper без ключа (`!! Move out of range`) — с текстом как есть.
     */
    fun parseResponse(line: String): Fault? {
        Regex("""\{.*"code"\s*:\s*"key(\d+)".*\}""").find(line)?.let { m ->
            val o = runCatching { JSONObject(m.value) }.getOrNull() ?: return null
            val key = m.groupValues[1].toInt()
            if (key in setOf(115, 16, 211)) return null  // power loss при старте, «не на паузе», «уже на паузе»
            val values = o.optJSONArray("values")?.toString()
            return describe(codeForKey(key), values, o.optString("msg").ifBlank { null })
        }
        if (line.startsWith("!!")) {
            val msg = line.removePrefix("!!").trim()
            if (msg.isBlank()) return null
            return Fault(0, tr(R.string.status_klipper_error), null, null, msg)
        }
        return null
    }
}
