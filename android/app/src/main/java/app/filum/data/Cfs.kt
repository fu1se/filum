package app.filum.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Пластик из библиотеки принтера (то же, что предлагает экран K2 при правке слота). */
data class MaterialDef(
    val id: String,
    val brand: String,
    val name: String,
    val type: String,
    val minTemp: Int,
    val maxTemp: Int,
    val pressure: Double?,
    /** Цвета производителя, «#RRGGBB». */
    val colors: List<String>,
    /** Добавлен из ваших пресетов orca-sync (printer/material-sync). */
    val synced: Boolean = false,
)

/**
 * CFS напрямую с принтера, через штатный WebSocket прошивки Creality (:9999) —
 * тот же канал, что у Creality Print и экрана принтера:
 * - `get boxsInfo` — боксы и катушки (тип, производитель, цвет, температуры, остаток);
 * - `get reqMaterials` — библиотека пластиков;
 * - `set modifyMaterial` — правка слота.
 */
class CfsRepository {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Шлёт [params] и ждёт сообщение, в котором есть ключ [key]. */
    private suspend fun request(host: String, params: JSONObject, key: String, timeoutMs: Long = 10_000): JSONObject =
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val ws = http.newWebSocket(
                    Request.Builder().url("ws://$host:9999").build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(JSONObject().put("method", "get").put("params", params).toString())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text.contains("heart_beat")) { webSocket.send("ok"); return }
                            if (!text.contains("\"$key\"")) return
                            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                            if (!obj.has(key)) return
                            webSocket.close(1000, null)
                            if (cont.isActive) cont.resume(obj)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    },
                )
                cont.invokeOnCancellation { ws.cancel() }
            }
        }

    suspend fun slots(host: String): List<CfsSlot> {
        val info = request(host, JSONObject().put("boxsInfo", 1), "boxsInfo").getJSONObject("boxsInfo")
        val boxes = info.optJSONArray("materialBoxs") ?: JSONArray()
        val result = mutableListOf<CfsSlot>()
        for (i in 0 until boxes.length()) {
            val b = boxes.optJSONObject(i) ?: continue
            // type 1 — внешний держатель катушки (box 0, один слот), type 0 — бокс CFS
            val external = b.optInt("type", -1) == 1
            val box = if (external) 0 else b.optInt("id")
            if (box !in 0..4 || (!external && b.optInt("state", 1) == 0)) continue
            val mats = b.optJSONArray("materials") ?: JSONArray()
            for (k in 0 until mats.length()) {
                val m = mats.optJSONObject(k) ?: continue
                val slot = m.optInt("id", k)
                if (slot !in 0..3 || (external && slot != 0)) continue
                val letter = "ABCD"[slot].toString()
                val color = creColor(m.optString("color"))
                val empty = m.optInt("state") == 0 || color == null || m.optString("type").isBlank()
                result += CfsSlot(
                    id = if (external) EXTERNAL_ID else "T$box$letter", box = box, letter = letter, empty = empty,
                    color = color,
                    type = m.optString("type").ifBlank { null },
                    brand = m.optString("vendor").ifBlank { null },
                    name = m.optString("name").ifBlank { null },
                    remain = if (empty || !m.has("percent")) null else m.optInt("percent"),
                    preset = null,
                    rfid = m.optString("rfid").ifBlank { null },
                    minTemp = m.optInt("minTemp").takeIf { it > 0 },
                    maxTemp = m.optInt("maxTemp").takeIf { it > 0 },
                    pressure = m.optDouble("pressure").takeIf { !it.isNaN() },
                    humidity = b.optDouble("humidity").takeIf { !it.isNaN() },
                    boxTemp = b.optDouble("temp").takeIf { !it.isNaN() },
                )
            }
        }
        return result.sortedWith(compareBy({ it.box }, { it.letter }))
    }

    suspend fun library(host: String): List<MaterialDef> {
        val arr = request(host, JSONObject().put("reqMaterials", 1), "retMaterials", 20_000).getJSONArray("retMaterials")
        val list = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val b = e.optJSONObject("base") ?: return@mapNotNull null
            val kv = e.optJSONObject("kvParam")
            val colors = b.optJSONArray("colors")?.let { c ->
                (0 until c.length()).mapNotNull { j -> c.optString(j).takeIf { it.length >= 7 }?.take(7)?.uppercase() }
            }.orEmpty()
            MaterialDef(
                id = b.optString("id"), brand = b.optString("brand"), name = b.optString("name"),
                type = b.optString("meterialType").ifBlank { kv?.optString("filament_type").orEmpty() },
                minTemp = b.optInt("minTemp").takeIf { it > 0 } ?: kv?.optString("nozzle_temperature_range_low")?.toIntOrNull() ?: 0,
                maxTemp = b.optInt("maxTemp").takeIf { it > 0 } ?: kv?.optString("nozzle_temperature_range_high")?.toIntOrNull() ?: 0,
                pressure = kv?.optString("pressure_advance")?.toDoubleOrNull(),
                colors = colors.distinct(),
                synced = b.optString("alias") == "orca-sync",
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        return list
    }

    /** Записывает в слот пластик [m] цвета [color] («#RRGGBB»). */
    suspend fun modify(host: String, slot: CfsSlot, m: MaterialDef, color: String, minTemp: Int, maxTemp: Int, pressure: Double) {
        val body = JSONObject()
            .put("boxId", slot.box)
            .put("id", "ABCD".indexOf(slot.letter))
            .put("rfid", m.id)
            .put("type", m.type)
            .put("vendor", m.brand)
            .put("name", m.name)
            .put("color", "#0" + color.removePrefix("#").take(6).lowercase())
            .put("pressure", pressure)
            .put("selected", 1)
            .put("percent", slot.remain ?: 100)
            .put("editStatus", 1)
            .put("state", 1)
        val ws = withTimeout(10_000) {
            suspendCancellableCoroutine<WebSocket> { cont ->
                val ws = http.newWebSocket(
                    Request.Builder().url("ws://$host:9999").build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            // Температуры прошивка принимает только как дробные (220.0): целое 220 она
                            // записывает нулём, а org.json печатает 220.0 как 220 — дописываем их сами.
                            val fields = body.toString().removeSuffix("}") +
                                ",\"minTemp\":$minTemp.0,\"maxTemp\":$maxTemp.0}"
                            val msg = "{\"method\":\"set\",\"params\":{\"modifyMaterial\":$fields}}"
                            webSocket.send(msg)
                            if (cont.isActive) cont.resume(webSocket)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text.contains("heart_beat")) webSocket.send("ok")
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    },
                )
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
        try {
            delay(1500) // прошивка применяет правку асинхронно
        } finally {
            ws.close(1000, null)
        }
    }

    companion object {
        /** Id слота внешнего держателя. */
        const val EXTERNAL_ID = "EXT"

        /** «#0RRGGBB» (формат Creality) → «#RRGGBB». */
        fun creColor(v: String?): String? {
            val s = v?.trim()?.removePrefix("#") ?: return null
            val hex = when (s.length) {
                7 -> s.substring(1)
                6, 8 -> s.take(6)
                else -> return null
            }
            return if (hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) "#" + hex.uppercase() else null
        }
    }
}
