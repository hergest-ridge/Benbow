package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.ui.theme.ThemeMode
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

/** The note-look spike (ADR-047, 7.20 L0): every sample picture reads, and the screen in both themes for the owner. */
@OptIn(ExperimentalTestApi::class)
class LooksSpikeTest {
    private fun services() = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
        "desktop",
        "Test PC",
        labs = true,
    )

    @Test
    fun everySamplePictureReadsIntoAVector() {
        val root = File("src/commonMain/composeResources/files/looks")
        val files = root.walk().filter { it.extension == "svg" }.toList()
        assertTrue(files.size >= 40, "the samples are there (${files.size})")
        val unread = files.filter { LookPictures.parse(it.name, it.readText()) == null }.map { it.name }
        assertTrue(unread.isEmpty(), "unread: $unread")
    }

    @Test
    fun theSpikeInBothThemes() {
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            runDesktopComposeUiTest(width = 412, height = 900) {
                val services = services()
                services.setAppearance(services.appearance.value.copy(mode = mode))
                setContent { BenbowApp(services) }
                onNodeWithText("Ещё").click()
                waitUntilAtLeastOneExists(hasText("Облик записки (проба)"), 5_000)
                onNodeWithText("Облик записки (проба)").click()
                waitUntilAtLeastOneExists(hasText("Бумага"), 5_000)
                val name = mode.name.lowercase()
                // Pictures load from resources off the test's idling.
                listOf(
                    "Бумага" to "paper",
                    "Бумага · фото ambientCG" to "paper-photos",
                    "Узоры · Hero Patterns" to "hero",
                    "Гламур" to "hero-2",
                    "Обои Морриса · The Met" to "morris",
                    "Россыпь звёзд" to "patterns",
                    "Скрапбук · рисуется кодом" to "scrap",
                    "Коллаж на бумаге" to "scrap-2",
                    "Картинка на скотче · The Met" to "taped",
                    "Уголки" to "taped-2",
                    "Рисунки · Openclipart" to "openclipart",
                    "Образы" to "looks",
                    "Чертёжная тетрадь" to "looks-2",
                ).forEach { (title, part) ->
                    onNode(hasScrollAction()).performScrollToNode(hasText(title, substring = true))
                    runBlocking { delay(400) }
                    waitForIdle()
                    screenshot("looks-$name-$part")
                }
            }
        }
    }
}
