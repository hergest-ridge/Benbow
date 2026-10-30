package app.benbow.core.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spans of time (ADR-050): a night over midnight, the day a span counts for. */
class TrackerSpansTest {
    private val night = span("n1", "2026-10-26T23:10:00", "2026-10-27T07:05:00")

    @Test
    fun aNightLastsItsSecondsAndCountsForTheDayOneWakes() {
        assertEquals((7 * 3600 + 55 * 60).toLong(), TrackerSpans.seconds(night))
        assertEquals("2026-10-27", TrackerSpans.dayOf(night, DayBy.End))
        assertEquals("2026-10-26", TrackerSpans.dayOf(night, DayBy.Start))
        assertEquals(50L * 60, TrackerSpans.secondsOn(night, "2026-10-26"), "drawn on both days")
        assertEquals((7 * 3600 + 5 * 60).toLong(), TrackerSpans.secondsOn(night, "2026-10-27"))
        assertEquals(0L, TrackerSpans.secondsOn(night, "2026-10-28"))
    }

    @Test
    fun brokenOrOpenSpansCountNothing() {
        assertNull(TrackerSpans.seconds(span("a", "2026-10-27T09:00:00", null)), "still running")
        assertNull(TrackerSpans.seconds(span("b", "2026-10-27T09:00:00", "2026-10-27T08:00:00")), "backwards")
        assertNull(TrackerSpans.seconds(span("c", "yesterday", "2026-10-27T08:00:00")))
        assertNull(TrackerSpans.dayOf(span("d", "soon", null), DayBy.Start))
    }

    @Test
    fun aDaysTotalLeavesOutTheTrashAndOtherDays() {
        val spans = listOf(
            night,
            span("n2", "2026-10-27T13:00:00", "2026-10-27T13:30:00"),
            span("n3", "2026-10-27T01:00:00", "2026-10-27T02:00:00") + ("deleted" to "true"),
            span("n4", "2026-10-27T23:00:00", "2026-10-28T06:00:00"),
        )
        assertEquals((7 * 3600 + 55 * 60 + 30 * 60).toLong(), TrackerSpans.total(spans, "2026-10-27", DayBy.End))
    }

    private fun span(id: String, start: String, end: String?) =
        TrackerRecord(id, mapOf(SpanField.COLUMN to "sleep", SpanField.START to start, SpanField.END to end))

    private operator fun TrackerRecord.plus(field: Pair<String, String?>) = copy(fields = fields + field)
}
