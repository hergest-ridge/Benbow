package app.benbow.app

import app.benbow.core.tracker.DayBy
import app.benbow.core.tracker.HourWindow
import app.benbow.core.tracker.TrackerRecord
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The trackers' time (ADR-050, B6): timers' words, drags on the sleep sheet, a day's spans as typed, a week's days. */
class TrackerTimeTest {
    private val day = LocalDate(2026, 10, 27)

    private fun span(id: String, start: String, end: String?) = TrackerRecord(id, mapOf("column" to "c", "start" to start, "end" to end))

    @Test
    fun aTimerTicksInWords() {
        assertEquals("0:05", tickText(5))
        assertEquals("4:05", tickText(4 * 60 + 5))
        assertEquals("1:04:05", tickText(3600 + 4 * 60 + 5))
        assertEquals(90, runningSeconds("2026-10-27T09:00:00", LocalDateTime(2026, 10, 27, 9, 1, 30)))
        assertEquals(0, runningSeconds("2026-10-27T10:00:00", LocalDateTime(2026, 10, 27, 9, 0)), "a clock set back counts none")
        assertEquals(0, runningSeconds("soon", LocalDateTime(2026, 10, 27, 9, 0)))
        assertEquals("2026-10-27T09:05:00", timeOf(LocalDateTime(2026, 10, 27, 9, 5)))
        assertEquals(0, hourAt(-3f, 140, 14))
        assertEquals(3, hourAt(35f, 140, 14))
        assertEquals(13, hourAt(500f, 140, 14))
    }

    @Test
    fun aDragPaintsTheHoursItPassesOrClearsThemWhenItStartsOnAPaintedOne() {
        val starts = hourStarts(HourWindow.of("21:00-11:00")!!, day, DayBy.End) // 21:00 the day before … 10:00
        assertEquals(listOf(SpanEdit.Add("2026-10-26T23:00:00", "2026-10-27T07:00:00")), dragEdits(emptyList(), starts, 2, 9))
        assertEquals(listOf(SpanEdit.Add("2026-10-26T23:00:00", "2026-10-27T07:00:00")), dragEdits(emptyList(), starts, 9, 2), "either way")
        val night = listOf(span("n", "2026-10-26T23:00:00", "2026-10-27T03:00:00"), span("m", "2026-10-27T05:00:00", "2026-10-27T07:00:00"))
        assertEquals(
            listOf(SpanEdit.Change("n", mapOf("end" to "2026-10-27T07:00:00")), SpanEdit.Trash("m")),
            dragEdits(night, starts, 6, 8),
            "a drag over the gap joins the night",
        )
        assertEquals(
            listOf(
                SpanEdit.Change("n", mapOf("end" to "2026-10-27T01:00:00")),
                SpanEdit.Change("m", mapOf("start" to "2026-10-27T06:00:00")),
            ),
            dragEdits(night, starts, 4, 8),
            "started on a painted hour: cleared",
        )
        assertEquals(emptyList(), paintSpans(night, seconds(LocalDateTime(2026, 10, 27, 0, 0)), seconds(LocalDateTime(2026, 10, 27, 2, 0))))
    }

    @Test
    fun aDaysSpansAreTypedAsClocksAndANightStartsTheEveningBefore() {
        assertEquals("2026-10-26T23:10:00" to "2026-10-27T07:05:00", spanTimes(day, DayBy.End, 23 * 60 + 10, 7 * 60 + 5))
        assertEquals("2026-10-27T01:00:00" to "2026-10-27T07:00:00", spanTimes(day, DayBy.End, 60, 7 * 60))
        assertEquals("2026-10-27T22:00:00" to "2026-10-28T01:00:00", spanTimes(day, DayBy.Start, 22 * 60, 60))
        assertNull(spanTimes(day, DayBy.Start, 60, 60))
        assertEquals("23:10", clockAt("2026-10-26T23:10:42"))
        assertEquals("", clockAt(null))

        val spans = listOf(
            span("a", "2026-10-26T23:10:42", "2026-10-27T07:05:13"),
            span("b", "2026-10-27T13:00:00", "2026-10-27T14:00:00"),
            span("open", "2026-10-27T20:00:00", null),
        )
        val listed = spansOn(spans, "2026-10-27", DayBy.End)
        assertEquals(listOf("a", "b"), listed.map { it.id }, "only spans with an end, by their start")
        val drafts = listed.map { SpanDraft(it.id, clockAt(it["start"]), clockAt(it["end"])) }
        assertEquals(emptyList(), spanEdits(day, DayBy.End, drafts, listed), "opened and saved: nothing rewritten, seconds kept")
        val edited =
            listOf(drafts[0].copy(end = "8:00"), drafts[1].copy(gone = true), newSpanDraft(DayBy.End).copy(start = "14:30", end = "15:00"))
        assertEquals(
            listOf(
                SpanEdit.Change("a", mapOf("end" to "2026-10-27T08:00:00")),
                SpanEdit.Trash("b"),
                SpanEdit.Add("2026-10-27T14:30:00", "2026-10-27T15:00:00"),
            ),
            spanEdits(day, DayBy.End, edited, listed),
        )
        assertNull(spanEdits(day, DayBy.End, listOf(drafts[0].copy(start = "25:00")), listed), "a time that doesn't read writes nothing")
        assertEquals(SpanDraft(null, "09:00", "10:00"), newSpanDraft(DayBy.Start))
        val instant = listOf(span("z", "2026-10-27T16:40:05", "2026-10-27T16:40:09"))
        assertEquals(
            emptyList(),
            spanEdits(day, DayBy.Start, listOf(SpanDraft("z", "16:40", "16:40")), instant),
            "a stopped timer of seconds stays",
        )
    }

    @Test
    fun aWeekIsMondayToSundayWithinTheMonth() {
        val october = monthDays(day)
        assertEquals((26..31).map { LocalDate(2026, 10, it) }, weekOf(day, october), "the month's last week, cut at its end")
        assertEquals((1..4).map { LocalDate(2026, 10, it) }, weekOf(LocalDate(2026, 10, 4), october), "its first, from Thursday")
    }
}
