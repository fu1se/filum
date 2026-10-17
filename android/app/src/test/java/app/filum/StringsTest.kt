package app.filum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Переводы: одинаковые ключи в values и values-ru и одинаковые аргументы (%1$s, %.1f…). */
class StringsTest {

    private fun load(dir: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$dir/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(s: String) =
        Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdf]""").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun sameKeys() {
        val en = load("values"); val ru = load("values-ru")
        assertEquals("нет русского перевода", emptySet<String>(), en.keys - ru.keys - setOf("app_name"))
        assertEquals("нет английского перевода", emptySet<String>(), ru.keys - en.keys)
    }

    @Test
    fun samePlaceholders() {
        val en = load("values"); val ru = load("values-ru")
        val bad = en.keys.intersect(ru.keys).filter { placeholders(en.getValue(it)) != placeholders(ru.getValue(it)) }
        assertTrue("разные аргументы: $bad", bad.isEmpty())
    }
}

class PresetMatchTest {
    @Test
    fun modelNamesDoNotOverlap() {
        assertTrue(presetMatchesModel("Creality K2 Plus 0.4 nozzle", "K2 Plus"))
        assertTrue(presetMatchesModel("Creality K2 Plus 0.4 nozzle - Copy", "K2 Plus"))
        assertTrue(!presetMatchesModel("Creality K1 Max 0.4 nozzle", "K1"))
        assertTrue(presetMatchesModel("Creality K1 0.4 nozzle", "K1"))
        assertTrue(presetMatchesModel("Creality Hi 0.4 nozzle", "Creality Hi"))
        assertTrue(presetMatchesModel("Creality Ender-3 V3 KE 0.4 nozzle", "Ender-3 V3 KE"))
        assertTrue(!presetMatchesModel("Creality Ender-3 V3 KE 0.4 nozzle", "Ender-3 V3"))
    }
}
