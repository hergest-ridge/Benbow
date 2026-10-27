package app.benbow.core.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A column's figures only count up, and a goal only fills once reached (ux.md "Psychology", ADR-050). */
class TrackerFiguresTest {
    private val days = (1..7).map { "2026-10-0$it" }

    @Test
    fun eachTypeHasItsFigure() {
        val check = column("check")
        val count = column("count")
        val scale = column("scale")
        val choice = column("choice")
        assertEquals(TrackerFigure.Days(2), TrackerFigures.of(check, cells("value", "true", "2026-10-01", "2026-10-03"), emptyList(), days))
        val water = mapOf("2026-10-01" to mapOf("add:a" to "3"), "2026-10-02" to mapOf("add:a" to "2", "add:b" to "2.5"))
        assertEquals(TrackerFigure.Sum(7.5), TrackerFigures.of(count, water, emptyList(), days))
        val energy = mapOf("2026-10-01" to mapOf("value" to "3"), "2026-10-02" to mapOf("value" to "4"))
        assertEquals(TrackerFigure.Average(3.5), TrackerFigures.of(scale, energy, emptyList(), days))
        val moods = mapOf("2026-10-01" to "good", "2026-10-02" to "bad", "2026-10-03" to "bad", "2026-10-04" to "good")
            .mapValues { mapOf("value" to it.value) }
        assertEquals(TrackerFigure.Top("good", 2), TrackerFigures.of(choice, moods, emptyList(), days), "a tie: the first to get there")
        assertNull(TrackerFigures.of(check, emptyMap(), emptyList(), days), "nothing marked: no figure, not a zero")
        assertNull(TrackerFigures.of(column("habit"), cells("value", "true", "2026-10-01"), emptyList(), days))
    }

    @Test
    fun aTimeColumnAveragesTheDaysItHasTimeOn() {
        val sleep = column("time", "day_by" to "end")
        val spans = listOf(
            span("2026-10-01T23:00:00", "2026-10-02T07:00:00"),
            span("2026-10-02T23:30:00", "2026-10-03T06:30:00"),
        )
        assertEquals(TrackerFigure.Time(7 * 3600 + 1800, 15 * 3600), TrackerFigures.of(sleep, emptyMap(), spans, days))
    }

    @Test
    fun goalsFillOnceReached() {
        val water = column("count", "goal" to "8")
        val glasses = mapOf("2026-10-01" to mapOf("add:a" to "5", "add:b" to "3"), "2026-10-02" to mapOf("add:a" to "7"))
        assertTrue(TrackerFigures.reached(water, glasses, emptyList(), "2026-10-01"))
        assertFalse(TrackerFigures.reached(water, glasses, emptyList(), "2026-10-02"))

        val sport = column("check", "goal" to "3", "goal_per" to "week")
        val marks = cells("value", "true", "2026-09-28", "2026-10-01", "2026-10-04", "2026-10-05")
        assertTrue(TrackerFigures.reached(sport, marks, emptyList(), "2026-10-02"), "three days from Monday the 28th to Sunday the 4th")
        assertFalse(TrackerFigures.reached(sport, marks, emptyList(), "2026-10-05"), "the next week has one so far")

        val reading = column("check", "goal" to "2", "goal_per" to "month")
        assertTrue(TrackerFigures.reached(reading, marks, emptyList(), "2026-10-31"))
        assertFalse(TrackerFigures.reached(column("check"), marks, emptyList(), "2026-10-01"), "no goal, nothing to reach")

        val work = column("time", "goal" to "1.5")
        val hours = listOf(span("2026-10-01T09:00:00", "2026-10-01T10:00:00"), span("2026-10-01T14:00:00", "2026-10-01T14:30:00"))
        assertTrue(TrackerFigures.reached(work, emptyMap(), hours, "2026-10-01"))
    }

    @Test
    fun aGoalsPeriodIsItsWeekFromMondayOrItsMonth() {
        assertEquals((28..30).map { "2026-09-$it" } + (1..4).map { "2026-10-0$it" }, TrackerFigures.period("2026-10-01", GoalPer.Week))
        assertEquals(29, TrackerFigures.period("2028-02-10", GoalPer.Month).size, "2028 is a leap year")
        assertEquals(emptyList(), TrackerFigures.period("not a day", GoalPer.Week))
    }

    private fun column(type: String, vararg fields: Pair<String, String>) =
        TrackerColumn(TrackerRecord("c", mapOf("type" to type) + fields))

    private fun cells(field: String, value: String, vararg days: String) = days.associateWith { mapOf(field to value) }

    private fun span(start: String, end: String) = TrackerRecord(start, mapOf("column" to "c", "start" to start, "end" to end))
}
