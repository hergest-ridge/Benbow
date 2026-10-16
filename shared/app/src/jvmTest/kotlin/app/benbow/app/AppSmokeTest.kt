package app.benbow.app

import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import app.benbow.client.Prompts
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.EventField
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
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
    )

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
        // Home's quick note stays on the left beside the new entry: type into the entry.
        onNode(hasSetTextAction() and !hasText("Заметка…")).performTextInput("Набрано с клавиатуры")
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

        // A tap on the title names the sound in place; Done keeps it, and the feed shows the name too.
        onNodeWithText("Голосовое сообщение").performClick()
        val title = hasSetTextAction() and isFocused() // the post's comment box has a text field too
        waitUntilExactlyOneExists(title, 5_000)
        onNode(title).performTextInput("Утро у моря")
        screenshot("51-voice-rename")
        onNode(title).performImeAction()
        waitUntilExactlyOneExists(hasText("Утро у моря") and hasClickAction(), 5_000)
        assertEquals("Утро у моря", runBlocking { services.repo.entry(entry).first()!!.attachments.single().caption })
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
            override suspend fun <T> session(
                model: app.benbow.client.SpeechModel,
                block: suspend (transcribe: suspend (java.io.File) -> String) -> T,
            ): T = block { "Шли вдоль берега, ветер был холодный, но солнце светило ярко." }
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
        if (runBlocking { services.repo.entry(entry).first()!!.transcripts[voice] } == null) {
            onNodeWithContentDescription("Расшифровать").performClick()
        }
        waitUntilExactlyOneExists(hasText(transcript), 5_000)
        screenshot("52-feed-transcript")
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
        onNodeWithText("Расшифровка голосовых").performClick()
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
