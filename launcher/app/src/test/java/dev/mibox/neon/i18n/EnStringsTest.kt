package dev.mibox.neon.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

class EnStringsTest {
    private val saved = Locale.getDefault()

    @Before fun english() = Locale.setDefault(Locale.ENGLISH)

    @After fun restore() = Locale.setDefault(saved)

    /** Every t("…") literal in the app sources, unescaped the way Kotlin reads it. */
    private fun sourceKeys(): Map<String, String> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val call = Regex("""(?<![\w.])t\("((?:[^"\\]|\\.)*)"""")
        val keys = mutableMapOf<String, String>()
        root.walk().filter { it.extension == "kt" && "i18n" !in it.path }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                val s = line.trimStart()
                if (s.startsWith("//") || s.startsWith("*") || s.startsWith("/**")) return@forEachIndexed
                call.findAll(line).forEach { keys.getOrPut(it.groupValues[1].replace("\\\"", "\"")) { "${f.name}:${i + 1}" } }
            }
        }
        return keys
    }

    @Test fun everyKeyIsTranslated() {
        val keys = sourceKeys()
        assertTrue("found only ${keys.size} keys", keys.size > 300)
        val missing = keys.filterKeys { it !in EN }
        assertTrue("missing English for:\n" + missing.entries.joinToString("\n") { "${it.value}  ${it.key}" }, missing.isEmpty())
    }

    @Test fun placeholdersMatch() {
        val ph = Regex("""\{\d}""")
        val bad = EN.filter { (k, v) -> ph.findAll(k).map { it.value }.toSet() != ph.findAll(v).map { it.value }.toSet() }
        assertTrue("placeholders differ: $bad", bad.isEmpty())
    }

    @Test fun englishTemplates() {
        assertEquals("RAM 42%", t("RAM %{0}", 42))
        assertEquals("Season 2, Episode 5", t("{0}. Sezon {1}. Bölüm", 2, 5))
        assertEquals("{0} apps · p. 1–{0}".replace("{0}", "7"), t("{0} uygulama · s. 1–{0}", 7))
        assertEquals("Not in catalog", t("Not in catalog"))
    }

    @Test fun turkishIsTheSource() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        assertEquals("RAM %42", t("RAM %{0}", 42))
        assertEquals("Açık", t("Açık"))
    }
}
