package app.filum.data

import app.filum.tr
import app.filum.R
import org.json.JSONArray
import org.json.JSONObject

/** Снимок состояния принтера, собранный из объектов Klipper (подписка Moonraker). */
data class PrinterState(
    val klippyState: String? = null,
    val klippyMessage: String? = null,
    val nozzleTemp: Double? = null,
    val nozzleTarget: Double? = null,
    /** extruder.nozzle_diameter — диаметр сопла, заданный на принтере (SET_NOZZLE_DIAMETER). */
    val nozzleDiameter: Double? = null,
    val bedTemp: Double? = null,
    val bedTarget: Double? = null,
    val chamberTemp: Double? = null,
    val chamberTarget: Double? = null,
    /** print_stats.state: standby / printing / paused / complete / cancelled / error */
    val printState: String? = null,
    val printMessage: String? = null,
    val progress: Int? = null,
    val jobTimeSec: Long? = null,
    val leftTimeSec: Long? = null,
    val fileName: String? = null,
    val layer: Int? = null,
    val totalLayers: Int? = null,
    val position: String? = null,
    val homedAxes: String = "",
    val modelFanPct: Int? = null,
    val auxFanPct: Int? = null,
    val caseFanPct: Int? = null,
    val light: Boolean? = null,
    val speedPct: Int? = null,
    val flowPct: Int? = null,
    val hostname: String? = null,
    val caps: PrinterCaps = PrinterCaps(),
) {
    val isPrinting get() = printState == "printing"
    val isPaused get() = printState == "paused"
    val hasActiveJob get() = isPrinting || isPaused
    val isError get() = printState == "error" || (klippyState != null && klippyState != "ready")

    val statusText: String
        get() = when {
            klippyState == null -> tr(R.string.status_no_data)
            klippyState == "startup" -> tr(R.string.status_klipper_starting)
            klippyState == "shutdown" -> tr(R.string.klipper_shutdown)
            klippyState == "error" -> tr(R.string.status_klipper_error)
            klippyState == "disconnected" -> tr(R.string.status_klipper_disconnected)
            else -> when (printState) {
                "standby", null -> tr(R.string.status_ready)
                "printing" -> tr(R.string.status_printing)
                "paused" -> tr(R.string.status_paused)
                "complete" -> tr(R.string.status_complete)
                "cancelled" -> tr(R.string.status_cancelled)
                "error" -> tr(R.string.status_error)
                else -> printState
            }
        }

    companion object {
        fun from(o: Map<String, JSONObject>, hostname: String?, caps: PrinterCaps = PrinterCaps()): PrinterState {
            val ps = o["print_stats"]
            val sd = o["virtual_sdcard"]
            val wh = o["webhooks"]
            val ext = o["extruder"]
            val bed = o["heater_bed"]
            val chamberHeater = o["heater_generic chamber_heater"]
            val chamberSensor = o["temperature_sensor chamber_temp"]
            val move = o["gcode_move"]

            val progress = sd?.dbl("progress")
            val printDuration = ps?.dbl("print_duration")
            val estimated = sd?.optJSONObject("cur_print_data")?.optJSONObject("metadata")?.dbl("estimated_time")
            val active = ps?.optString("state") in setOf("printing", "paused")
            // Как в Fluidd: по доле файла, а в начале печати — по оценке слайсера
            val left = when {
                !active || printDuration == null -> null
                progress != null && progress > 0.05 -> printDuration / progress - printDuration
                estimated != null -> (estimated - printDuration).coerceAtLeast(0.0)
                else -> null
            }
            val info = ps?.optJSONObject("info")
            val pos = move?.optJSONArray("gcode_position")

            return PrinterState(
                klippyState = wh?.optString("state")?.ifBlank { null },
                klippyMessage = wh?.optString("state_message")?.ifBlank { null },
                nozzleTemp = ext?.dbl("temperature"),
                nozzleTarget = ext?.dbl("target"),
                nozzleDiameter = ext?.dbl("nozzle_diameter"),
                bedTemp = bed?.dbl("temperature"),
                bedTarget = bed?.dbl("target"),
                chamberTemp = chamberSensor?.dbl("temperature") ?: chamberHeater?.dbl("temperature"),
                chamberTarget = chamberHeater?.dbl("target"),
                printState = ps?.optString("state")?.ifBlank { null },
                printMessage = ps?.optString("message")?.ifBlank { null },
                progress = progress?.let { (it * 100).toInt() },
                jobTimeSec = printDuration?.toLong(),
                leftTimeSec = left?.toLong(),
                fileName = ps?.optString("filename")?.ifBlank { null },
                layer = sd?.int("layer") ?: info?.int("current_layer"),
                totalLayers = sd?.int("layer_count") ?: info?.int("total_layer"),
                position = pos?.let {
                    "X %.2f  Y %.2f  Z %.2f".format(it.optDouble(0), it.optDouble(1), it.optDouble(2))
                },
                homedAxes = o["toolhead"]?.optString("homed_axes").orEmpty(),
                modelFanPct = o["output_pin fan0"]?.pct("value") ?: o["fan"]?.pct("speed"),
                auxFanPct = o["output_pin fan1"]?.pct("value"),
                caseFanPct = o["output_pin fan2"]?.pct("value"),
                light = caps.lightPin?.let { o["output_pin $it"] }?.dbl("value")?.let { it > 0 },
                caps = caps,
                speedPct = move?.pct("speed_factor"),
                flowPct = move?.pct("extrude_factor"),
                hostname = hostname,
            )
        }

        private fun JSONObject.dbl(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

        private fun JSONObject.int(key: String): Int? = dbl(key)?.toInt()
        private fun JSONObject.pct(key: String): Int? = dbl(key)?.let { Math.round(it * 100).toInt() }
    }
}

data class GcodeFile(val path: String, val size: Long?, val modified: Double?) {
    val name get() = path.substringAfterLast('/')
}

/** Ответ server.files.list: [{path, modified, size, permissions}, …] */
fun parseGcodeFiles(arr: JSONArray?): List<GcodeFile> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val path = o.optString("path").ifBlank { return@mapNotNull null }
        GcodeFile(path, o.optLong("size", -1).takeIf { it >= 0 }, o.optDouble("modified").takeIf { !it.isNaN() })
    }.filter { it.path.endsWith(".gcode", true) }
        .sortedByDescending { it.modified ?: 0.0 }
}
