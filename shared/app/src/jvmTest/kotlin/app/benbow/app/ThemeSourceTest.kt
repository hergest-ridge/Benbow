package app.benbow.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Colors come from the theme (ADR-057): outside `ui/theme`, no screen asks a color how dark it is, keeps hex, or fades a
 * line or quiet words by hand. The few exceptions are named here with their reasons.
 */
class ThemeSourceTest {
    private val roots = listOf(File("src"), File("../ui/src")).filter { it.isDirectory }

    private val sources = roots.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("Test") }.toList()
    }.filterNot { it.invariantSeparatorsPath.contains("/ui/theme/") }

    /** Hex kept on purpose: things drawn as things, and files read or written for outside the app. */
    private val hexAllowed = mapOf(
        "NoteScrap.kt" to "the scrapbook's materials: kraft paper, stamp ink, photo corners",
        "RecorderKey.kt" to "the dictaphone keys' lamps, as hardware",
        "LookPictures.kt" to "colors read from a picture's SVG",
        "InkPicture.kt" to "a drawing's ink in its SVG file, for viewers outside the app",
    )

    /** Faded quiet colors kept on purpose: marks, not words (how many per file). */
    private val fadeAllowed = mapOf(
        "CalendarOverview.kt" to 1, // a day without entries: a faint speck
        "FeedScrubber.kt" to 1, // the scrubber's handle at rest
    )

    @Test
    fun onlyTheThemeAsksHowDarkAColorIs() = assertNone("luminance()") { _, line -> line.contains(".luminance()") }

    @Test
    fun hexLivesInTheTheme() = assertNone("hex") { file, line -> file.name !in hexAllowed && Regex("""Color\(0x""").containsMatchIn(line) }

    @Test
    fun linesComeFromTheirRoles() = assertNone("a faded outline variant (use divider or hairline)") { _, line ->
        line.contains("outlineVariant.copy(alpha")
    }

    @Test
    fun quietWordsComeFromTheirRole() {
        val found = sources.associate { it.name to it.readLines().count { line -> line.contains("onSurfaceVariant.copy(alpha") } }
            .filterValues { it > 0 }
        val over = found.filter { (name, count) -> count > (fadeAllowed[name] ?: 0) }
        assertTrue(over.isEmpty(), "onSurfaceVariant faded by hand (use textQuiet or disabled): $over")
    }

    private fun assertNone(what: String, bad: (File, String) -> Boolean) {
        val found = sources.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> !line.trimStart().startsWith("//") && bad(file, line) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertTrue(sources.isNotEmpty(), "no sources found from ${File(".").absolutePath}")
        assertTrue(found.isEmpty(), "$what outside the theme:\n" + found.joinToString("\n"))
    }
}
