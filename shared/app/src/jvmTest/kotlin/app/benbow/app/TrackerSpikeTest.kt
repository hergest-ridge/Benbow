package app.benbow.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The trackers' B0 spike (ADR-050): «Ещё → Трекеры» on a phone, a tab on a wide window, marks and the eye. */
@OptIn(ExperimentalTestApi::class)
class TrackerSpikeTest {
    @Test
    fun aPhoneFindsThemInMoreMarksByTapAndHidesTheNumbers() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val today = LocalDate.parse(services.today())
        val water = SPIKE_HABITS.columns.first { it.id == "water" }
        setContent { BenbowApp(services) }
        assertEquals(0, onAllNodesWithText("Трекеры").fetchSemanticsNodes().size, "no tab on a phone")
        onNodeWithText("Ещё").click()
        waitUntilAtLeastOneExists(hasText("Привычки, время и сон по дням"), 5_000)
        onNodeWithText("Привычки, время и сон по дням").click()
        waitUntilAtLeastOneExists(hasText("Итого"), 5_000)
        screenshot("trackers-01-phone")

        onNodeWithTag(cellId(water, today)).click()
        onNodeWithTag(cellId(water, today)).click()
        waitForIdle()
        assertEquals(listOf("2"), texts(cellId(water, today)), "two taps, two glasses")

        onNodeWithContentDescription("Скрыть цифры").click()
        waitUntilAtLeastOneExists(hasContentDescription("Показать цифры"), 5_000)
        onAllNodesWithText("Итого").assertCountEquals(0)
        assertEquals(emptyList(), texts(cellId(water, today)), "the count shows as its fill alone")
        screenshot("trackers-02-quiet")

        onNodeWithContentDescription("Оформление трекера").click()
        waitUntilAtLeastOneExists(hasText("Оформление трекера"), 5_000) // the note's look sheet, for this tracker
        screenshot("trackers-05-look")
    }

    @Test
    fun theSleepSheetPaintsANightHourByHour() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val today = LocalDate.parse(services.today())
        val night = SPIKE_SLEEP.columns.first()
        setContent { TrackersScreen(services, Navigator(mutableStateListOf(Route.Trackers)), tab = false) }
        onNodeWithText("😴 Сон").click()
        waitUntilAtLeastOneExists(hasText("🌙 Сон"), 5_000)
        listOf(3, 4).forEach { onNodeWithTag("${cellId(night, today)}:$it").click() }
        waitUntilAtLeastOneExists(hasText("2:00"), 5_000) // the night's length at the row's end
        screenshot("trackers-03-sleep")
    }

    @Test
    fun aWideWindowHasATabAfterTheBoardOnlyInLabs() = runDesktopComposeUiTest(width = 1280, height = 800) {
        setContent { BenbowApp(labsServices("desktop")) }
        onNodeWithText("Трекеры").click()
        waitUntilAtLeastOneExists(hasText("Итого"), 5_000)
        screenshot("trackers-04-wide")
    }

    @Test
    fun theOwnersAppShowsNoTrackers() = runDesktopComposeUiTest(width = 1280, height = 800) {
        setContent { BenbowApp(feedTestServices()) }
        waitForIdle()
        assertTrue(onAllNodesWithText("Трекеры").fetchSemanticsNodes().isEmpty())
    }

    /** The texts shown in the cell tagged [tag] (merged into it, as a tap target). */
    private fun ComposeUiTest.texts(tag: String): List<String> =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun labsServices(platform: String) = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(Files.createTempDirectory("benbow-media").toFile()),
        platform,
        "Test",
        labs = true,
    )
}
