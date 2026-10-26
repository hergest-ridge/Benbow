package app.benbow.app

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.setLook
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The look of a note (ADR-047, 7.20 L1): its tokens, its names, and how it shows in the feed and in the open note. */
@OptIn(ExperimentalTestApi::class)
class NoteLooksTest {
    @Test
    fun everyChoiceDrawsSomething() {
        for (layer in LookLayer.entries) {
            for (token in NoteLooks.choices(layer)) {
                assertFalse(NoteLooks.look(mapOf(layer.field to token)).isEmpty, "${layer.field} $token draws")
            }
        }
    }

    @Test
    fun everyChoiceHasItsName() {
        val strings = File("src/commonMain/composeResources/values/strings.xml").readText()
        val arrays = mapOf(
            LookLayer.Paper to "look_papers",
            LookLayer.Pattern to "look_patterns",
            LookLayer.Light to "look_lights",
            LookLayer.Motion to "look_motions",
            LookLayer.Scrap to "look_scraps",
        )
        for ((layer, array) in arrays) {
            val items = Regex("""<string-array name="$array">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL).find(strings)!!
                .groupValues[1].split("<item>").size - 1
            assertEquals(NoteLooks.choices(layer).size, items, "$array names its choices in order")
        }
    }

    @Test
    fun unknownTokensDrawNothing() {
        val look = NoteLooks.look(
            mapOf(
                "paper" to "velvet",
                "pattern" to "hero-nope",
                "light" to "999 pink 2;45 neon 2;45 blue 9",
                "motion" to "spin",
                "scrap" to "glitter.3",
                "font" to "caveat", // a newer app's layer
            ),
        )
        assertTrue(look.isEmpty, "a newer app's look draws as nothing here: $look")
    }

    @Test
    fun aLightIsItsSourcesWrittenOut() {
        assertEquals(listOf(LookLight(315f, "orange", 2), LookLight(45f, "blue", 1)), NoteLooks.lightsOf("315 orange 2;45 blue 1"))
        assertEquals(3, NoteLooks.lightsOf("1 pink 1;2 pink 1;3 pink 1;4 pink 1").size, "three at most")
        assertEquals(listOf(LookLight(45f, "blue", 2)), NoteLooks.lightsOf("45 blue 2;nonsense"), "a bad source is left out")
    }

    @Test
    fun shufflingKeepsTheKind() {
        assertEquals("tape" to 0, NoteLooks.scrapOf("tape"))
        assertEquals("tape" to 7, NoteLooks.scrapOf("tape.7"))
        assertEquals(null to 0, NoteLooks.scrapOf("glitter.7"))
        val shuffled = NoteLooks.shuffled("collage.3")
        assertEquals("collage", NoteLooks.scrapOf(shuffled).first)
        assertEquals(7 * 7919, NoteLooks.look(mapOf("scrap" to "tape.7")).scrapShuffle * 7919, "the shuffle reaches the drawing")
    }

    @Test
    fun aScrapbookKeepsItsTonesAtEverySizeAndItsScrapsStaySmall() {
        val paper = Color(0xFFE9E3D4)
        val ink = Color(0xFF1D1B18)
        for (kind in NoteLooks.scraps) {
            for (seed in listOf(1, 7, 42, -3)) {
                val card = Scrapbook(kind, seed, Size(380f, 390f), paper, ink, dark = false, px = 2f)
                val page = Scrapbook(kind, seed, Size(1600f, 2400f), paper, ink, dark = false, px = 2f)
                assertEquals(card.tones(), page.tones(), "$kind $seed: the same tones on a card and on the open page")
            }
        }
        val wide = Scrapbook("scraps", 7, Size(2560f, 2880f), paper, ink, dark = false, px = 2f)
        assertTrue(wide.scrapSizes().all { it.width <= 600f && it.height <= 300f }, "no poster of a scrap: ${wide.scrapSizes()}")
    }

    @Test
    fun aLookInTheFeedAndInTheNote() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width = 412, height = 860) {
                val services = feedTestServices()
                val mode = if (dark) app.benbow.ui.theme.ThemeMode.Dark else app.benbow.ui.theme.ThemeMode.Light
                services.setAppearance(services.appearance.value.copy(mode = mode))
                val today = services.today()
                val sea = services.repo.createNote(
                    today,
                    "Утро у моря\nПроснулась раньше будильника. Чай на балконе, чайки, ветер пахнет водорослями.",
                )
                services.repo.setBackground(sea, "fog")
                services.repo.setLook(sea, mapOf("paper" to "lines", "scrap" to "tape", "light" to "45 blue 2"))
                val weekend = services.repo.createNote(today, "На выходные\nКупить ниток и пуговиц. Позвонить бабушке.")
                services.repo.setBackground(weekend, "sand")
                services.repo.setLook(weekend, mapOf("pattern" to "hero-topography", "light" to "315 orange 2"))
                setContent { BenbowApp(services) }
                onAllNodesWithText("Лента")[0].click()
                waitUntilAtLeastOneExists(hasText("Утро у моря", substring = true), 5_000)
                runBlocking { delay(400) } // the Hero tile loads off the test's idling
                waitForIdle()
                val theme = if (dark) "dark" else "light"
                screenshot("looks-l1-feed-$theme")
                onAllNodesWithText("Утро у моря", substring = true)[0].click()
                waitUntilAtLeastOneExists(hasText("Проснулась раньше", substring = true), 5_000)
                waitForIdle()
                screenshot("looks-l1-note-$theme")
            }
        }
    }
}
