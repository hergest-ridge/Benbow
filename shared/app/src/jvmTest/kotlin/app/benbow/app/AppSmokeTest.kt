package app.benbow.app

import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import app.benbow.client.GentleRule
import app.benbow.client.HomeBlock
import app.benbow.client.HomeBlockType
import app.benbow.client.HomeLayout
import app.benbow.client.Prompts
import app.benbow.client.TagChoice
import app.benbow.client.TagSearch
import app.benbow.client.Veil
import app.benbow.client.addTag
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.createCategory
import app.benbow.client.db.BenbowDb
import app.benbow.client.homeLayout
import app.benbow.client.ruleFor
import app.benbow.client.saveHomeLayout
import app.benbow.client.setTagCategory
import app.benbow.client.tagRegistry
import app.benbow.client.tags
import app.benbow.client.trashCategory
import app.benbow.client.trashTag
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.EventField
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.toDocument
import app.benbow.core.text.Mark
import app.benbow.core.text.buildRichText
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the real app (in-memory DB, not paired) through the main flows and keeps screenshots for review. */
@OptIn(ExperimentalTestApi::class)
class AppSmokeTest {
    private val services = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
        "desktop",
        "Test PC",
    ).apply { pageReader = { null } } // a shared link's card never reads a real page in tests

    /** The topmost layer: the screen, or an open dialog over it. */
    private fun SemanticsNodeInteractionsProvider.screenshot(name: String) {
        val file = File("build/screens/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }

    @Test
    fun quickNoteEditTrashRestore() = runDesktopComposeUiTest(width = 412, height = 860) {
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Как прошёл день? Пара строк — и он сохранится."))
        screenshot("01-empty")

        onNode(hasSetTextAction()).performTextInput("Купить хлеб\nи молоко")
        onNodeWithContentDescription("Сохранить заметку").performClick()
        waitUntilExactlyOneExists(hasText("Купить хлеб"), 5_000)
        screenshot("02-note")

        onNodeWithText("Купить хлеб").performClick()
        startEditing() // an entry tapped on Home reads first
        waitUntilExactlyOneExists(hasText("Сегодня", substring = true) and hasClickAction()) // the editor title: day, time, tap to move
        onNode(hasSetTextAction()).performTextInput(" и сыр")
        screenshot("03-editor")
        onNodeWithContentDescription("Назад").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.journal().first().single().preview.contains("сыр") } }
        screenshot("04-edited")

        onNodeWithText("Купить хлеб").performClick()
        onNodeWithContentDescription("Меню").performClick()
        onNodeWithText("В корзину").performClick()
        waitUntilExactlyOneExists(hasText("Как прошёл день? Пара строк — и он сохранится."), 5_000)

        onNodeWithText("Ещё").performClick()
        waitUntilExactlyOneExists(hasText("Корзина"), 5_000)
        screenshot("22-more")
        onNodeWithText("Корзина").performClick()
        waitUntilExactlyOneExists(hasText("Восстановить"), 5_000)
        screenshot("05-trash")
        onNodeWithText("Восстановить").performClick()
        waitUntilExactlyOneExists(hasText("Корзина пуста"), 5_000)
        onNodeWithContentDescription("Назад").performClick()
        onNodeWithText("Главная").performClick()
        waitUntilExactlyOneExists(hasText("Купить хлеб"), 5_000)

        onNodeWithContentDescription("Не подключено").performClick()
        waitUntilExactlyOneExists(hasText("Подключиться"))
        screenshot("06-server")
        assertEquals(1, runBlocking { services.repo.journal().first().size })
    }

    /** A picture: warm sky over a dark sea, so the screenshots show whether it renders. */
    private fun picture(width: Int = 1600, height: Int = 1200): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(0xE8, 0xA8, 0x5A)
            fillRect(0, 0, width, height * 7 / 12)
            color = Color(0x1F, 0x4E, 0x6E)
            fillRect(0, height * 7 / 12, width, height * 5 / 12)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    @Test
    fun attachedPhotoShowsInTheEntryTheJournalAndFullScreen() = runDesktopComposeUiTest(width = 412, height = 860) {
        val bytes = picture()
        val entry = services.repo.createNote(services.today(), "Закат на море")
        val image = ImageIO.read(bytes.inputStream())
        runBlocking {
            services.attach({ entry }, { bytes.inputStream() }) {
                MediaProbe("image/jpeg", "IMG_1.jpg", "image", image.width, image.height, preview = picture(512, 384))
            }.join()
        }
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Закат на море"), 5_000)
        runBlocking { delay(1_500) } // Coil decodes on its own threads, outside the test's idling
        waitForIdle()
        screenshot("07-journal-cover")

        onNodeWithText("Закат на море").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasClickAction()).fetchSemanticsNodes().size > 3 }
        runBlocking { delay(1_000) }
        waitForIdle()
        screenshot("08-editor-gallery")
        assertEquals(1, runBlocking { services.repo.entry(entry).first()!!.attachments.size })
        assertEquals(2L, runBlocking { services.media.pendingCount().first() }, "the file and its preview wait for the server")
    }

    /** Clicks through the node's action: no hit-testing against a layout that may be a frame behind. */
    private fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick)

    /** On screen: laid out inside the window (a list keeps some items composed just out of view). */
    private fun SemanticsNode.isShown() = boundsInRoot.let { it.bottom > 0f && it.top < 860f && it.height > 0f }

    /** An entry tapped in a list opens as a post to read (ADR-037); its menu switches to writing. */
    private fun ComposeUiTest.startEditing() {
        waitUntilExactlyOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        waitUntilExactlyOneExists(hasText("Редактировать"), 5_000)
        onNodeWithText("Редактировать").click()
    }

    @Test
    fun favoriteMoodTagsAndSearch() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.repo.createNote(services.today(), "Планы на неделю\nдописать поиск")
        services.repo.createNote(services.today(), "Прогулка у моря")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Планы на неделю"), 5_000)
        onNodeWithText("Планы на неделю").performClick()
        startEditing() // an entry tapped on Home reads first
        waitUntilExactlyOneExists(hasText("Настроение"), 5_000)
        onNodeWithContentDescription("В избранное").click()
        onNodeWithText("Настроение").click()
        onNodeWithText("🙂").click()
        onNodeWithText("+ Тег").click()
        waitUntilExactlyOneExists(hasSetTextAction() and hasText("Найти", substring = true), 5_000) // the picker's field
        onNode(hasSetTextAction() and hasText("Найти", substring = true)).performTextInput("Работа")
        onNodeWithText("Создать «Работа»").click()
        onNodeWithText("Готово").click()
        waitUntilExactlyOneExists(hasText("Работа"), 5_000)
        screenshot("09-editor-metadata")

        onNodeWithContentDescription("Назад").performClick()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { services.repo.journal().first().any { it.favorite && it.tags.map { tag -> tag.name } == listOf("Работа") } }
        }
        waitForIdle()
        screenshot("10-journal-metadata")

        onNodeWithText("Ещё").click() // Поиск lives in «Ещё» while «Записки» has its tab (ADR-040)
        onNodeWithText("Поиск").click()
        onNode(hasSetTextAction()).performTextInput("мор")
        waitUntilExactlyOneExists(hasText("Прогулка у моря"), 5_000)
        onNode(hasSetTextAction()).performTextClearance()
        onNodeWithText("Избранное").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Прогулка у моря")).fetchSemanticsNodes().isEmpty() }
        waitUntilExactlyOneExists(hasText("Планы на неделю"), 5_000)
        screenshot("11-search")

        onNodeWithText("Избранное").performClick()
        onNodeWithText("Тег…").click()
        waitUntilExactlyOneExists(hasText("Тег для поиска"), 5_000)
        onNode(hasSetTextAction() and hasText("Найти", substring = true)).performTextInput("hf,")
        onNode(hasSetTextAction() and hasText("hf,")).performImeAction() // «раб» in the latin layout; one tag, the picker closes
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Тег…").fetchSemanticsNodes().isEmpty() }
        // Only entries with the tag.
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Прогулка у моря").fetchSemanticsNodes().isEmpty() }
        waitUntilExactlyOneExists(hasText("Планы на неделю"), 5_000)
    }

    @Test
    fun theTagPickerFindsForgivinglyAndPutsTagsOn() = runDesktopComposeUiTest(width = 412, height = 860) {
        val walk = services.repo.createNote(services.today(), "Прогулка у моря")
        services.repo.setTagCategory(services.repo.addTag(walk, "Море")!!, "места")
        val lunch = services.repo.createNote("2026-01-05", "Обед в городе")
        services.repo.addTag(lunch, "Мария")
        services.repo.addTag(lunch, "Кафе")
        val plans = services.repo.createNote(services.today(), "Планы")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Планы"), 5_000)
        onNodeWithText("Планы").performClick()
        startEditing() // an entry tapped on Home reads first
        waitUntilExactlyOneExists(hasText("+ Тег"), 5_000)
        onNodeWithText("+ Тег").click()
        val field = hasSetTextAction() and hasText("Найти", substring = true)
        waitUntilExactlyOneExists(hasText("Недавние"), 5_000)
        screenshot("09a-tag-picker")

        onNode(field).performTextInput("vjht") // «море» in the latin layout
        waitUntilExactlyOneExists(hasText("Море") and hasClickAction(), 5_000)
        onNode(hasText("Море") and hasClickAction()).click()
        onNode(field).performTextInput("мари")
        waitUntilExactlyOneExists(hasText("Мария") and hasClickAction(), 5_000)
        onNode(hasSetTextAction() and hasText("мари")).performImeAction() // Enter takes the first line
        waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                services.repo.entry(plans).first()!!.tags.map { it.name } ==
                    listOf("Мария", "Море")
            }
        }
        waitUntilExactlyOneExists(hasText("Часто вместе"), 5_000) // Кафе goes with Мария
        screenshot("09b-tag-picker-chosen")

        onNode(field).performTextInput("Отпуск")
        onNodeWithText("Проекты").click()
        screenshot("09c-tag-picker-create")
        onNodeWithText("Создать «Отпуск»").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.entry(plans).first()!!.tags.size == 3 } }
        val holiday = runBlocking { services.repo.entry(plans).first()!!.tags.single { it.name == "Отпуск" } }
        assertEquals("проекты", runBlocking { services.repo.tagRegistry().first().category(holiday.id) })
        onNodeWithText("Готово").click()
        waitUntilExactlyOneExists(hasText("Отпуск"), 5_000)
    }

    @Test
    fun homeAndFeed() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.repo.createNote("2025-" + services.today().substring(5), "Год назад тоже писал")
        val note = services.repo.createNote(services.today(), "Утренняя заметка\nвторая строка")
        services.repo.toggleReaction(note, "🔥", on = true)
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Утренняя заметка"), 5_000)
        waitUntilExactlyOneExists(hasText("В этот день"), 5_000)
        screenshot("12-home")

        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("вторая строка"), 5_000) // each paragraph its own line, as in the post
        waitUntilExactlyOneExists(hasText("🔥"), 5_000)
        screenshot("13-feed")

        onNodeWithText("вторая строка").click()
        waitUntilExactlyOneExists(hasText("Пока без комментариев", substring = true), 5_000)
        onNode(hasSetTextAction()).performTextInput("Перечитал вечером") // only the reply box takes text while reading
        onNodeWithContentDescription("Сохранить заметку").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.commentCount(note).first() } == 1L }
        waitUntilExactlyOneExists(hasText("Перечитал вечером"), 5_000)
        screenshot("23-post")
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Редактировать").click()
        waitUntilExactlyOneExists(hasText("Настроение"), 5_000)
    }

    /** Two tagged tracks in a post and one in an older post (ADR-035), as the feed, the mini-player and the player show them. */
    private fun postSounds() = runBlocking {
        val older = services.repo.createNote(services.today(), "Вчерашнее")
        val post = services.repo.createNote(services.today(), "Плейлист на вечер")
        val sleeve = TestSounds.jpeg(1200, 1200, top = Color(0xC0, 0x39, 0x2B), bottom = Color(0x1A, 0x1A, 0x2E))
        val tracks = listOf(
            older to TestSounds.mp3("Спокойная ночь", "Кино", TestSounds.jpeg(800, 800)),
            post to TestSounds.mp3("Звезда по имени Солнце", "Кино", sleeve, album = "Звезда по имени Солнце"),
            post to TestSounds.mp3("Кукушка", "Кино", sleeve),
        )
        for ((entry, bytes) in tracks) {
            services.attach({ entry }, { bytes.inputStream() }) { MediaProbe("audio/mpeg", "track.mp3", "audio") }.join()
        }
    }

    @Test
    fun soundsPlayFromTheFeedThroughTheMiniPlayerAndThePlayer() = runDesktopComposeUiTest(width = 412, height = 860) {
        postSounds()
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("Кукушка"), 5_000)
        runBlocking { delay(1_000) } // covers decode off the test's idling
        waitForIdle()
        screenshot("70-feed-sounds")

        onAllNodesWithContentDescription("Слушать")[0].click()
        waitUntilExactlyOneExists(hasContentDescription("Следующий трек"), 5_000)
        assertEquals("Звезда по имени Солнце", services.player.state.value?.track?.title)
        assertEquals(listOf("Звезда по имени Солнце", "Кукушка", "Спокойная ночь"), services.player.state.value?.queue?.map { it.title })
        waitForIdle()
        screenshot("71-mini-player")

        services.player.playPause()
        waitUntilExactlyOneExists(hasContentDescription("Закрыть плеер"), 5_000)
        screenshot("72-mini-paused")

        onAllNodesWithText("Звезда по имени Солнце", substring = false).onLast().click()
        waitUntilExactlyOneExists(hasText("Сейчас играет"), 5_000)
        runBlocking { delay(1_500) } // the cover's colors are counted off the main thread
        waitForIdle()
        screenshot("73-player")
        onNodeWithContentDescription("Следующий трек").click()
        waitUntil(timeoutMillis = 5_000) { services.player.state.value?.track?.title == "Кукушка" }
        onNodeWithContentDescription("Свернуть").click()
        waitUntilDoesNotExist(hasText("Сейчас играет"), 5_000)
        services.player.playPause()
        waitUntilExactlyOneExists(hasContentDescription("Закрыть плеер"), 5_000)
        onNodeWithContentDescription("Закрыть плеер").click()
        waitUntil(timeoutMillis = 5_000) { services.player.state.value == null }
    }

    @Test
    fun aWideWindowShowsThePlayerAsADialog() = runDesktopComposeUiTest(width = 1280, height = 800) {
        postSounds()
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("Кукушка"), 5_000)
        onAllNodesWithContentDescription("Слушать")[1].click()
        waitUntilExactlyOneExists(hasContentDescription("Следующий трек"), 5_000)
        onAllNodesWithText("Кукушка").onLast().click()
        // The dialog's first frame can take a while when the whole gate runs at once.
        waitUntilExactlyOneExists(hasText("Сейчас играет"), 10_000)
        runBlocking { delay(1_500) }
        waitForIdle()
        screenshot("74-player-wide")
    }

    @Test
    fun spoilersHideUntilATapThatOpensThemNotThePost() = runDesktopComposeUiTest(width = 412, height = 860) {
        val rich = buildRichText {
            paragraph { text("Разговор с Олей") }
            paragraph {
                text("Она сказала, что ")
                text("диагноз подтвердили", Mark.Spoiler)
                text(", но держится.")
            }
            paragraph { text("Вечером гуляли у моря") }
        }
        val post = services.repo.createEntry(services.today(), kind = "note", body = DocumentCodec.encode(rich.toDocument()))
        // A reply with a spoiler of its own: hidden in the thread too, not dropped.
        val reply = buildRichText {
            paragraph {
                text("Перечитал: ")
                text("было страшно", Mark.Spoiler)
            }
        }
        val comment = services.repo.createEntry(services.today(), kind = "comment", body = DocumentCodec.encode(reply.toDocument()))
        services.repo.setField(comment, "parent", post)
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("Вечером гуляли у моря"), 5_000)
        screenshot("24-spoiler-feed")

        // A tap on the hidden words opens them in place; the post stays closed.
        val line = onNodeWithText("Она сказала", substring = true, useUnmergedTree = true) // the line, not the whole card
        val bounds = line.fetchSemanticsNode().layoutInfo.coordinates.size
        mainClock.autoAdvance = false
        line.performTouchInput { click(Offset(bounds.width * 0.6f, bounds.height * 0.25f)) } // the first of its two lines
        mainClock.advanceTimeBy(250)
        screenshot("25a-spoiler-opening") // a circle grows from the tap
        mainClock.autoAdvance = true
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()) // no reply box: still the feed
        screenshot("25-spoiler-open")

        onNodeWithText("Вечером гуляли у моря").click()
        waitUntilExactlyOneExists(hasText("Перечитал", substring = true), 5_000) // the reply, its spoiler hidden
        screenshot("26-spoiler-post") // hidden again: a new visit
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Редактировать").click()
        waitUntilExactlyOneExists(hasText("Настроение"), 5_000)
        screenshot("27-spoiler-editor") // frosted, readable
        onNode(hasSetTextAction()).performTextInputSelection(TextRange(38)) // inside "диаг|ноз"
        waitForIdle()
        screenshot("27b-spoiler-cursor") // clear where the cursor is
    }

    @Test
    fun curtainsAndQuietEntriesStayOutOfTheWayUntilTurnedOff() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.repo.createNote(services.today(), "Обычный день у моря")
        services.repo.createNote(services.today(), "Тяжёлый разговор с врачом")
        val quiet = services.repo.createNote(services.today(), "Совсем тяжёлое")
        services.repo.setVeil(quiet, Veil.Quiet)
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("Обычный день у моря"), 5_000)
        assertTrue(onAllNodesWithText("Совсем тяжёлое").fetchSemanticsNodes().isEmpty()) // quiet: no trace in the feed

        // A curtain from the post's long-press menu, with a way back.
        onNodeWithText("Тяжёлый разговор с врачом").performTouchInput { longClick() }
        onNodeWithText("Задёрнуть шторку").click()
        waitUntilExactlyOneExists(hasText("Вернуть"), 5_000)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Тяжёлый разговор", substring = true).fetchSemanticsNodes().isEmpty() }
        screenshot("28-gentle-feed")
        onNodeWithText("Открыть", substring = true).click() // opens in place, not the post
        waitUntilExactlyOneExists(hasText("Тяжёлый разговор с врачом"), 5_000)
        screenshot("29-gentle-curtain-open")

        // The switch stays out of sight until the title is clicked (with a mouse); off, the quiet entry is back.
        assertTrue(onAllNodesWithText("Бережно").fetchSemanticsNodes().isEmpty())
        onNodeWithText("Моя лента").click()
        waitUntilExactlyOneExists(isToggleable(), 5_000)
        onNode(isToggleable()).click()
        waitUntilExactlyOneExists(hasText("Совсем тяжёлое"), 5_000)
        screenshot("30-gentle-off")
        onNode(isToggleable()).click()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Совсем тяжёлое").fetchSemanticsNodes().isEmpty() }

        // Looking for it on purpose finds it, under a curtain.
        onNodeWithText("Ещё").click()
        onNodeWithText("Поиск").click()
        onNode(hasSetTextAction()).performTextInput("тяжёлое")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Открыть", substring = true).fetchSemanticsNodes().size == 2 }
        assertTrue(onAllNodesWithText("Совсем тяжёлое").fetchSemanticsNodes().isEmpty())
        screenshot("31-gentle-search")
    }

    @Test
    fun aRuleByTagKeepsItsEntriesOutOfTheWayAndOpenOnPurpose() = runDesktopComposeUiTest(width = 412, height = 860) {
        val call = services.repo.createNote(services.today(), "Звонок из больницы")
        services.repo.addTag(call, "Больница")
        val old = services.repo.createNote("2025-01-10", "Опять в больнице")
        services.repo.addTag(old, "Больница")
        services.repo.createNote(services.today(), "Обычный вечер")
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Бережно").click()
        waitUntilExactlyOneExists(hasText("Добавить правило"), 5_000)
        onNodeWithText("Добавить правило").click()
        onNodeWithText("Больница").click() // the tag, in the new rule's dialog
        waitUntilExactlyOneExists(hasText("2 записи"), 5_000)
        onNodeWithText("Тихо").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.gentleRules().first().rules.single().level == "quiet" } }
        waitForIdle()
        screenshot("33-gentle-rules")

        onNodeWithContentDescription("Назад").click()
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("Обычный вечер"), 5_000)
        assertTrue(onAllNodesWithText("больниц", substring = true).fetchSemanticsNodes().isEmpty()) // both quiet now

        // Coming back on purpose: the rule's own list, nothing veiled.
        onNodeWithText("Ещё").click()
        onNodeWithText("Бережно").click()
        onNodeWithText("Открыть").click()
        waitUntilExactlyOneExists(hasText("Звонок из больницы"), 5_000)
        waitUntilExactlyOneExists(hasText("Опять в больнице"), 5_000)
        screenshot("34-gentle-rule-entries")

        // A period: its days and a name in one dialog.
        onNodeWithContentDescription("Назад").click()
        onNodeWithText("Добавить правило").click()
        onNodeWithText("Выбрать даты").click()
        waitUntilExactlyOneExists(hasText("Название", substring = true), 5_000)
        screenshot("35-gentle-period")
        onNodeWithText("Отмена").click()
    }

    @Test
    fun aPutOffEntryLeavesTheFeedAndComesBackWithACard() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = services.repo.createNote(services.today(), "Письмо, которое пока не перечитать")
        services.repo.createNote(services.today(), "Утро как утро")
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("Письмо, которое пока не перечитать"), 5_000)
        onNodeWithText("Письмо, которое пока не перечитать").performTouchInput { longClick() }
        onNodeWithText("Отложить до…").click()
        onNodeWithText("На месяц").click()
        waitUntilExactlyOneExists(hasText("Запись вернётся", substring = true), 5_000)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Письмо", substring = true).fetchSemanticsNodes().isEmpty() }
        screenshot("36-gentle-postponed")

        // Its day comes: back in its place, and Home says so once.
        services.repo.setVeilUntil(id, services.today())
        onNodeWithText("Главная").click()
        waitUntilExactlyOneExists(hasText("Вернулась запись от", substring = true), 5_000)
        screenshot("37-gentle-returned")
        onNodeWithText("Не сейчас").click()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Вернулась запись от", substring = true).fetchSemanticsNodes().isEmpty() }
        waitUntilExactlyOneExists(hasText("Письмо, которое пока не перечитать"), 5_000) // in today's list again
    }

    @Test
    fun onAPhoneTheSwitchWaitsAboveTheFeedForAPull() = runDesktopComposeUiTest(width = 412, height = 860) {
        val phone = AppServices(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
            mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
            "android",
            "Test phone",
        )
        phone.repo.createNote(phone.today(), "Утро у моря")
        setContent { BenbowApp(phone) }
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("Утро у моря"), 5_000)
        waitForIdle()
        screenshot("32a-gentle-hidden")
        assertFalse(onAllNodesWithText("Бережно").fetchSemanticsNodes().any { it.isShown() })
        onNodeWithText("Утро у моря").performTouchInput { swipeDown(startY = centerY, endY = centerY + 500f, durationMillis = 800) }
        waitForIdle()
        onNodeWithText("Бережно").assertIsDisplayed()
        screenshot("32-gentle-pulled")
    }

    @Test
    fun commentsAndTagCategories() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Обед с Машей")
        val masha = services.repo.addTag(entry, "Маша")!!
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Обед с Машей"), 5_000)
        onNodeWithText("Обед с Машей").click()
        startEditing() // an entry tapped on Home reads first
        waitUntilExactlyOneExists(hasText("Маша"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Комментарии").click()
        waitUntilExactlyOneExists(hasText("Комментарий…"), 5_000)
        onNode(hasSetTextAction()).performTextInput("Было очень вкусно")
        onNodeWithContentDescription("Сохранить заметку").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.commentCount(entry).first() } == 1L }
        waitUntilExactlyOneExists(hasText("Было очень вкусно"), 5_000)
        screenshot("14-comments")

        onNodeWithContentDescription("Назад").click()
        waitUntilExactlyOneExists(hasText("Маша"), 5_000)
        onNodeWithContentDescription("Назад").click()
        waitUntilExactlyOneExists(hasText("Добр", substring = true), 5_000)
        onNodeWithText("Ещё").click()
        onNodeWithText("Теги").click()
        waitUntilExactlyOneExists(hasText("Маша"), 5_000)
        screenshot("15-tags")
        onNodeWithText("Маша").click() // its page
        waitUntilExactlyOneExists(hasText("Обед с Машей"), 5_000)
        onNodeWithContentDescription("Изменить").click()
        waitUntilExactlyOneExists(hasText("Как у категории"), 5_000)
        onNodeWithText("Люди").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.tags().first().single().category == "люди" } }
        onNodeWithContentDescription("Коралловый").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.tagRegistry().first().color(masha) == "coral" } }
        waitForIdle()
        screenshot("15d-tag-editor")
        assertEquals(1L, runBlocking { services.repo.commentCount(entry).first() })
    }

    @Test
    fun tagsAreFoundChosenEditedAndOpenFromTheirChips() = runDesktopComposeUiTest(width = 412, height = 860) {
        val walk = services.repo.createNote(services.today(), "Вечер у моря")
        val sea = services.repo.addTag(walk, "Море")!!
        services.repo.addTag(services.repo.createNote("2026-08-01", "Шторм"), "Море")
        val trip = services.repo.addTag(services.repo.createNote("2026-07-01", "Билеты"), "Отпуск")!!
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Вечер у моря"), 5_000)
        onNodeWithText("Море").click() // the chip on the card opens the tag's page, not the entry
        waitUntilExactlyOneExists(hasText("Шторм"), 5_000)
        screenshot("15c-tag-page")
        onNodeWithContentDescription("Изменить").click()
        waitUntilExactlyOneExists(hasText("Как у категории"), 5_000)
        onNode(hasSetTextAction() and hasText("Описание", substring = true)).performTextInput("Всё про воду")
        onNodeWithText("Шторка").click()
        onNodeWithText("Готово").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.tagRegistry().first().description(sea) == "Всё про воду" } }
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { services.repo.gentleRules().first().ruleFor(GentleRule.TAG, sea)?.level == GentleRule.CURTAIN }
        }
        onNodeWithContentDescription("Назад").click()

        onNodeWithText("Ещё").click()
        onNodeWithText("Теги").click()
        waitUntilExactlyOneExists(hasText("Отпуск"), 5_000)
        onNode(hasSetTextAction()).performTextInput("jngecr") // «отпуск» in the latin layout
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Море").fetchSemanticsNodes().isEmpty() }
        onNode(hasSetTextAction()).performTextClearance()
        waitUntilExactlyOneExists(hasText("Море"), 5_000)
        onNodeWithText("Отпуск").performTouchInput { longClick() }
        waitUntilExactlyOneExists(hasText("Выбрано: 1"), 5_000)
        screenshot("15e-tags-chosen")
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("В категорию").click()
        onNode(hasText("Места") and hasAnyAncestor(isDialog())).click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.tagRegistry().first().category(trip) == "места" } }

        onNodeWithContentDescription("Новый тег").click()
        onNodeWithText("Новая категория").click()
        onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Спорт")
        onNodeWithText("Готово").click()
        waitUntilExactlyOneExists(hasText("Эмодзи"), 5_000) // its editor
        onNode(hasSetTextAction() and hasText("Эмодзи")).performTextInput("🏃")
        screenshot("15f-category-editor")
        onNodeWithText("Готово").click()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { services.repo.tagRegistry().first().categoryEmoji.values.toList() == listOf("🏃") }
        }
        waitUntilExactlyOneExists(hasText("🏃 Спорт"), 5_000)
        screenshot("15b-tags-screen")
    }

    @Test
    fun aTagAndACategoryComeBackFromTheTrash() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Обед с Машей")
        val masha = services.repo.addTag(entry, "Маша")!!
        val friends = services.repo.createCategory("Друзья")!!
        services.repo.trashTag(masha)
        services.repo.trashCategory(friends)
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Обед с Машей"), 5_000)
        onNodeWithText("Ещё").click()
        onNodeWithText("Корзина").click()
        waitUntilExactlyOneExists(hasText("Тег «Маша»"), 5_000)
        onNodeWithText("Категория «Друзья»").assertIsDisplayed()
        screenshot("15a-trash-tags")
        onAllNodesWithText("Восстановить")[0].click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.journal().first().single().tags.map { it.name } == listOf("Маша") } }
        waitUntilExactlyOneExists(hasText("Восстановить"), 5_000)
        onNodeWithText("Восстановить").click()
        waitUntilExactlyOneExists(hasText("Корзина пуста"), 5_000)
        assertEquals("Друзья", runBlocking { services.repo.tagRegistry().first().categories.last().name })
    }

    @Test
    fun aGoogleExportImportsWithAPreviewAndCanBeUndone() = runDesktopComposeUiTest(width = 412, height = 860) {
        val today = services.today()
        val ics = { name: String, events: String -> "BEGIN:VCALENDAR\r\nX-WR-CALNAME:$name\r\n$events\r\nEND:VCALENDAR" }
        val work = ics(
            "Работа",
            (1..12).joinToString("\r\n") {
                "BEGIN:VEVENT\r\nUID:w$it\r\nSUMMARY:Созвон $it\r\nDTSTART:202610${it + 10}T100000Z\r\nEND:VEVENT"
            },
        )
        val birthdays =
            ics("Годовщины", "BEGIN:VEVENT\r\nUID:b1\r\nSUMMARY:Свадьба\r\nDTSTART;VALUE=DATE:20151103\r\nRRULE:FREQ=YEARLY\r\nEND:VEVENT")
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                for ((name, text) in listOf("work.ics" to work, "anniversaries.ics" to birthdays)) {
                    z.putNextEntry(java.util.zip.ZipEntry(name))
                    z.write(text.encodeToByteArray())
                }
            }
        }.toByteArray()
        setContent { BenbowApp(services) }
        services.importCalendarFile("calendar-export.zip") { zip.inputStream() }
        waitUntilExactlyOneExists(hasText("Годовщины"), 5_000)
        screenshot("17-import-preview")
        onNodeWithText("Перенести 13 событий").click()
        waitUntilExactlyOneExists(hasText("Отменить импорт"), 5_000)
        screenshot("17b-import-done")
        assertEquals(listOf("Годовщины", "Работа"), runBlocking { services.calendar.calendars().first().drop(1).mapNotNull { it.name } })
        onNodeWithText("Отменить импорт").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.calendar.calendars().first().size == 1 } }
        assertEquals(today, services.today())
    }

    @Test
    fun calendarShowsTheMonthAndTakesAnEvent() = runDesktopComposeUiTest(width = 412, height = 860) {
        val today = services.today()
        val day = { shift: Int -> LocalDate.parse(today).plus(shift, DateTimeUnit.DAY).toString() }
        val good = services.repo.createNote(today, "Хороший день")
        services.repo.setMood(good, 2)
        val work = services.calendar.createCalendar("Работа", "storm")
        val trip = services.calendar.createEvent(day(2), "Командировка", null, calendar = work)
        services.calendar.put(CalendarKind.EVENT, trip, mapOf(EventField.END to day(5)))
        val yoga = services.calendar.createEvent(day(-7), "Йога", "19:00")
        services.calendar.put(CalendarKind.EVENT, yoga, mapOf(EventField.RRULE to "FREQ=WEEKLY"))
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Хороший день"), 5_000)
        onNodeWithText("Календарь").click()
        waitUntilExactlyOneExists(hasText("+ Событие"), 5_000)
        onNodeWithText("+ Событие").click()
        waitUntilExactlyOneExists(hasText("Новое событие"), 5_000)
        onAllNodes(hasSetTextAction())[0].performTextInput("Врач")
        onAllNodes(hasSetTextAction())[1].performTextInput("9:30")
        screenshot("16a-new-event")
        onNodeWithText("Добавить").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.calendar.events(today, today).first().size == 2 } }
        waitUntilExactlyOneExists(hasText("09:30"), 5_000)
        waitForIdle()
        screenshot("16-calendar")

        // The event lives in the calendar's own file (ADR-027): it opens in its sheet, not as a journal entry.
        onNodeWithText("Врач").click()
        waitUntilExactlyOneExists(hasText("Сохранить"), 5_000)
        screenshot("16b-event")
        onAllNodes(hasSetTextAction() and hasText("Врач"))[0].performTextReplacement("Стоматолог")
        onNodeWithText("Сохранить").click()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { services.calendar.events(today, today).first().any { it.title == "Стоматолог" } }
        }
        assertEquals(listOf("Хороший день"), runBlocking { services.repo.journal().first().map { it.title } })

        // "Написать об этом": a new journal entry headed by the event's title.
        waitUntilExactlyOneExists(hasText("Стоматолог"), 5_000)
        onNodeWithText("Стоматолог").click()
        waitUntilExactlyOneExists(hasText("Написать об этом"), 5_000)
        onNodeWithText("Написать об этом").click()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasSetTextAction() and hasText("Стоматолог", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("16d-write")
        onNodeWithContentDescription("Назад").click()

        // The calendars: a checkbox hides one on the grid.
        waitUntilExactlyOneExists(hasContentDescription("Календари"), 5_000)
        onNodeWithContentDescription("Календари").click()
        waitUntilExactlyOneExists(hasText("Работа"), 5_000)
        screenshot("16c-calendars")
        onAllNodes(isToggleable())[1].click()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { services.calendar.events(day(2), day(5)).first().none { it.title == "Командировка" } }
        }
    }

    @Test
    fun anEveningNudgeIsOneTap() = runDesktopComposeUiTest(width = 412, height = 860) {
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Добр", substring = true), 5_000)
        onNodeWithText("Ещё").click()
        onNodeWithText("Напоминания").click()
        waitUntilExactlyOneExists(hasText("Каждый вечер в 21:00", substring = true), 5_000)
        onNodeWithText("Каждый вечер в 21:00", substring = true).click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.reminders().first().size == 1 } }
        waitUntilExactlyOneExists(hasText("Пара строк о сегодняшнем дне?"), 5_000)
        screenshot("17-reminders")
    }

    @Test
    fun homeFollowsTheOwnersLayout() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.repo.createNote("2025-" + services.today().substring(5), "Год назад тоже писал")
        val default = runBlocking { services.repo.homeLayout().first() }
        // Looking back first, and no question of the day.
        services.repo.saveHomeLayout(default, default.blocks.sortedByDescending { it.id == "on-this-day" }.filter { it.id != "prompt" })
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Год назад тоже писал"), 5_000)
        onNodeWithText(Prompts.ofTheDay(services.today())).assertDoesNotExist()
        assertTrue(onNodeWithText("В этот день").getBoundsInRoot().top < onNodeWithText("Как сейчас дела?").getBoundsInRoot().top)
        screenshot("12b-home-layout")
    }

    @Test
    fun aTagBlockShowsItsPicturesAndOpensTheSameInSearch() = runDesktopComposeUiTest(width = 412, height = 860) {
        // Weeks back, so neither the day nor On this day shows them (with their tag chips) on Home too.
        val sea = (1..4).map {
            services.repo.createNote(LocalDate.parse(services.today()).minus(40 + it, DateTimeUnit.DAY).toString(), "Море $it")
        }
        val tag = services.repo.addTag(sea[0], "Море")!!
        sea.drop(1).forEach { services.repo.addTag(it, "Море") }
        runBlocking {
            sea.take(3).forEach { entry ->
                val bytes = picture(480, 360)
                services.attach({ entry }, { bytes.inputStream() }) { MediaProbe("image/jpeg", "$entry.jpg", "image", 480, 360) }.join()
            }
        }
        val default = runBlocking { services.repo.homeLayout().first() }
        services.repo.saveHomeLayout(default, listOf(HomeBlock("sea", "tag", "l", mapOf(HomeBlock.TAG to tag))) + default.blocks)
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Море"), 5_000)
        onNodeWithText("3").assertExists() // the pictures only: the entry without one is search's too, not the gallery's
        runBlocking { delay(1_500) } // Coil decodes on its own threads
        waitForIdle()
        screenshot("12f-home-tag-block")

        onNodeWithText("Море").click()
        waitUntil(timeoutMillis = 5_000) {
            services.searchFor.value == null &&
                onAllNodesWithText("С вложениями").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(onAllNodesWithText("Море 4").fetchSemanticsNodes().isEmpty(), "the same selection, as a grid of pictures")
        runBlocking { delay(1_000) }
        waitForIdle()
        screenshot("12g-search-grid")

        onNodeWithText("Главная").click()
        waitUntilExactlyOneExists(hasText("Море"), 5_000)
        onNodeWithText("Море").performTouchInput { longClick() }
        onNode(hasText("Настроить главную") and hasAnyAncestor(isPopup())).click()
        waitUntilExactlyOneExists(hasText("Тег «Море»"), 5_000)
        onNodeWithText("Малый").click()
        onNodeWithText("Случайные на сегодня").click()
        val block = { runBlocking { services.repo.homeLayout().first().blocks.find { it.id == "sea" } } }
        waitUntil(timeoutMillis = 5_000) { block()?.size == "s" && block()?.config?.get(HomeBlock.ORDER) == HomeBlock.RANDOM }
        screenshot("12h-home-editor-tag")

        // Another tag block from the catalog: the tag picker comes first.
        onNodeWithText("Добавить блок").click()
        onNode(hasText("Записи с тегом сеточкой", substring = true) and hasClickAction()).click()
        val field = hasSetTextAction() and hasText("Найти", substring = true)
        waitUntilExactlyOneExists(field, 5_000)
        onNode(field).performTextInput("мор")
        onNode(hasSetTextAction() and hasText("мор")).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { services.repo.homeLayout().first().blocks.count { it.type == "tag" && it.config[HomeBlock.TAG] == tag } } == 2
        }
    }

    @Test
    fun theStatsBlockCountsUpAndShowsWhatTheOwnerPicks() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.repo.createNote(services.today(), "Сегодня утром")
        services.repo.createNote(LocalDate.parse(services.today()).minus(400, DateTimeUnit.DAY).toString(), "Давно")
        val walk = services.repo.createNote(services.today(), "Прогулка")
        val tag = services.repo.addTag(walk, "Море")!!
        val default = runBlocking { services.repo.homeLayout().first() }
        services.repo.saveHomeLayout(
            default,
            listOf(
                HomeBlock.of(HomeBlockType.Stats),
                HomeBlock("pair-tag", "tag", "s", mapOf(HomeBlock.TAG to tag)),
                HomeBlock("pair-stats", "stats", "s", mapOf(HomeBlock.FIGURES to "since")),
                HomeBlock.of(HomeBlockType.Checkin),
            ),
        )
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("день в этом месяце"), 5_000)
        onNodeWithText("записи за месяц").assertExists()
        onNodeWithText("фото и видео за месяц").assertDoesNotExist() // a zero is left out
        onNodeWithText("401").assertExists()
        onNodeWithText("день с первой записи").assertExists()
        screenshot("12i-home-stats")

        onNodeWithText("день в этом месяце").performTouchInput { longClick() }
        onNode(hasText("Настроить главную") and hasAnyAncestor(isPopup())).click()
        waitUntilExactlyOneExists(hasText("До трёх цифр"), 5_000)
        onNodeWithText("Частые теги").click()
        val figures = { runBlocking { services.repo.homeLayout().first().blocks.first().config[HomeBlock.FIGURES] } }
        waitUntil(timeoutMillis = 5_000) { figures() == "moments,pictures,tags" }
        onNodeWithText("Малый").click()
        waitUntilExactlyOneExists(hasText("Какую цифру показывать"), 5_000)
        screenshot("12j-home-editor-stats")
    }

    @Test
    fun lookingBackComesInSizes() = runDesktopComposeUiTest(width = 412, height = 860) {
        val today = LocalDate.parse(services.today())
        val year = services.repo.createNote(today.minus(1, DateTimeUnit.YEAR).toString(), "Год назад у моря")
        services.repo.createNote(today.minus(1, DateTimeUnit.MONTH).toString(), "Месяц назад в лесу")
        services.repo.createNote(today.minus(6, DateTimeUnit.MONTH).toString(), "Полгода назад в горах")
        val favorite = services.repo.createNote(today.minus(70, DateTimeUnit.DAY).toString(), "Любимый вечер")
        services.repo.setFavorite(favorite, true)
        runBlocking {
            val bytes = picture(800, 600)
            services.attach({ year }, { bytes.inputStream() }) { MediaProbe("image/jpeg", "year.jpg", "image", 800, 600) }.join()
        }
        val default = runBlocking { services.repo.homeLayout().first() }
        services.repo.saveHomeLayout(
            default,
            listOf(
                HomeBlock("otd", "on-this-day", "m", mapOf(HomeBlock.ALSO to HomeBlock.MONTHS)),
                HomeBlock("favorite", "favorite", "s"),
                HomeBlock("stats", "stats", "s", mapOf(HomeBlock.FIGURES to "since")),
            ),
        )
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Месяц назад"), 5_000)
        onNodeWithText("Полгода назад").assertExists() // nearest first; a year ago waits past the edge of the strip
        onNodeWithText("Из избранного").assertExists()
        runBlocking { delay(1_500) } // Coil decodes on its own threads
        waitForIdle()
        screenshot("12k-home-look-back")

        // Large, and only years ago again.
        val now = runBlocking { services.repo.homeLayout().first() }
        services.repo.saveHomeLayout(now, listOf(HomeBlock("otd", "on-this-day", "l")))
        waitUntilDoesNotExist(hasText("Месяц назад"), 5_000)
        waitUntilExactlyOneExists(hasText("1 год назад"), 5_000)
        runBlocking { delay(1_500) }
        waitForIdle()
        screenshot("12l-home-on-this-day-large")
    }

    @Test
    fun aWideHomeLaysBlocksSideBySide() = runDesktopComposeUiTest(width = 820, height = 860) {
        val today = LocalDate.parse(services.today())
        services.repo.createNote(services.today(), "Утро у окна")
        val evening = services.repo.createNote(today.minus(60, DateTimeUnit.DAY).toString(), "Тот самый вечер")
        services.repo.setFavorite(evening, true)
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Как сейчас дела?"), 5_000)
        val checkin = onNodeWithText("Как сейчас дела?").getBoundsInRoot()
        val prompt = onNodeWithText(Prompts.ofTheDay(services.today())).getBoundsInRoot()
        assertTrue(prompt.left > checkin.right && prompt.top < checkin.bottom, "two blocks of M share a row")
        screenshot("12m-home-wide")
    }

    @Test
    fun theOwnerArrangesHome() = runDesktopComposeUiTest(width = 412, height = 860) {
        val ids = { runBlocking { services.repo.homeLayout().first().blocks.map { it.id } } }
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Настроить главную"), 5_000)
        onNodeWithText("Настроить главную").click()
        waitUntilExactlyOneExists(hasText("Блоки идут сверху вниз", substring = true), 5_000)
        onNodeWithText("Вопрос дня").click()
        waitUntilExactlyOneExists(hasText("Вопрос дня, чтобы было с чего начать"), 5_000)
        screenshot("12c-home-editor")

        onNodeWithContentDescription("Ниже").click()
        waitUntil(timeoutMillis = 5_000) { ids().take(3) == listOf("checkin", "pinned", "prompt") }
        onNodeWithText("Убрать").click()
        waitUntil(timeoutMillis = 5_000) { "prompt" !in ids() }
        waitUntilExactlyOneExists(hasText("Блок «Вопрос дня» убран"), 5_000)
        onNodeWithText("Вернуть").click()
        waitUntil(timeoutMillis = 5_000) { ids().take(3) == listOf("checkin", "pinned", "prompt") }

        // An empty block says when it comes; one taken off waits in the catalog.
        onNodeWithText("Закреплено").click()
        waitUntilExactlyOneExists(hasText("Появится, когда вы закрепите запись"), 5_000)
        onNodeWithText("Убрать").click()
        waitUntil(timeoutMillis = 5_000) { "pinned" !in ids() }
        onNodeWithText("Добавить блок").click()
        waitUntilExactlyOneExists(hasText("Записи, закреплённые наверху"), 5_000)
        screenshot("12d-home-catalog")
        onNodeWithText("Записи, закреплённые наверху").click()
        waitUntil(timeoutMillis = 5_000) { ids().last() == "pinned" }

        onNodeWithText("Вернуть обычную главную").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.homeLayout().first() } == HomeLayout.DEFAULT }
        onNodeWithText("Готово").click()
        waitUntilDoesNotExist(hasText("Блоки идут сверху вниз", substring = true), 5_000)

        // A block's long press: off Home, and back from the snackbar.
        onNodeWithText("Как сейчас дела?").performTouchInput { longClick() }
        waitUntilExactlyOneExists(hasText("Убрать с главной"), 5_000)
        screenshot("12e-home-block-menu")
        onNodeWithText("Убрать с главной").click()
        waitUntil(timeoutMillis = 5_000) { "checkin" !in ids() }
        waitUntilDoesNotExist(hasText("Как сейчас дела?"), 5_000)
        onNodeWithText("Вернуть").click()
        waitUntilExactlyOneExists(hasText("Как сейчас дела?"), 5_000)
        assertEquals("checkin", ids().first())
    }

    @Test
    fun questionOfTheDayAndTemplates() = runDesktopComposeUiTest(width = 412, height = 860) {
        val prompt = Prompts.ofTheDay(services.today())
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText(prompt), 5_000)
        screenshot("18-home-prompt")
        onNodeWithText("Ответить").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.journal().first().any { it.title == prompt } } }
        onNodeWithContentDescription("Назад").click()

        waitUntilExactlyOneExists(hasContentDescription("Новая запись"), 5_000)
        onNodeWithContentDescription("Новая запись").click()
        waitUntilExactlyOneExists(hasText("Три хороших события"), 5_000)
        onNodeWithText("Три хороших события").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.journal().first().any { it.title == "Три хороших события" } } }
        waitForIdle()
        screenshot("19-template")
    }

    @Test
    fun aHardCheckinOffersKindness() = runDesktopComposeUiTest(width = 412, height = 860) {
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Как сейчас дела?"), 5_000)
        onNodeWithText("🙁").click()
        waitUntilExactlyOneExists(hasText("усталость"), 5_000)
        onNodeWithText("усталость").click()
        onNodeWithText("тревога").click()
        waitForIdle()
        screenshot("20-checkin")
        onNodeWithText("Отметить").click()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                services.repo.journal().first().any {
                    it.mood == -1 &&
                        "усталость" in it.tags.map { tag -> tag.name }
                }
            }
        }
        waitUntilExactlyOneExists(hasText("Похоже, сейчас непросто", substring = true), 5_000)
        screenshot("21-kindness")
        // The kindness card's own «Написать» (on Sundays the week's review card has one too).
        val hint = onNodeWithText("Похоже, сейчас непросто", substring = true).fetchSemanticsNode().boundsInRoot.top
        val writes = onAllNodesWithText("Написать")
        val below = writes.fetchSemanticsNodes().withIndex().filter { it.value.boundsInRoot.top > hint }.minBy { it.value.boundsInRoot.top }
        writes[below.index].click()
        val question = Prompts.kindness(services.today())
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.journal().first().any { it.title == question } } }
    }

    @Test
    fun aSharedLinkBecomesANoteInOneTap() = runDesktopComposeUiTest(width = 412, height = 860) {
        var closed = false
        setContent {
            QuickCapture(services, SharedContent.text(sharedNoteText("Статья про сон", "https://example.com/sleep"))) {
                closed =
                    true
            }
        }
        waitUntilExactlyOneExists(hasText("Заметка в Benbow"), 5_000)
        screenshot("24-capture")
        onNodeWithText("Сохранить").click()
        waitUntil(timeoutMillis = 5_000) { closed }
        assertEquals("Статья про сон", runBlocking { services.repo.journal().first().single().title })
    }

    @Test
    fun theWholeJournalExportsFromMore() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.repo.createNote(services.today(), "Для архива")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Для архива"), 5_000)
        onNodeWithText("Ещё").click()
        onNodeWithText("Экспорт").click()
        waitUntilExactlyOneExists(hasText("Сохранить архив…"), 5_000)
        val out = ByteArrayOutputStream()
        services.exportArchive({ out }, withMedia = true) // the save dialog is the platform's; from here on it's the app
        waitUntilExactlyOneExists(hasText("Готово", substring = true), 5_000)
        screenshot("25-export")
        assertTrue(out.size() > 0)
    }

    @Test
    fun theLockCoversEverythingUntilTheOwnerIsConfirmed() = runDesktopComposeUiTest(width = 412, height = 860) {
        var asked = 0
        val auth = object : DeviceAuth {
            override fun available() = true

            override fun authenticate(title: String, onResult: (Boolean) -> Unit) {
                asked++ // the system prompt would show here; the owner hasn't answered yet
            }
        }
        var open = false
        setContent { app.benbow.ui.theme.BenbowTheme { LockScreen(auth) { open = true } } }
        waitUntilExactlyOneExists(hasText("Дневник закрыт"), 5_000)
        screenshot("27-lock")
        assertEquals(1, asked, "it asks at once")
        assertFalse(open)
    }

    @Test
    fun appearanceSwitchesPaletteAndLightTheme() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.repo.createNote(services.today(), "Утро у моря")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Утро у моря"), 5_000)
        onNodeWithText("Ещё").click()
        onNodeWithText("Оформление").click()
        waitUntilExactlyOneExists(hasText("Пергамент"), 5_000)
        screenshot("28-appearance")
        onNodeWithText("Светлая").click()
        onNodeWithText("Море").click()
        waitUntil(timeoutMillis = 5_000) {
            services.appearance.value == Appearance(app.benbow.ui.theme.ThemeMode.Light, app.benbow.ui.theme.ThemePalette.Sea)
        }
        waitForIdle()
        screenshot("29-appearance-sea-light")
        onNodeWithContentDescription("Назад").click()
        onNodeWithText("Главная").click()
        waitUntilExactlyOneExists(hasText("Утро у моря"), 5_000)
        screenshot("30-home-sea-light")
        assertEquals(Appearance.load(services.settings), services.appearance.value, "kept on this device")
    }

    @Test
    fun aNoteTakesAColorAndALook() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = services.repo.createNote(services.today(), "Список покупок\nхлеб, молоко")
        services.repo.createNote(services.today(), "Без цвета")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Список покупок"), 5_000)
        onNodeWithText("Список покупок").click()
        // A note opens to be read; the look is chosen while writing.
        waitUntilExactlyOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Редактировать").click()
        // «Оформление записки» (ADR-047, ADR-049: an icon beside the star while writing): the color, then layers of the
        // look, each saved as it is picked.
        waitUntilExactlyOneExists(hasContentDescription("Оформление записки"), 5_000)
        onNodeWithContentDescription("Оформление записки").click()
        onNodeWithContentDescription("Мятный").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.entry(id).first()?.background == "mint" } }
        onNodeWithContentDescription("Линейка").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.entry(id).first()?.look?.get("paper") == "lines" } }
        onNodeWithContentDescription("Скотч").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.entry(id).first()?.look?.get("scrap") == "tape" } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Перемешать") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Перемешать").click()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { services.repo.entry(id).first()?.look?.get("scrap")?.startsWith("tape.") == true }
        }
        waitForIdle()
        screenshot("31-note-look")
        onNodeWithText("Скрапбук").performScrollTo()
        waitForIdle()
        screenshot("31-note-look-2")
        onNodeWithContentDescription("Назад").click()
        waitUntilExactlyOneExists(hasText("Без цвета"), 5_000)
        screenshot("32-home-note-look")
    }

    @Test
    fun theIconRenders() = runDesktopComposeUiTest(width = 220, height = 220) {
        setContent {
            androidx.compose.foundation.Image(appIcon(), null, androidx.compose.ui.Modifier.size(192.dp))
        }
        waitForIdle()
        screenshot("33-icon")
    }

    @Test
    fun aWideWindowGetsARailAndKeyboardShortcuts() = runDesktopComposeUiTest(width = 1280, height = 820) {
        services.repo.createNote(services.today(), "Широкое окно")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Широкое окно"), 5_000)
        screenshot("34-desktop-wide")

        services.shortcuts.tryEmit(Shortcut.NewEntry)
        waitUntilExactlyOneExists(hasText("Итоги дня"), 5_000) // the templates of a new, empty entry
        // Home's quick note stays on the left beside the new entry: type into the entry.
        onNode(hasSetTextAction() and !hasText("Заметка…")).performTextInput("Набрано с клавиатуры")
        services.shortcuts.tryEmit(Shortcut.Save)
        waitUntilExactlyOneExists(hasText("Сохранено"), 5_000)
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.journal().first().any { it.title == "Набрано с клавиатуры" } } }
        screenshot("35-desktop-editor")

        services.shortcuts.tryEmit(Shortcut.Tab(5)) // «Ещё», after «Записки» and «Трекеры»
        waitUntilExactlyOneExists(hasText("Сочетания клавиш"), 5_000)
        onNodeWithText("Сочетания клавиш").click()
        waitUntilExactlyOneExists(hasText("Ctrl+N"), 5_000)
        screenshot("36-shortcuts")
    }

    @Test
    fun aWideWindowPicksTagsInADialogByKeyboard() = runDesktopComposeUiTest(width = 1280, height = 820) {
        val walk = services.repo.createNote(services.today(), "Прогулка")
        services.repo.addTag(walk, "Море")
        services.repo.addTag(walk, "Мария")
        val plans = services.repo.createNote(services.today(), "Планы")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Планы"), 5_000)
        onNodeWithText("Планы").performClick()
        startEditing() // an entry tapped on Home reads first
        waitUntilExactlyOneExists(hasText("+ Тег"), 5_000)
        onNodeWithText("+ Тег").click()
        val field = hasSetTextAction() and hasText("Найти", substring = true)
        waitUntilExactlyOneExists(field, 5_000)
        onNode(field).performTextInput("м")
        val order = TagSearch.find("м", runBlocking { services.repo.tags().first() }.map { TagChoice(it.id, it.name, it.uses) })
        onNode(hasSetTextAction() and hasText("м")).performKeyInput { pressKey(Key.DirectionDown) } // to the second line
        waitForIdle()
        screenshot("61-wide-tag-picker")
        onNode(hasSetTextAction() and hasText("м")).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.entry(plans).first()!!.tags.size == 1 } }
        assertEquals(order[1].tag.name, runBlocking { services.repo.entry(plans).first()!!.tags.single().name }, "the highlighted line")
        onNode(field).performKeyInput { pressKey(Key.Backspace) } // an empty field: takes the last tag off
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.entry(plans).first()!!.tags.isEmpty() } }
    }

    @Test
    fun aWideWindowShowsTheEntryBesideTheList() = runDesktopComposeUiTest(width = 1280, height = 820) {
        services.repo.createNote(services.today(), "Первая в ленте")
        services.repo.createNote(services.today(), "Вторая в ленте")
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").performClick()
        waitUntilExactlyOneExists(hasText("Откройте запись слева", substring = true), 5_000)
        screenshot("58-wide-feed")

        // The post opens on the right; the feed stays on the left (each text shows twice while it is open).
        onNodeWithText("Первая в ленте").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Первая в ленте").fetchSemanticsNodes().size == 2 }
        assertEquals(1, onAllNodesWithText("Вторая в ленте").fetchSemanticsNodes().size, "the feed is still there")
        screenshot("59-wide-post")

        // Another post from the list takes its place instead of stacking on it; Back then closes the right side.
        onAllNodesWithText("Вторая в ленте")[0].performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Вторая в ленте").fetchSemanticsNodes().size == 2 &&
                onAllNodesWithText("Первая в ленте").fetchSemanticsNodes().size == 1
        }
        onNodeWithContentDescription("Назад").performClick()
        waitUntilExactlyOneExists(hasText("Откройте запись слева", substring = true), 5_000)
        screenshot("60-wide-closed")
    }

    @Test
    fun theLineBetweenListAndEntryMoves() = runDesktopComposeUiTest(width = 1280, height = 820) {
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").performClick()
        waitUntilExactlyOneExists(hasText("Откройте запись слева", substring = true), 5_000)
        // The placeholder sits in the middle of the right side, which starts after the rail, the list (400) and the line (9).
        val middle = onNodeWithText("Откройте запись слева", substring = true).fetchSemanticsNode().boundsInRoot.center.x
        val rail = 2 * middle - 1280 - 409
        fun line(list: Float) = Offset(rail + list + 4.5f, 400f)
        fun width() = services.settings.get("ui.listWidth")?.toFloat()
        onRoot().performMouseInput {
            moveTo(line(400f))
            press()
            repeat(10) { moveBy(Offset(12f, 0f), delayMillis = 16) }
            release()
        }
        waitForIdle()
        val dragged = width() ?: 0f
        assertTrue(dragged in 450f..520f, "the list got wider: $dragged")
        screenshot("60a-wide-dragged")

        // Not past what the entry needs, and a double click puts it back.
        onRoot().performMouseInput {
            moveTo(line(dragged))
            press()
            repeat(20) { moveBy(Offset(40f, 0f), delayMillis = 16) }
            release()
        }
        waitForIdle()
        val widest = minOf(640f, 1280 - rail - 480)
        assertEquals(widest, width(), "the widest list")
        onRoot().performMouseInput { doubleClick(line(widest)) }
        waitForIdle()
        assertEquals(400f, width())
    }

    /** A finger drag in small steps, the way a pager or a pull-to-close sees a real swipe. */
    private fun androidx.compose.ui.test.TouchInjectionScope.drag(from: Offset, by: Offset) {
        down(from)
        repeat(12) { moveBy(by / 12f, delayMillis = 16) }
        up()
    }

    @Test
    fun picturesSwipeInTheFeedAndFullScreen() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Три снимка с моря")
        runBlocking {
            repeat(3) { i ->
                val bytes = picture()
                val image = ImageIO.read(bytes.inputStream())
                services.attach({ entry }, { bytes.inputStream() }) {
                    MediaProbe("image/jpeg", "IMG_$i.jpg", "image", image.width, image.height, preview = picture(512, 384))
                }.join()
            }
        }
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Три снимка с моря"), 5_000)
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("1/3"), 5_000)
        runBlocking { delay(1_000) } // Coil decodes off the test's idling
        waitForIdle()
        screenshot("37-feed-carousel")
        onNodeWithContentDescription("Вложения").performTouchInput { drag(center, Offset(-width * 0.7f, 0f)) }
        waitUntilExactlyOneExists(hasText("2/3"), 5_000)

        onNodeWithContentDescription("Вложения").performTouchInput { click(center) }
        waitUntilExactlyOneExists(hasText("2 / 3"), 5_000)
        runBlocking { delay(800) }
        waitForIdle()
        screenshot("38-viewer")
        onRoot().performTouchInput { drag(center, Offset(-width * 0.7f, 0f)) }
        waitUntilExactlyOneExists(hasText("3 / 3"), 5_000)
        onRoot().performTouchInput { drag(center, Offset(0f, height * 0.35f)) }
        waitUntilExactlyOneExists(hasText("Три снимка с моря"), 5_000) // back in the feed
    }

    @Test
    fun withTheWebsSwitchAPostsPictureOpensThePost() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.setAppearance(services.appearance.value.copy(mediaOpensPost = true))
        val entry = services.repo.createNote(services.today(), "Два снимка с моря")
        runBlocking {
            repeat(2) { i ->
                val bytes = picture()
                val image = ImageIO.read(bytes.inputStream())
                services.attach({ entry }, { bytes.inputStream() }) {
                    MediaProbe("image/jpeg", "IMG_$i.jpg", "image", image.width, image.height, preview = picture(512, 384))
                }.join()
            }
        }
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Два снимка с моря"), 5_000)
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("1/2"), 5_000)
        onNodeWithContentDescription("Вложения").performTouchInput { click(center) }
        // The post, not the viewer.
        waitUntilAtLeastOneExists(hasContentDescription("Назад"), 5_000)
        assertTrue(onAllNodes(hasText("1 / 2")).fetchSemanticsNodes().isEmpty(), "no viewer")
    }

    @Test
    fun aPictureAddedWhileWritingLandsInTheText() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Прогулка у моря\nВот что я увидел:")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Прогулка у моря"), 5_000)
        onNodeWithText("Прогулка у моря").click()
        startEditing() // an entry tapped on Home reads first
        waitUntilExactlyOneExists(hasText("Настроение"), 5_000)
        val bytes = picture()
        val image = ImageIO.read(bytes.inputStream())
        runBlocking {
            services.attach({ entry }, { bytes.inputStream() }) {
                MediaProbe("image/jpeg", "IMG_7.jpg", "image", image.width, image.height, preview = picture(512, 384))
            }.join()
        }
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { services.repo.entry(entry).first()?.body?.contains("\"attachment\"") == true }
        }
        runBlocking { delay(1_000) }
        waitForIdle()
        screenshot("39-inline-picture")

        onNodeWithContentDescription("Назад").click()
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasContentDescription("Вложения"), 5_000)
        onNodeWithText("Прогулка у моря", substring = true).click()
        waitUntilExactlyOneExists(hasText("Комментарии"), 5_000)
        runBlocking { delay(1_000) }
        waitForIdle()
        screenshot("40-post-inline")
    }

    @Test
    fun theFeedKeepsAPostsShapeAndCommentsShowTheirPictures() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = services.repo.createNote(services.today(), "Черновик")
        val doc = app.benbow.core.content.Document(
            listOf(
                app.benbow.core.content.TextBlock(
                    "h",
                    style = "h2",
                    children = listOf(app.benbow.core.content.Span("Bob Dylan — New Morning")),
                ),
                app.benbow.core.content.TextBlock(
                    "q",
                    style = "blockquote",
                    children = listOf(app.benbow.core.content.Span("Sign on the window says lonely.")),
                ),
                app.benbow.core.content.TextBlock(
                    "q2",
                    style = "blockquote",
                    children = listOf(app.benbow.core.content.Span("Sign on the door said no company allowed.")),
                ),
                app.benbow.core.content.TextBlock("h3", style = "h3", children = listOf(app.benbow.core.content.Span("Мелкий заголовок"))),
                app.benbow.core.content.TextBlock(
                    "l1",
                    listItem = "bullet",
                    level = 1,
                    children = listOf(app.benbow.core.content.Span("подоконник")),
                ),
                app.benbow.core.content.TextBlock(
                    "l2",
                    listItem = "check",
                    level = 1,
                    checked = true,
                    children = listOf(app.benbow.core.content.Span("воздух")),
                ),
                app.benbow.core.content.TextBlock(
                    "c",
                    callout = app.benbow.core.content.CalloutRef("c", "yellow", "💡"),
                    children = listOf(app.benbow.core.content.Span("Осень. Аврора. Надежда.")),
                ),
                app.benbow.core.content.TextBlock("p", children = listOf(app.benbow.core.content.Span("Долгий абзац. ".repeat(40)))),
                app.benbow.core.content.TextBlock("p2", children = listOf(app.benbow.core.content.Span("Самый конец поста"))),
            ) + (1..25).map { line ->
                app.benbow.core.content.TextBlock(
                    "s$line",
                    style = "blockquote",
                    children = listOf(app.benbow.core.content.Span("Don't let go $line")),
                )
            } + listOf(
                app.benbow.core.content.TextBlock("p3", children = listOf(app.benbow.core.content.Span("Wunderhorse — The Rope"))),
            ),
        )
        services.repo.saveDraft(id, app.benbow.core.content.DocumentCodec.encode(doc))
        services.repo.commitDraft(id)
        val comment = services.repo.createComment(id, services.today(), "Обложка альбома")
        runBlocking {
            val bytes = picture()
            services.attach({
                comment
            }, { bytes.inputStream() }) { MediaProbe("image/jpeg", "cover.jpg", "image", 1600, 1200, preview = picture(512, 384)) }.join()
        }
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Ещё"), 5_000)
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("Bob Dylan — New Morning"), 5_000)
        waitUntilExactlyOneExists(hasText("Показать ещё"), 5_000)
        screenshot("41-feed-formatting")
        assertTrue(onAllNodes(hasText("Don't let go 25")).fetchSemanticsNodes().isEmpty(), "a long quote stops with the rest")
        onNodeWithText("Показать ещё").click()
        waitUntilExactlyOneExists(hasText("Самый конец поста"), 5_000)

        services.setAppearance(services.appearance.value.copy(feedCards = false))
        waitForIdle()
        screenshot("42-feed-flat")

        onNodeWithText("Bob Dylan — New Morning").click()
        waitUntilExactlyOneExists(hasText("Обложка альбома"), 5_000)
        runBlocking { delay(1_000) }
        waitForIdle()
        screenshot("43-post-comment-media")
    }

    @Test
    fun attachmentsReorderByLongPressAndDrag() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Три снимка")
        runBlocking {
            for (name in listOf("A.jpg", "B.jpg", "C.jpg")) {
                val bytes = picture(640, 480)
                services.attach({ entry }, { bytes.inputStream() }) { MediaProbe("image/jpeg", name, "image", 640, 480) }.join()
            }
        }
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Три снимка"), 5_000)
        onNodeWithText("Три снимка").click()
        startEditing() // an entry tapped on Home reads first
        waitUntilExactlyOneExists(hasContentDescription("A.jpg"), 5_000)
        onNodeWithContentDescription("A.jpg").performTouchInput {
            down(center)
            move(delayMillis = 1_000) // held still: a long press lifts it
            repeat(8) { moveBy(Offset(10f, 0f), delayMillis = 16) } // past the middle of B, not of C
            up()
        }
        runBlocking { delay(500) }
        waitForIdle()
        assertEquals(
            listOf("B.jpg", "A.jpg", "C.jpg"),
            runBlocking {
                services.repo.entry(entry).first()!!.attachments.map { it.meta.name }
            },
        )
        waitForIdle()
        screenshot("44-ribbon-reordered")
    }

    @Test
    fun postPicturesFollowTheChosenLayout() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote("2026-10-10", "Прогулка по набережной")
        runBlocking {
            listOf(600 to 1000, 1600 to 900, 1600 to 1200, 1200 to 1200, 1600 to 1000).forEachIndexed { i, (w, h) ->
                val bytes = picture(w, h)
                services.attach({ entry }, { bytes.inputStream() }) { MediaProbe("image/jpeg", "P$i.jpg", "image", w, h) }.join()
            }
        }
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Ещё"), 5_000)
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasContentDescription("Вложения"), 5_000)
        onNodeWithContentDescription("Вложения").performTouchInput { drag(center, Offset(-width * 0.7f, 0f)) }
        waitUntilExactlyOneExists(hasText("2/5"), 5_000)
        runBlocking { delay(1_000) }
        waitForIdle()
        screenshot("45-carousel-fitted") // a wide one after a tall one: whole, over a blurred copy

        onNodeWithText("Прогулка по набережной").click()
        waitUntilExactlyOneExists(hasText("Суббота"), 5_000) // the weekday under the date
        runBlocking { delay(800) }
        waitForIdle()
        screenshot("46-post-carousel")
        services.setAppearance(services.appearance.value.copy(postMedia = PostMedia.Grid))
        runBlocking { delay(800) }
        waitForIdle()
        screenshot("47-post-grid")
        services.setAppearance(services.appearance.value.copy(postMedia = PostMedia.Strip))
        runBlocking { delay(800) }
        waitForIdle()
        screenshot("48-post-strip")
    }

    @Test
    fun voiceNotesShowAsPlayers() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Мысли по дороге")
        runBlocking {
            val sound = ByteArray(4_000) { (it % 251).toByte() }
            services.attach({ entry }, { sound.inputStream() }) {
                MediaProbe("audio/mp4", "voice-1.m4a", "voice", durationMs = 42_000, capturedAt = 0)
            }.join()
        }
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Ещё"), 5_000)
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("Голосовое сообщение"), 5_000)
        waitUntilExactlyOneExists(hasText("0:42"), 5_000)
        screenshot("49-feed-voice")
        onNodeWithText("Мысли по дороге").click()
        waitUntilExactlyOneExists(hasText("Голосовое сообщение"), 5_000)
        screenshot("50-post-voice")

        // The pencil opens a sheet: title, artist and album like a track's; «Сохранить» keeps them.
        onNodeWithContentDescription("Название и исполнитель").performClick()
        val title = hasSetTextAction() and isFocused() // the post's comment box has a text field too
        waitUntilExactlyOneExists(title, 5_000)
        onNode(title).performTextInput("Утро у моря")
        onNode(hasSetTextAction() and hasText("Исполнитель")).performTextInput("Я и гитара")
        screenshot("51-voice-rename")
        onNodeWithText("Сохранить").performClick()
        // Title and artist land in one transaction; the in-memory test DB shares one connection, so wait for both.
        waitUntil(timeoutMillis = 5_000) {
            val named = runBlocking { services.repo.entry(entry).first()!! }
            named.attachments.single().caption == "Утро у моря" && named.tracks[named.attachments.single().meta.id]?.artist == "Я и гитара"
        }
        waitUntilExactlyOneExists(hasText("Я и гитара · 0:42"), 5_000)
    }

    @Test
    fun newEntryOpenedByMistakeLeavesNothing() = runDesktopComposeUiTest(width = 412, height = 860) {
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Как прошёл день? Пара строк — и он сохранится."))
        onNodeWithContentDescription("Новая запись").performClick()
        waitUntilExactlyOneExists(hasText("Сегодня", substring = true) and hasClickAction()) // the editor title: day, time, tap to move
        onNodeWithContentDescription("Назад").performClick()
        waitUntilExactlyOneExists(hasText("Как прошёл день? Пара строк — и он сохранится."))
        assertEquals(0, runBlocking { services.repo.journal().first().size })
    }

    /** A device that can transcribe: a made-up model already in place, a recognizer that hears one sentence. */
    private fun speakingServices(): AppServices {
        val models = java.nio.file.Files.createTempDirectory("benbow-models").toFile()
        val bytes = "model".toByteArray()
        val files = listOf(app.benbow.core.api.SpeechModelFile("encoder.int8.onnx", bytes.size.toLong(), "unused"))
        java.io.File(models, app.benbow.core.api.SpeechModelSpec.ID).apply {
            mkdirs()
            java.io.File(this, "encoder.int8.onnx").writeBytes(bytes)
            java.io.File(this, "verified").writeText(app.benbow.core.api.SpeechModelSpec.ID)
        }
        val heard = object : Transcriber {
            override suspend fun <T> session(model: app.benbow.client.SpeechModel, block: suspend (Recognizer) -> T): T = block(
                object : Recognizer {
                    override suspend fun file(sound: java.io.File) = "Шли вдоль берега, ветер был холодный, но солнце светило ярко."

                    override suspend fun samples(samples: FloatArray) = error("no dictation here")
                },
            )
        }
        return AppServices(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
            mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
            "android",
            "Test phone",
            transcriber = heard,
            speechModel = { api ->
                app.benbow.client.SpeechModelStore(java.io.File(models, app.benbow.core.api.SpeechModelSpec.ID), api, files)
            },
        )
    }

    @Test
    fun aVoiceNoteBecomesTextOnATap() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = speakingServices()
        val entry = services.repo.createNote(services.today(), "Мысли по дороге")
        runBlocking {
            val sound = ByteArray(4_000) { (it % 251).toByte() }
            services.attach({ entry }, { sound.inputStream() }) {
                MediaProbe("audio/mp4", "voice-1.m4a", "voice", durationMs = 42_000, capturedAt = 0)
            }.join()
        }
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Ещё"), 5_000)
        onNodeWithText("Лента").performClick()
        waitUntilExactlyOneExists(hasText("Голосовое сообщение"), 5_000)
        val voice = services.repo.entry(entry).let { runBlocking { it.first()!! } }.attachments.single().meta.id
        val transcript = "Шли вдоль берега, ветер был холодный, но солнце светило ярко."
        // Nothing is transcribed on its own any more (owner 2026-10-18): the row's button does it.
        assertEquals(null, runBlocking { services.repo.entry(entry).first()!!.transcripts[voice] })
        onNodeWithContentDescription("Расшифровать").performClick()
        waitUntil(timeoutMillis = 10_000) { runBlocking { services.repo.entry(entry).first()!!.transcripts[voice] } != null }
        // The post reads it afresh. Under a full run the in-memory test DB (one connection for every thread) sometimes drops
        // the change notice of a write made while another thread is in a transaction, so the feed's window can miss it
        // here; the phone's driver keeps a transaction per thread.
        onNodeWithText("Мысли по дороге").performClick()
        waitUntilExactlyOneExists(hasText(transcript), 5_000)
        screenshot("53-post-transcript")
        assertEquals(listOf(entry), runBlocking { services.repo.search(app.benbow.client.SearchQuery("берегом")).first().map { it.id } })
    }

    @Test
    fun speechSettingsSayWhereTheModelComesFrom() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = speakingServices()
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Ещё"), 5_000)
        onNodeWithText("Ещё").performClick()
        waitUntilExactlyOneExists(hasText("Расшифровка голосовых"), 5_000)
        onNodeWithText("Расшифровка голосовых").performScrollTo().performClick() // below the window since «Медиатека»
        waitUntilExactlyOneExists(hasText("Модель на телефоне"), 5_000)
        screenshot("54-speech-settings")
    }

    /** A line across the canvas, the way a finger draws it. */
    private fun ComposeUiTest.drawLine(from: Offset, step: Offset) {
        onNodeWithContentDescription("Холст").performTouchInput {
            down(from)
            repeat(24) { moveBy(step, delayMillis = 16) }
            up()
        }
    }

    @Test
    fun aDrawingGoesInTheTextAndARedrawReplacesIt() = runDesktopComposeUiTest(width = 412, height = 860) {
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Ещё"), 5_000)
        onNodeWithContentDescription("Новая запись").performClick()
        waitUntilExactlyOneExists(hasContentDescription("Нарисовать"), 5_000)
        onNodeWithContentDescription("Нарисовать").performClick()
        waitUntilExactlyOneExists(hasContentDescription("Холст"), 5_000)
        drawLine(Offset(40f, 60f), Offset(12f, 5f))
        drawLine(Offset(60f, 220f), Offset(10f, -4f))
        screenshot("55-drawing")
        onNodeWithContentDescription("Назад").performClick()

        // Back in the note: the drawing sits in the text.
        waitUntilExactlyOneExists(hasContentDescription("Рисунок"), 5_000)
        val entry = runBlocking { services.repo.journal().first().single().id }
        fun drawings() = runBlocking { services.repo.entry(entry).first()!! }.let { d ->
            d.attachments.filter { it.meta.kind == "drawing" } to
                d.body
        }
        // The draft is saved a moment after the edit.
        waitUntil(timeoutMillis = 5_000) { drawings().let { (d, body) -> d.size == 1 && d.single().meta.id in body } }
        val (first, _) = drawings()
        screenshot("56-editor-drawing")

        // A tap opens it again; one more line and back: the text points at the new version, the old one leaves.
        onNodeWithContentDescription("Рисунок").performClick()
        waitUntilExactlyOneExists(hasContentDescription("Холст"), 5_000)
        drawLine(Offset(80f, 140f), Offset(6f, 6f))
        onNodeWithContentDescription("Назад").performClick()
        waitUntil(timeoutMillis = 5_000) {
            drawings().let { (d, body) -> d.singleOrNull()?.meta?.id.let { it != null && it != first.single().meta.id && it in body } }
        }
        val (second, secondBody) = drawings()
        assertTrue(second.single().meta.id in secondBody)
        assertTrue(first.single().meta.id !in secondBody, "the old version is out of the text")
        val svg = runBlocking { services.media.file(second.single().meta.sha256).readText() }
        assertEquals(3, app.benbow.core.ink.InkSvg.decode(svg)!!.strokes.size, "two lines and the one added")

        // In the feed it's a picture like any other.
        onNodeWithContentDescription("Назад").performClick()
        waitUntilExactlyOneExists(hasText("Ещё"), 5_000)
        onNodeWithText("Лента").performClick()
        waitUntilExactlyOneExists(hasText("Моя лента"), 5_000)
        runBlocking { delay(500) }
        waitForIdle()
        screenshot("57-feed-drawing")
    }
}
