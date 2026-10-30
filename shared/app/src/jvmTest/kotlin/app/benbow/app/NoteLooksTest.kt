package app.benbow.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.text.font.FontFamily
import app.benbow.client.Connection
import app.benbow.client.FolderFontShelf
import app.benbow.client.JournalRepository
import app.benbow.client.LookFields
import app.benbow.client.NOTE_BLACK
import app.benbow.client.Settings
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.createBoardNote
import app.benbow.client.db.BenbowDb
import app.benbow.client.setLook
import app.benbow.ui.editor.NoteFont
import app.benbow.ui.theme.BenbowTheme
import app.benbow.ui.theme.InTheDark
import app.benbow.ui.theme.SwatchColors
import app.benbow.ui.theme.ThemePalette
import app.benbow.ui.theme.ThemeSpec
import app.benbow.ui.theme.colorsOf
import app.benbow.ui.theme.contrastRatio
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
                "picture" to "fern", // a newer app's layer
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
    fun aLookInAWideWindowFillsTheRightSide() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val services = feedTestServices()
        services.setAppearance(services.appearance.value.copy(mode = app.benbow.ui.theme.ThemeMode.Dark))
        val today = services.today()
        val sea = services.repo.createNote(today, "Утро у моря\nПроснулась раньше будильника. Чай на балконе, чайки.")
        services.repo.setBackground(sea, "dusk")
        services.repo.setLook(sea, mapOf("paper" to "grid", "motion" to "drift", "light" to "315 orange 3;135 teal 2", "scrap" to "stamp"))
        setContent { BenbowApp(services) }
        onAllNodesWithText("Лента")[0].click()
        waitUntilAtLeastOneExists(hasText("Утро у моря", substring = true), 5_000)
        onAllNodesWithText("Утро у моря", substring = true)[0].click()
        waitUntilAtLeastOneExists(hasText("Проснулась раньше", substring = true), 5_000)
        waitForIdle()
        screenshot("looks-pane-wide")
    }

    @Test
    fun aScrapbookKeepsItsTonesAtEverySizeAndItsScrapsStaySmall() {
        val paper = Color(0xFFE9E3D4)
        val ink = Color(0xFF1D1B18)
        for (kind in NoteLooks.scraps) {
            for (seed in listOf(1, 7, 42, -3)) {
                val card =
                    Scrapbook(
                        kind,
                        seed,
                        Size(380f, 390f),
                        paper,
                        ink,
                        dark = false,
                        tokens = colorsOf(ThemePalette.Inn, dark = false).tokens,
                        px = 2f,
                    )
                val page =
                    Scrapbook(
                        kind,
                        seed,
                        Size(1600f, 2400f),
                        paper,
                        ink,
                        dark = false,
                        tokens = colorsOf(ThemePalette.Inn, dark = false).tokens,
                        px = 2f,
                    )
                assertEquals(card.tones(), page.tones(), "$kind $seed: the same tones on a card and on the open page")
            }
        }
        val wide =
            Scrapbook(
                "scraps",
                7,
                Size(2560f, 2880f),
                paper,
                ink,
                dark = false,
                tokens = colorsOf(ThemePalette.Inn, dark = false).tokens,
                px = 2f,
            )
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
                    "Утро у моря\nПроснулась раньше будильника. Чай на балконе, чайки, ветер пахнет водорослями." +
                        (1..30).joinToString("") { "\nСтрока $it: море, ветер, чайки." },
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
                // The paper goes up with the words (owner): the lines and the tape move, the light stays.
                onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.3f) }
                waitForIdle()
                screenshot("looks-l1-note-scrolled-$theme")
            }
        }
    }

    @Test
    fun everyFontIsBundledAndAnUnknownOneIsTheAppsOwn() = runDesktopComposeUiTest {
        var fonts: List<NoteFont?> = emptyList()
        var unknown: NoteFont? = NoteFont(FontFamily.Default)
        setContent {
            fonts = NoteFonts.tokens.map { noteFont(it) }
            unknown = noteFont("caveat") // one of the server's pack (L4), not in this app
        }
        waitForIdle()
        assertEquals(8, NoteFonts.tokens.distinct().size)
        assertTrue(fonts.all { it != null }, "every font offered is in the app")
        assertEquals(null, unknown, "an unknown font is the app's own")
        assertTrue(NoteFonts.tokens.all(LookFields::isToken))
        assertEquals("Amatic SC", NoteFonts.name("amatic-sc"))
        assertEquals("JetBrains Mono", NoteFonts.name("jetbrains-mono"))
    }

    /** The owner, 2026-10-28: the note in its font, open and on the board; the feed keeps the app's font. */
    @Test
    fun aNoteInItsOwnFont() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = feedTestServices()
        val today = services.today()
        val words = "Утро у моря\nПроснулась раньше будильника. Чай на балконе, чайки, ветер пахнет водорослями."
        val sea = services.repo.createNote(today, words)
        services.repo.setBackground(sea, "sand")
        services.repo.setLook(sea, mapOf("font" to "neucha", "paper" to "lines"))
        val list = services.repo.createBoardNote(
            today,
            JournalRepository.textBody("На выходные\nКупить ниток и пуговиц\nПозвонить бабушке"),
        )
        services.repo.setLook(list, mapOf("font" to "amatic-sc"))
        val shop = services.repo.createBoardNote(today, JournalRepository.textBody("Ремонт\nНиша 62 см, розетка слева"))
        services.repo.setLook(shop, mapOf("font" to "cormorant-infant"))
        setContent { BenbowApp(services) }
        onAllNodesWithText("Лента")[0].click()
        waitUntilAtLeastOneExists(hasText("Утро у моря", substring = true), 5_000)
        waitForIdle()
        screenshot("fonts-feed")
        onAllNodesWithText("Утро у моря", substring = true)[0].click()
        waitUntilAtLeastOneExists(hasText("Проснулась раньше", substring = true), 5_000)
        waitForIdle()
        screenshot("fonts-note")
        onNodeWithContentDescription("Назад").performClick()
        waitUntilAtLeastOneExists(hasText("Записки"), 5_000)
        onAllNodesWithText("Записки")[0].click()
        waitUntilAtLeastOneExists(hasText("На выходные"), 5_000)
        waitForIdle()
        screenshot("fonts-board")
    }

    /** ADR-054: the sheet's parts as a list, each opening as a grid; «Шрифт» by its groups, the diary's hands first. */
    @Test
    fun theSheetListsItsPartsAndEachOpensAsAGrid() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width = 412, height = 860) {
                setContent {
                    app.benbow.ui.theme.BenbowTheme(darkTheme = dark) {
                        LookSheet(
                            LookTarget(
                                id = "n",
                                words = "Утро у моря\nПроснулась раньше будильника. Чай на балконе, чайки.",
                                color = null,
                                look = mapOf("font" to "pangolin", "pattern" to "waves"),
                                page = MaterialTheme.colorScheme.background,
                                onColor = {},
                                onLook = {},
                            ),
                            onDismiss = {},
                        )
                    }
                }
                val theme = if (dark) "dark" else "light"
                waitUntilAtLeastOneExists(hasText("Шрифт"), 5_000)
                waitUntilAtLeastOneExists(hasText("Pangolin"), 5_000) // what is chosen, said in the list
                waitForIdle()
                screenshot("look-parts-$theme")
                onAllNodesWithText("Шрифт")[0].click()
                waitUntilAtLeastOneExists(hasText("Рукописные"), 5_000)
                waitForIdle()
                screenshot("look-fonts-$theme")
                onNodeWithContentDescription("Назад").performClick()
                waitUntilAtLeastOneExists(hasText("Узор"), 5_000)
                onAllNodesWithText("Узор")[0].click()
                waitUntilAtLeastOneExists(hasText("Волны"), 5_000)
                waitForIdle()
                screenshot("look-patterns-$theme")
            }
        }
    }

    /** ADR-052: a font from the server's pack, its file kept on the device, in the open note and in «Шрифт». */
    @Test
    fun aNoteInAFontFromTheServer() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = serverFontServices("caveat")
        val sea = services.repo.createNote(services.today(), "Утро у моря\nПроснулась раньше будильника. Чай на балконе, чайки.")
        services.repo.setLook(sea, mapOf("font" to "caveat"))
        assertEquals(
            listOf("neucha", "pangolin", "marmelad", "amatic-sc", "caveat"),
            NoteFonts.choices(services.fonts.catalog.value).take(5).map { it.token },
            "the diary's hands first, the app's own before the server's",
        )
        setContent { BenbowApp(services) }
        onAllNodesWithText("Лента")[0].click()
        waitUntilAtLeastOneExists(hasText("Утро у моря", substring = true), 5_000)
        onAllNodesWithText("Утро у моря", substring = true)[0].click()
        waitUntilAtLeastOneExists(hasText("Проснулась раньше", substring = true), 5_000)
        runBlocking { delay(300) } // the file is read off the test's idling
        waitForIdle()
        screenshot("fonts-server-note")
    }

    /** The owner, 2026-10-29: after one font from the server, the next one picked didn't take (on the web). */
    @Test
    fun aSecondFontFromTheServerTakesItsPlace() = runDesktopComposeUiTest {
        val services = serverFontServices("caveat", "bad-script")
        var token by mutableStateOf("caveat")
        var drawn: NoteFont? = null
        setContent { CompositionLocalProvider(LocalServerFonts provides services.fonts) { drawn = noteFont(token) } }
        waitUntil(timeoutMillis = 5_000) { drawn != null }
        val caveat = drawn
        token = "bad-script"
        waitUntil(timeoutMillis = 5_000) { drawn != null && drawn !== caveat }
        token = "caveat"
        waitUntil(timeoutMillis = 5_000) { drawn === caveat }
    }

    /** The owner, 2026-10-30: a new note's «Оформление» still came up from below; beside it, as any other note's. */
    @Test
    fun aNewNotesLookStandsBesideItToo() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val services = feedTestServices()
        setContent { BenbowApp(services) }
        onAllNodesWithText("Лента")[0].click()
        waitUntilAtLeastOneExists(hasText("Новая запись"), 5_000)
        onAllNodesWithText("Новая запись")[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Оформление записки"), 5_000)
        onNodeWithContentDescription("Оформление записки").performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Закрыть"), 5_000) // the pane's ✕: a sheet has none
        onAllNodesWithText("Бумага")[0].assertExists()
        waitForIdle()
        screenshot("look-beside-new-note")
    }

    /** The owner, 2026-10-30: from one photographed paper straight to another, the first one stayed. */
    @Test
    fun aPaperAfterAnotherIsTheNewOne() = runDesktopComposeUiTest {
        var path by mutableStateOf("paper/paper001.jpg")
        var shown: ImageBitmap? = null
        setContent { shown = lookPhoto(path) }
        waitUntil(timeoutMillis = 5_000) { shown != null }
        val white = shown
        path = "paper/paper003.jpg"
        waitUntil(timeoutMillis = 5_000) { shown != null && shown !== white }
        assertSame(LookPictures.photo("paper/paper003.jpg"), shown)
    }

    /** Patterns the owner took out (2026-10-30) are no longer offered; a note that has one draws nothing and keeps it. */
    @Test
    fun patternsTakenOutDrawNothing() {
        for (token in listOf("morris-willow-bough", "morris-marigold", "morris-daisy", "morris-fruit", "hero-moroccan")) {
            assertFalse(token in NoteLooks.patterns, "$token is not offered")
            assertTrue(NoteLooks.look(mapOf("pattern" to token)).isEmpty, "$token draws nothing")
        }
    }

    /** A black note (owner, 2026-10-30): its words and its look's ink read on it in a light theme, the theme's dark twin. */
    @Test
    fun aBlackNoteReadsInTheLight() {
        for (palette in ThemePalette.entries) {
            runDesktopComposeUiTest {
                var page = Color.Unspecified
                var words = Color.Unspecified
                var quiet = Color.Unspecified
                var twin = false
                setContent {
                    BenbowTheme(darkTheme = false, spec = ThemeSpec(palette)) {
                        page = swatch(SwatchColors.BLACK)!!
                        InTheDark(true) {
                            words = MaterialTheme.colorScheme.onSurface
                            quiet = MaterialTheme.colorScheme.onSurfaceVariant
                            twin = BenbowTheme.colors.dark
                        }
                    }
                }
                waitForIdle()
                assertEquals(NOTE_BLACK, SwatchColors.BLACK, "the app and the data say the same black")
                assertTrue(twin, "$palette: a black note wears the dark twin")
                assertTrue(contrastRatio(words, page) >= 7.0, "$palette: words on black ${contrastRatio(words, page)}")
                assertTrue(contrastRatio(quiet, page) >= 4.5, "$palette: quiet words on black ${contrastRatio(quiet, page)}")
            }
        }
    }

    /** The owner, 2026-10-30: a black note in the feed and open, in the light theme; «Цвет» offers it with the palette. */
    @Test
    fun aBlackNoteInTheFeedAndOpen() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val services = feedTestServices()
        services.setAppearance(services.appearance.value.copy(mode = app.benbow.ui.theme.ThemeMode.Light))
        val night = services.repo.createNote(services.today(), "Ночная запись\nЗвёзды над крышами, тишина.")
        services.repo.setBackground(night, SwatchColors.BLACK)
        services.repo.setLook(night, mapOf("pattern" to "stars"))
        setContent { BenbowApp(services) }
        onAllNodesWithText("Лента")[0].click()
        waitUntilAtLeastOneExists(hasText("Ночная запись", substring = true), 5_000)
        onAllNodesWithText("Ночная запись", substring = true)[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onAllNodes(hasContentDescription("Меню")).onLast().performClick()
        onNodeWithText("Редактировать").performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Оформление записки"), 5_000)
        onNodeWithContentDescription("Оформление записки").performClick()
        waitUntilAtLeastOneExists(hasText("Цвет"), 5_000)
        onAllNodesWithText("Цвет")[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Чёрный"), 5_000)
        waitUntilAtLeastOneExists(hasText("Ещё оттенки"), 5_000)
        runBlocking { delay(300) } // the stars' pictures are read off the test's idling
        waitForIdle()
        screenshot("look-black-note")
        // Its stars show on the black page: the look held to its light words, not to the theme's dark ones.
        val shot = onAllNodes(isRoot()).onLast().captureToImage().toPixelMap()
        val page = (500 until 1260 step 4).flatMap { x -> (200 until 800 step 4).map { y -> shot[x, y] } }.toSet()
        assertTrue(page.size > 1, "the black page draws its pattern: ${page.size} colors")
    }

    /** ADR-054: in a wide window «Оформление» stands beside the note, the page against the feed, nothing over anything. */
    @Test
    fun inAWideWindowTheLookStandsBesideTheNote() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val services = feedTestServices()
        val sea = services.repo.createNote(services.today(), "Утро у моря\nПроснулась раньше будильника. Чай на балконе, чайки.")
        services.repo.setLook(sea, mapOf("paper" to "lines"))
        setContent { BenbowApp(services) }
        onAllNodesWithText("Лента")[0].click()
        waitUntilAtLeastOneExists(hasText("Утро у моря", substring = true), 5_000)
        onAllNodesWithText("Утро у моря", substring = true)[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onAllNodes(hasContentDescription("Меню")).onLast().performClick()
        onNodeWithText("Редактировать").performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Оформление записки"), 5_000)
        onNodeWithContentDescription("Оформление записки").performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Закрыть"), 5_000)
        waitForIdle()
        screenshot("look-beside-note")
        val page = onAllNodes(hasText("Проснулась раньше", substring = true))[0].fetchSemanticsNode().boundsInRoot
        val panel = onAllNodesWithText("Бумага")[0].fetchSemanticsNode().boundsInRoot
        assertTrue(page.right < panel.left, "the page and the pane side by side: $page, $panel")
        // A color picked keeps «Цвет» open (owner, 2026-10-30: the pane went back to its list).
        onAllNodesWithText("Цвет")[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Мятный"), 5_000)
        onNodeWithContentDescription("Мятный").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.entry(sea).first()?.background == "mint" } }
        waitForIdle()
        onAllNodesWithText("Ещё оттенки")[0].assertExists()
        onNodeWithContentDescription("Закрыть").performClick()
        waitUntilDoesNotExist(hasContentDescription("Закрыть"), 5_000)
    }

    /** Services paired with a server that isn't there, its catalog and the [tokens]' files kept on the device. */
    private fun serverFontServices(vararg tokens: String): AppServices {
        val files = tokens.associateWith { File("../../server/packaging/fonts/$it.ttf").readBytes() }
        val sha = { bytes: ByteArray ->
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        // A file, as on desktop: paired, the services sync at once beside the search index, and one in-memory
        // connection can't hold both transactions.
        val dbFile = File(Files.createTempDirectory("benbow-db").toFile(), "benbow.db")
        // WAL, as the desktop app opens it: Home reads its posts while sync writes, and neither waits on the other.
        val wal = Properties().apply { put("journal_mode", "WAL") }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", wal, BenbowDb.Schema)
        val fonts = files.entries.joinToString(",") { (token, bytes) ->
            """{"token":"$token","name":"$token","group":"diary","scale":1.2,"files":[{"weight":400,"sha256":"${sha(
                bytes,
            )}","size":${bytes.size}}]}"""
        }
        Settings(BenbowDb(driver)).apply {
            connection = Connection("https://127.0.0.1:1", "AA", "token", "store-1", 1, "device")
            put("fonts.catalog", """{"store":"store-1","fonts":[$fonts]}""")
        }
        val shelf = Files.createTempDirectory("benbow-fonts").toFile()
        files.values.forEach { File(shelf, "${sha(it)}.ttf").writeBytes(it) }
        return AppServices(
            driver,
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
            mediaIn(Files.createTempDirectory("benbow-media").toFile()),
            "desktop",
            "Test PC",
            fontShelf = FolderFontShelf(shelf),
        )
    }
}
