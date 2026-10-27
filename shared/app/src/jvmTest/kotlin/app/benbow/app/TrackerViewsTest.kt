package app.benbow.app

import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerRecord
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The trackers' views (ADR-050, B7): where one opens, a week's and a year's pages, a day's marks in words. */
class TrackerViewsTest {
    private val today = LocalDate(2026, 10, 27) // a Tuesday
    private val columns = TrackerPresets.habits.columns.mapIndexed { i, fields -> TrackerColumn(TrackerRecord("c$i", fields)) }
    private val work = TrackerColumn(TrackerRecord("w", TrackerPresets.time.columns.first()))

    @Test
    fun aTrackerOpensWhereItSaysAndPagesGoByWeeksAndYears() {
        assertEquals(SheetView.Week, SheetView.of("week"))
        assertEquals(SheetView.Month, SheetView.of("sleep"), "the sleep sheet is a month with its hours")
        assertEquals(SheetView.Month, SheetView.of(null))
        assertEquals(LocalDate(2026, 10, 26), mondayOf(today))
        assertEquals((26..31).map { LocalDate(2026, 10, it) } + LocalDate(2026, 11, 1), weekOfPage(today, WEEK_ORIGIN))
        assertEquals(LocalDate(2026, 10, 19), weekOfPage(today, WEEK_ORIGIN - 1).first())
        assertEquals(2025, yearOfPage(today, YEAR_ORIGIN - 1))
        val days = yearDays(2026)
        assertEquals(372, days.size)
        assertEquals(LocalDate(2026, 2, 28), days[31 + 27])
        assertNull(days[31 + 29], "no 30th of February")
        assertEquals(LocalDate(2028, 2, 29), yearDays(2028)[31 + 28])
    }

    @Test
    fun aDaysMarksReadInAFewCharacters() {
        val (stretch, _, _, water, _, energy, day) = columns
        assertEquals("🧘", markText(stretch, mapOf("value" to "true"), 0, figures = true))
        assertNull(markText(stretch, null, 0, figures = true))
        assertEquals("💧 3", markText(water, mapOf("add:a" to "3"), 0, figures = true))
        assertEquals("💧", markText(water, mapOf("add:a" to "3"), 0, figures = false), "the eye hides numbers here too")
        assertEquals("🔋 4", markText(energy, mapOf("value" to "4"), 0, figures = true))
        assertEquals("🙂", markText(day, mapOf("value" to "good"), 0, figures = true))
        assertEquals("💻 1:30", markText(work, null, 5400, figures = true))
        assertEquals(0.5f, pixelFill(water, mapOf("add:a" to "4"), 0))
        assertEquals(0.25f, pixelFill(work, null, 2 * 3600), "eight hours fill a time's pixel without a goal")

        val cells = mapOf("c0:2026-10-27" to mapOf("value" to "true"), "c3:2026-10-27" to mapOf("add:a" to "1"))
        assertEquals(2, markedOn(columns, cells, emptyMap(), "2026-10-27"))
        val spans = mapOf("w" to listOf(TrackerRecord("s", mapOf("start" to "2026-10-27T09:00:00", "end" to "2026-10-27T10:00:00"))))
        assertEquals(1, markedOn(listOf(work), emptyMap(), spans, "2026-10-27"))
    }

    private operator fun <T> List<T>.component6() = this[5]

    private operator fun <T> List<T>.component7() = this[6]
}
