package app.benbow.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.HomeBlock
import app.benbow.client.HomeBlockType
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.client.editHomeLayout
import app.benbow.client.plusBlock
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerField
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Трекеры» (ADR-050, B4): a sheet from a preset, marks by type, the eye, the sleep sheet's hours, the trash. */
@OptIn(ExperimentalTestApi::class)
class TrackersScreenTest {
    @Test
    fun aPhoneStartsFromAPresetMarksDaysByTypeAndHidesTheNumbers() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val today = services.today()
        setContent { BenbowApp(services) }
        assertEquals(0, onAllNodesWithText("Трекеры").fetchSemanticsNodes().size, "no tab on a phone")
        onNodeWithText("Ещё").click()
        waitUntilAtLeastOneExists(hasText("Привычки, время и сон по дням"), 5_000)
        onNodeWithText("Привычки, время и сон по дням").click()
        waitUntilAtLeastOneExists(hasText("Трекеров пока нет"), 5_000)
        screenshot("trackers-01-empty")

        onNodeWithText("🌱 Привычки").click()
        waitUntilAtLeastOneExists(hasText("Итого"), 5_000)
        val tracker = runBlocking { services.trackers.trackers().first().single() }
        val columns = runBlocking { services.trackers.columns(tracker.id).first() }
        assertEquals(TrackerPresets.habits.columns.size, columns.size)
        val (walk, water, energy) = listOf(columns[1], columns[3], columns[5]).map { TrackerCells.id(it.id, today) }
        screenshot("trackers-02-sheet")

        onNodeWithTag(walk).click()
        onNodeWithTag(water).click()
        onNodeWithTag(water).click()
        waitUntil(timeoutMillis = 5_000) { texts(water) == listOf("2") }
        assertEquals("true", cell(services, walk)?.get("value"))

        onNodeWithTag(energy).click() // a rating offers its levels
        waitUntilAtLeastOneExists(hasTestTag("pick:3"), 5_000)
        onNodeWithTag("pick:3").click()
        waitUntil(timeoutMillis = 5_000) { texts(energy) == listOf("3") }

        onNodeWithTag(water).performSemanticsAction(SemanticsActions.OnLongClick) // the exact number and a note
        waitUntilAtLeastOneExists(hasText("Сколько"), 5_000)
        onNodeWithTag("cell-count").performTextReplacement("5")
        onNodeWithText("Сохранить").click()
        waitUntil(timeoutMillis = 5_000) { texts(water) == listOf("5") }
        screenshot("trackers-03-marked")

        onNodeWithContentDescription("Скрыть цифры").click()
        waitUntilAtLeastOneExists(hasContentDescription("Показать цифры"), 5_000)
        onAllNodesWithText("Итого").assertCountEquals(0)
        assertEquals(emptyList(), texts(water), "the count shows as its fill alone")
        assertEquals("hidden", runBlocking { services.trackers.trackers().first().single() }[TrackerField.FIGURES], "on every device")
        screenshot("trackers-04-quiet")

        onNodeWithContentDescription("Оформление трекера").click()
        waitUntilAtLeastOneExists(hasText("Оформление трекера"), 5_000) // the note's look sheet, for this tracker
        screenshot("trackers-05-look")
    }

    @Test
    fun theSleepSheetPaintsANightHourByHourAsOneSpan() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val today = services.today()
        val id = services.trackers.createTracker(TrackerPresets.sleep.tracker, TrackerPresets.sleep.columns)
        val night = runBlocking { services.trackers.columns(id).first() }.first().id
        fun spans() = runBlocking { services.trackers.spans(listOf(night), today, today).first() }
        setContent { TrackerSheetScreen(services, Navigator(mutableStateListOf(Route.TrackerSheet(id))), id, back = true) }
        waitUntilAtLeastOneExists(hasText("🌙 Сон"), 5_000)

        onNodeWithTag("${TrackerCells.id(night, today)}:3").click() // midnight: the 21:00-11:00 night's fourth hour
        waitUntil(timeoutMillis = 5_000) { spans().size == 1 }
        onNodeWithTag("${TrackerCells.id(night, today)}:4").click()
        waitUntilAtLeastOneExists(hasText("2:00"), 5_000) // the night's length at the row's end
        val span = spans().single()
        assertEquals("${today}T00:00:00" to "${today}T02:00:00", span[SpanField.START] to span[SpanField.END], "the hour joined the night")
        screenshot("trackers-06-sleep")
    }

    @Test
    fun aTimerRunsFromItsHeaderAndADaysSpansAreTypedByHand() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val today = services.today()
        val id = services.trackers.createTracker(TrackerPresets.time.tracker, TrackerPresets.time.columns)
        fun work() = runBlocking { services.trackers.columns(id).first() }.first()
        fun spans() = runBlocking { services.trackers.spans(listOf(work().id), today, today).first() }
        setContent { TrackerSheetScreen(services, Navigator(mutableStateListOf(Route.TrackerSheet(id))), id, back = true) }
        val timer = "timer:" + work().id
        waitUntilAtLeastOneExists(hasTestTag(timer), 5_000)
        onNodeWithTag(timer).click()
        waitUntil(timeoutMillis = 5_000) { work().running != null }
        waitUntilAtLeastOneExists(hasContentDescription("Остановить таймер"), 5_000)
        screenshot("trackers-10-timer")
        onNodeWithTag(timer).click()
        waitUntil(timeoutMillis = 5_000) { work().running == null && spans().size == 1 }

        onNodeWithTag(TrackerCells.id(work().id, today)).performSemanticsAction(SemanticsActions.OnLongClick)
        waitUntilAtLeastOneExists(hasText("Добавить отрезок"), 5_000)
        onNodeWithText("Добавить отрезок").click()
        waitUntilAtLeastOneExists(hasTestTag("span-start:1"), 5_000)
        screenshot("trackers-11-spans")
        onNodeWithText("Сохранить").click()
        waitUntil(timeoutMillis = 5_000) { spans().size == 2 }
        assertTrue(spans().any { it[SpanField.START] == "${today}T09:00:00" && it[SpanField.END] == "${today}T10:00:00" })
    }

    @Test
    fun aWideWindowHasATabWithTheListBesideTheSheetAndTheTrashGivesItBack() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val services = labsServices("desktop")
        services.trackers.createTracker(TrackerPresets.habits.tracker, TrackerPresets.habits.columns)
        setContent { BenbowApp(services) }
        onNodeWithText("Трекеры").click()
        waitUntilAtLeastOneExists(hasText("Итого"), 5_000)
        waitUntilAtLeastOneExists(hasText("Привычки"), 5_000)
        screenshot("trackers-07-wide")

        onAllNodesWithContentDescription("Меню").assertCountEquals(2) // the list's row and the sheet beside it
        onAllNodesWithContentDescription("Меню")[1].click() // the sheet's
        waitUntilAtLeastOneExists(hasText("В корзину"), 5_000)
        onNodeWithText("В корзину").click()
        waitUntilAtLeastOneExists(hasText("Вернуть"), 5_000)
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.trackers.trackers().first() }.isEmpty() }
        onNodeWithText("Вернуть").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.trackers.trackers().first() }.size == 1 }
        waitUntilAtLeastOneExists(hasText("Итого"), 5_000)
    }

    @Test
    fun aBlankSheetGetsItsColumnsInTheConstructor() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val id = services.trackers.createTracker(TrackerPresets.blank.tracker, TrackerPresets.blank.columns)
        fun columns() = runBlocking { services.trackers.columns(id).first() }
        setContent { TrackerEditorScreen(services, Navigator(mutableStateListOf(Route.TrackerEditor(id))), id) }
        waitUntilAtLeastOneExists(hasText("Колонок пока нет", substring = true), 5_000)
        onNodeWithText("Добавить колонку").click()
        waitUntilAtLeastOneExists(hasText("Число"), 5_000)
        onNodeWithText("Число").click() // a type first, then the column's editor opens
        waitUntil(timeoutMillis = 5_000) { columns().size == 1 }
        assertEquals("count", columns().single().record["type"])
        waitUntilAtLeastOneExists(hasTestTag("column-name"), 5_000)
        onNodeWithTag("column-name").performTextReplacement("Отжимания")
        screenshot("trackers-08-column")
        onNodeWithText("Готово").click()
        waitUntil(timeoutMillis = 5_000) { columns().single().name == "Отжимания" }
        screenshot("trackers-09-constructor")
    }

    @Test
    fun aTrashedTrackerWaitsInTheTrash() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val id = services.trackers.createTracker(TrackerPresets.habits.tracker, TrackerPresets.habits.columns)
        services.trackers.moveToTrash(id)
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Корзина").click()
        waitUntilAtLeastOneExists(hasText("Трекер «🌱 Привычки»"), 5_000)
        onNodeWithText("Восстановить").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.trackers.trackers().first() }.size == 1 }
    }

    @Test
    fun aTrackerOpensAsAWeekOrAYearAndRemembersWhich() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val today = services.today()
        val id = services.trackers.createTracker(TrackerPresets.habits.tracker, TrackerPresets.habits.columns)
        val walk = runBlocking { services.trackers.columns(id).first() }[1].id
        setContent { TrackerSheetScreen(services, Navigator(mutableStateListOf(Route.TrackerSheet(id))), id, back = true) }
        waitUntilAtLeastOneExists(hasText("Неделя"), 5_000)
        onNodeWithText("Неделя").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.trackers.trackers().first() }.single()[TrackerField.VIEW] == "week" }
        waitUntilAtLeastOneExists(hasTestTag(TrackerCells.id(walk, today)), 5_000)
        onNodeWithTag(TrackerCells.id(walk, today)).click()
        waitUntil(timeoutMillis = 5_000) { cell(services, TrackerCells.id(walk, today))?.get("value") == "true" }
        screenshot("trackers-12-week")
        onNodeWithText("Год").click()
        waitUntilAtLeastOneExists(hasTestTag("year:" + runBlocking { services.trackers.columns(id).first() }.first().id), 5_000)
        screenshot("trackers-13-year")
    }

    @Test
    fun aTrackersBlockOnHomeTicksTodayAndTheCalendarShowsItsLine() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val today = services.today()
        val id = services.trackers.createTracker(TrackerPresets.habits.tracker, TrackerPresets.habits.columns)
        val stretch = runBlocking { services.trackers.columns(id).first() }.first().id
        services.trackers.updateTracker(id, mapOf(TrackerField.CALENDAR to "true"))
        services.repo.editHomeLayout { it.plusBlock(HomeBlock("t1", HomeBlockType.Tracker.value, "s", mapOf(HomeBlock.TRACKER to id)), 0) }
        setContent { BenbowApp(services) }
        val tick = "home:" + TrackerCells.id(stretch, today)
        waitUntilAtLeastOneExists(hasTestTag(tick), 5_000)
        screenshot("trackers-14-home")
        onNodeWithTag(tick).click()
        waitUntil(timeoutMillis = 5_000) { cell(services, TrackerCells.id(stretch, today))?.get("value") == "true" }

        onNodeWithText("Календарь").click()
        waitUntilAtLeastOneExists(hasText("🧘"), 5_000) // the day's marks under its title
        screenshot("trackers-15-calendar")
    }

    @Test
    fun aWidgetReadsTodayAndTicksChecksAndCounts() = runBlocking {
        val services = labsServices("android")
        val id = services.trackers.createTracker(TrackerPresets.habits.tracker, TrackerPresets.habits.columns)
        val ids = services.trackers.columns(id).first().map { it.id }
        val (stretch, water) = ids[0] to ids[3]
        services.tickToday(stretch)
        services.tickToday(water)
        services.tickToday(water)
        val today = services.trackersToday().single()
        assertEquals("🌱 Привычки", today.title)
        assertEquals(listOf(true, false, false, true), today.marks.take(4).map { it.marked })
        assertEquals("💧 2", today.marks[3].text)
        assertEquals(listOf(true, true, true, true, true, false), today.marks.map { it.tickable }, "checks and counts tick")
        services.tickToday(stretch)
        assertEquals(false, services.trackersToday().single().marks[0].marked, "a second tap takes it off")
        assertEquals(emptyList(), feedTestServices().trackersToday(), "the owner's app shows none")
    }

    @Test
    fun tenColumnsFitTheMonthAndTheYear() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices("android")
        val columns = (1..10).map { mapOf("name" to "Колонка $it", "type" to "check", "icon" to "emoji:🌱", "color" to "green") }
        val id = services.trackers.createTracker(mapOf("name" to "Десять"), columns)
        val first = runBlocking { services.trackers.columns(id).first() }.first().id
        setContent { TrackerSheetScreen(services, Navigator(mutableStateListOf(Route.TrackerSheet(id))), id, back = true) }
        waitUntilAtLeastOneExists(hasText("Итого"), 5_000)
        screenshot("trackers-16-ten")
        onNodeWithText("Год").click()
        waitUntilAtLeastOneExists(hasTestTag("year:$first"), 5_000)
        screenshot("trackers-17-ten-year")
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

    private fun cell(services: AppServices, id: String): Map<String, String?>? {
        val (column, day) = id.substringBeforeLast(':') to id.substringAfterLast(':')
        return runBlocking { services.trackers.cells(listOf(column), day, day).first() }.singleOrNull()?.fields
    }

    private fun labsServices(platform: String) = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(Files.createTempDirectory("benbow-media").toFile()),
        platform,
        "Test",
        labs = true,
    )
}
