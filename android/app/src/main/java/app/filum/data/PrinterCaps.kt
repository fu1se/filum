package app.filum.data

/**
 * Что есть у конкретного принтера. Creality OS — это Klipper с надстройками Creality, и у моделей
 * разный набор: камерный нагреватель есть только у K2, CFS — у K2 и Hi, у K1 SE нет подсветки,
 * у Ender-3 V3 вентиляторы стандартные. Заполняется при подключении из `printer.objects.list`
 * и `printer.gcode.help`; экраны показывают только то, что принтер умеет.
 */
data class PrinterCaps(
    /** Список объектов уже получен (до этого показываем всё, чтобы экран не «прыгал»). */
    val known: Boolean = false,
    val objects: Set<String> = emptySet(),
    val commands: Set<String> = emptySet(),
) {
    private fun has(o: String) = !known || o in objects

    /** Вентиляторы Creality: output_pin fan0/1/2 и макросы M106/M107 с P0–P2. */
    val crealityFans get() = !known || "output_pin fan0" in objects
    val auxFan get() = crealityFans && has("output_pin fan1")
    val chamberFan get() = crealityFans && has("output_pin fan2")
    /** Стандартный вентилятор Klipper `[fan]`, если вентиляторов Creality нет. */
    val stdFan get() = known && !crealityFans && "fan" in objects

    val chamberHeater get() = has(CHAMBER_HEATER)
    val chamberTemp get() = chamberHeater || has(CHAMBER_SENSOR)

    /** Пин подсветки: у K1/K2 — `output_pin LED`. */
    val lightPin: String? get() = if (!known) "LED" else LIGHT_PINS.firstOrNull { "output_pin $it" in objects }

    val cfs get() = has("box")
    /**
     * SET_NOZZLE_DIAMETER зарегистрирована без описания, поэтому в printer.gcode.help её нет.
     * Признак — CXSAVE_CONFIG из того же Klipper Creality: им SET_NOZZLE_DIAMETER и сохраняет диаметр.
     */
    val nozzleDiameter get() = !known || "SET_NOZZLE_DIAMETER" in commands || "CXSAVE_CONFIG" in commands

    /** Датчик для хоминга Z (`probe`; у K2 это тензодатчик PRTouch). */
    val probe get() = has("probe")
    /** Z-offset можно сохранить постоянно: Z_OFFSET_APPLY_PROBE пишет z_offset датчика в автосохранение. */
    val zOffsetSave get() = probe && (!known || "Z_OFFSET_APPLY_PROBE" in commands)
    /** CXSAVE_CONFIG — запись printer.cfg без перезапуска (Klipper Creality); иначе SAVE_CONFIG перезапускает сам. */
    val cxSaveConfig get() = !known || "CXSAVE_CONFIG" in commands

    /** На что подписываться: только существующие объекты. */
    fun subscribeList(): List<String> = if (!known) ALL else ALL.filter { it in objects }

    companion object {
        const val CHAMBER_HEATER = "heater_generic chamber_heater"
        const val CHAMBER_SENSOR = "temperature_sensor chamber_temp"
        val LIGHT_PINS = listOf("LED", "led", "light", "Light", "LIGHT", "caselight")
        val ALL = listOf(
            "webhooks", "print_stats", "virtual_sdcard", "extruder", "heater_bed",
            CHAMBER_HEATER, CHAMBER_SENSOR,
            "output_pin fan0", "output_pin fan1", "output_pin fan2", "fan",
            "gcode_move", "toolhead", "pause_resume", "probe",
        ) + LIGHT_PINS.map { "output_pin $it" }
    }
}
