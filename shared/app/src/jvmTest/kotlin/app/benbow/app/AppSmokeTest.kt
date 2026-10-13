package app.benbow.app

import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import app.benbow.client.Prompts
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        java.nio.file.Files.createTempDirectory("benbow-media").toFile(),
        "desktop",
        "Test PC",
    )

    private fun SemanticsNodeInteractionsProvider.screenshot(name: String) {
        val file = File("build/screens/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
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

    @Test
    fun favoriteMoodTagsAndSearch() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.repo.createNote(services.today(), "Планы на неделю\nдописать поиск")
        services.repo.createNote(services.today(), "Прогулка у моря")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Планы на неделю"), 5_000)
        onNodeWithText("Планы на неделю").performClick()
        waitUntilExactlyOneExists(hasText("Настроение"), 5_000)
        onNodeWithContentDescription("В избранное").click()
        onNodeWithText("Настроение").click()
        onNodeWithText("🙂").click()
        onNodeWithText("+ Тег").click()
        onNode(hasSetTextAction() and !hasText("Планы", substring = true)).performTextInput("Работа") // the tag field, not the editor
        onNodeWithText("Добавить").click()
        waitUntilExactlyOneExists(hasText("Работа"), 5_000)
        screenshot("09-editor-metadata")

        onNodeWithContentDescription("Назад").performClick()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { services.repo.journal().first().any { it.favorite && it.tags == listOf("Работа") } }
        }
        waitForIdle()
        screenshot("10-journal-metadata")

        onNodeWithText("Поиск").click()
        onNode(hasSetTextAction()).performTextInput("мор")
        waitUntilExactlyOneExists(hasText("Прогулка у моря"), 5_000)
        onNode(hasSetTextAction()).performTextClearance()
        onNodeWithText("★ Избранное").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Прогулка у моря")).fetchSemanticsNodes().isEmpty() }
        waitUntilExactlyOneExists(hasText("Планы на неделю"), 5_000)
        screenshot("11-search")
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

    @Test
    fun commentsAndTagCategories() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Обед с Машей")
        services.repo.addTag(entry, "Маша")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Обед с Машей"), 5_000)
        onNodeWithText("Обед с Машей").click()
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
        onNodeWithText("Маша").click()
        onNodeWithText("Люди").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.tags().first().single().category == "Люди" } }
        onNodeWithContentDescription("Коралловый").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.tagLooks().first().color("Маша") == "coral" } }
        waitForIdle()
        screenshot("15-tags")
        assertEquals(1L, runBlocking { services.repo.commentCount(entry).first() })
    }

    @Test
    fun calendarShowsTheMonthAndTakesAnEvent() = runDesktopComposeUiTest(width = 412, height = 860) {
        val today = services.today()
        val good = services.repo.createNote(today, "Хороший день")
        services.repo.setMood(good, 2)
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Хороший день"), 5_000)
        onNodeWithText("Календарь").click()
        waitUntilExactlyOneExists(hasText("+ Событие"), 5_000)
        onNodeWithText("+ Событие").click()
        waitForIdle()
        onAllNodes(hasSetTextAction())[0].performTextInput("Врач")
        onAllNodes(hasSetTextAction())[1].performTextInput("9:30")
        onNodeWithText("Добавить").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.events(today, today).first().size == 1 } }
        waitUntilExactlyOneExists(hasText("09:30"), 5_000)
        screenshot("16-calendar")
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
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.journal().first().any { it.mood == -1 && "усталость" in it.tags } } }
        waitUntilExactlyOneExists(hasText("Похоже, сейчас непросто", substring = true), 5_000)
        screenshot("21-kindness")
        onAllNodesWithText("Написать")[0].click()
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
    fun aNoteTakesAKeepColor() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = services.repo.createNote(services.today(), "Список покупок\nхлеб, молоко")
        services.repo.createNote(services.today(), "Без цвета")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Список покупок"), 5_000)
        onNodeWithText("Список покупок").click()
        waitUntilExactlyOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Фон").click()
        onNodeWithContentDescription("Мятный").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.entry(id).first()?.background == "mint" } }
        waitForIdle()
        screenshot("31-note-color")
        onNodeWithContentDescription("Назад").click()
        waitUntilExactlyOneExists(hasText("Без цвета"), 5_000)
        screenshot("32-home-note-color")
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
        onNode(hasSetTextAction()).performTextInput("Набрано с клавиатуры")
        services.shortcuts.tryEmit(Shortcut.Save)
        waitUntilExactlyOneExists(hasText("Сохранено"), 5_000)
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.journal().first().any { it.title == "Набрано с клавиатуры" } } }
        screenshot("35-desktop-editor")

        services.shortcuts.tryEmit(Shortcut.Tab(4))
        waitUntilExactlyOneExists(hasText("Сочетания клавиш"), 5_000)
        onNodeWithText("Сочетания клавиш").click()
        waitUntilExactlyOneExists(hasText("Ctrl+N"), 5_000)
        screenshot("36-shortcuts")
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
    fun aPictureAddedWhileWritingLandsInTheText() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Прогулка у моря\nВот что я увидел:")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Прогулка у моря"), 5_000)
        onNodeWithText("Прогулка у моря").click()
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
    fun newEntryOpenedByMistakeLeavesNothing() = runDesktopComposeUiTest(width = 412, height = 860) {
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Как прошёл день? Пара строк — и он сохранится."))
        onNodeWithContentDescription("Новая запись").performClick()
        waitUntilExactlyOneExists(hasText("Сегодня", substring = true) and hasClickAction()) // the editor title: day, time, tap to move
        onNodeWithContentDescription("Назад").performClick()
        waitUntilExactlyOneExists(hasText("Как прошёл день? Пара строк — и он сохранится."))
        assertEquals(0, runBlocking { services.repo.journal().first().size })
    }
}
