package app.filum.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

/** Строки — настоящие ответы прошивки K2 (gcode_store, fault_code_info.json, box_wrapper). */
class FaultsTest {

    companion object {
        /** tr() в тестах: русские строки прямо из res/values-ru/strings.xml, по имени поля R.string. */
        @JvmStatic @BeforeClass fun ruStrings() {
            val xml = java.io.File("src/main/res/values-ru/strings.xml").readText()
            val byName = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""").findAll(xml)
                .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }
            val byId = app.filum.R.string::class.java.fields.associate { it.getInt(null) to byName[it.name] }
            app.filum.Tr.resolve = { id, args -> (byId[id] ?: "#$id").let { if (args.isEmpty()) it else it.format(*args) } }
        }
    }

    @Test
    fun runoutWarningMapsToScreenCode116() {
        val f = Faults.parseResponse("""## !{"code":"key358", "msg":"filament switch sensor runout", "values": []}""")!!
        assertEquals(116, f.code)
        assertEquals("Закончился филамент", f.title)
        assertNotNull(f.todo)
    }

    @Test
    fun cfsErrorNamesBoxAndSlot() {
        val f = Faults.parseResponse(
            """!! {"code":"key839", "msg":"filament error, no filament detected at box extrude position", "values": [1, "B"]}""")!!
        assertEquals(2839, f.code)
        assertEquals("CFS: нет прутка в позиции подачи", f.title)
        assertEquals("CFS 1, слот B", f.detail)
    }

    @Test
    fun aiCameraOnlyFromScreenCode() {
        val f = Faults.describe(101, """{"prob": 0.708728}""")!!
        assertEquals("ИИ-камера: «спагетти»", f.title)
        assertNotNull(f.what)
    }

    @Test
    fun unknownKeyKeepsFirmwareText() {
        val f = Faults.parseResponse("""!! {"code":"key999", "msg":"something new", "values": []}""")!!
        assertEquals(2999, f.code)
        assertEquals("Something new", f.title)
    }

    @Test
    fun plainKlipperErrorKeepsText() {
        val f = Faults.parseResponse("!! Move out of range: 442.500 203.800 -0.308 [5.000]")!!
        assertEquals("Move out of range: 442.500 203.800 -0.308 [5.000]", f.detail)
    }

    @Test
    fun ignoresNoiseAndNonErrors() {
        assertNull(Faults.parseResponse("B:70.2 /70.0 T0:227.1 /225.0"))
        assertNull(Faults.parseResponse("// temp_pos:1.5920553960022517"))
        assertNull(Faults.parseResponse("""## !{"code":"key115", "msg":"exist power loss file", "values": []}"""))
        assertNull(Faults.describe(0))
    }
}
