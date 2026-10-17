package app.benbow.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web build draws text with Compose's own font and our bundled emoji only; for any other character Compose for
 * Web would fetch a font from fonts.gstatic.com (blocked by the CSP, so it shows as a box; ADR-030). So the UI's own
 * texts keep to Latin, Cyrillic, common punctuation and emoji, and symbols such as arrows, stars or triangles are drawn
 * as icons. What the owner writes may hold anything; this is about what the app writes.
 */
class UiGlyphsTest {
    private val roots = listOf(File("src/commonMain"), File("../ui/src/commonMain"))
    private val literal = Regex(""""(?:[^"\\]|\\.)*"""")

    private fun covered(text: String, i: Int): Boolean {
        val c = text.codePointAt(i)
        val emoji = c in 0x1F000..0x1FAFF || text.getOrNull(i + Character.charCount(c)) == '️' || c == 0xFE0F || c == 0x200D
        return emoji || c in 0x20..0x7E || c in 0xA0..0xFF || c in 0x400..0x4FF ||
            (c in 0x2010..0x2027 && c != 0x2016) || c in 0x2030..0x205E
    }

    private fun strays(text: String): List<String> = buildList {
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (!covered(text, i)) add("U+%04X %s".format(c, String(Character.toChars(c))))
            i += Character.charCount(c)
        }
    }

    @Test
    fun theUiWritesOnlyWhatTheWebBuildsFontsHave() {
        assertTrue(roots.all { it.isDirectory }, "run from shared/app")
        val found = roots.flatMap { root -> root.walk().filter { it.isFile } }.flatMap { file ->
            file.readLines().withIndex().flatMap { (n, line) ->
                val code = line.trim()
                val texts = when {
                    file.extension == "xml" && "<!--" !in code -> listOf(code)

                    file.extension == "kt" && !code.startsWith("//") && !code.startsWith("*") && !code.startsWith("/*") ->
                        literal.findAll(code.substringBefore(" // ")).map { it.value }.toList()

                    else -> emptyList()
                }
                texts.flatMap { strays(it) }.map { "${file.path}:${n + 1} $it" }
            }
        }
        assertEquals(emptyList(), found, "draw these as icons (Icons.kt, EditorIcons.kt) or say them in words")
    }
}
