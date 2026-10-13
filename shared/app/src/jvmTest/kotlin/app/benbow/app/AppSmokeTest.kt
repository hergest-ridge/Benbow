package app.benbow.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
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
        waitUntilExactlyOneExists(hasText("Сегодня"))
        onNode(hasSetTextAction()).performTextInput(" и сыр")
        screenshot("03-editor")
        onNodeWithContentDescription("Назад").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.journal().first().single().preview.contains("сыр") } }
        screenshot("04-edited")

        onNodeWithText("Купить хлеб").performClick()
        onNodeWithContentDescription("Меню").performClick()
        onNodeWithText("В корзину").performClick()
        waitUntilExactlyOneExists(hasText("Как прошёл день? Пара строк — и он сохранится."), 5_000)

        onNodeWithContentDescription("Меню").performClick()
        onNodeWithText("Корзина").performClick()
        waitUntilExactlyOneExists(hasText("Восстановить"), 5_000)
        screenshot("05-trash")
        onNodeWithText("Восстановить").performClick()
        waitUntilExactlyOneExists(hasText("Корзина пуста"), 5_000)
        onNodeWithContentDescription("Назад").performClick()
        waitUntilExactlyOneExists(hasText("Купить хлеб"), 5_000)

        onNodeWithText("Не подключено").performClick()
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
        waitUntilExactlyOneExists(hasText("#Работа"), 5_000)
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
        waitUntilExactlyOneExists(hasText("Утренняя заметка\nвторая строка"), 5_000)
        waitUntilExactlyOneExists(hasText("🔥"), 5_000)
        screenshot("13-feed")
    }

    @Test
    fun commentsAndTagCategories() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Обед с Машей")
        services.repo.addTag(entry, "Маша")
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Обед с Машей"), 5_000)
        onNodeWithText("Обед с Машей").click()
        waitUntilExactlyOneExists(hasText("#Маша"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Комментарии").click()
        waitUntilExactlyOneExists(hasText("Комментарий…"), 5_000)
        onNode(hasSetTextAction()).performTextInput("Было очень вкусно")
        onNodeWithContentDescription("Сохранить заметку").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.commentCount(entry).first() } == 1L }
        waitUntilExactlyOneExists(hasText("Было очень вкусно"), 5_000)
        screenshot("14-comments")

        onNodeWithContentDescription("Назад").click()
        waitUntilExactlyOneExists(hasText("#Маша"), 5_000)
        onNodeWithContentDescription("Назад").click()
        waitUntilExactlyOneExists(hasText("Добр", substring = true), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Теги").click()
        waitUntilExactlyOneExists(hasText("#Маша · 1"), 5_000)
        onNodeWithText("#Маша · 1").click()
        onNodeWithText("Люди").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.tags().first().single().category == "Люди" } }
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
    fun newEntryOpenedByMistakeLeavesNothing() = runDesktopComposeUiTest(width = 412, height = 860) {
        setContent { BenbowApp(services) }
        waitUntilExactlyOneExists(hasText("Как прошёл день? Пара строк — и он сохранится."))
        onNodeWithContentDescription("Новая запись").performClick()
        waitUntilExactlyOneExists(hasText("Сегодня"))
        onNodeWithContentDescription("Назад").performClick()
        waitUntilExactlyOneExists(hasText("Как прошёл день? Пара строк — и он сохранится."))
        assertEquals(0, runBlocking { services.repo.journal().first().size })
    }
}
