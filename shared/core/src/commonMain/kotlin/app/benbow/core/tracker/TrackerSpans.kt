package app.benbow.core.tracker

import app.benbow.core.LocalDays

/** Spans of time a time column holds (ADR-050): local floating times, "YYYY-MM-DDTHH:MM:SS". */
object TrackerSpans {
    /** How long [span] lasts in seconds; null while it has no end, or if its times don't read or run backwards. */
    fun seconds(span: TrackerRecord): Long? {
        val start = span[SpanField.START]?.let(LocalDays::seconds) ?: return null
        val end = span[SpanField.END]?.let(LocalDays::seconds) ?: return null
        return (end - start).takeIf { it >= 0 }
    }

    /** The day [span] counts for, "YYYY-MM-DD": the day it starts or (sleep) ends. */
    fun dayOf(span: TrackerRecord, by: DayBy): String? {
        val time = span[if (by == DayBy.End) SpanField.END else SpanField.START] ?: return null
        return time.take(DAY).takeIf { LocalDays.seconds(time) != null }
    }

    /** The seconds of [span] that fall on [day] itself: a span over midnight is drawn on both days. */
    fun secondsOn(span: TrackerRecord, day: String): Long {
        val start = span[SpanField.START]?.let(LocalDays::seconds) ?: return 0
        val end = span[SpanField.END]?.let(LocalDays::seconds) ?: return 0
        val from = LocalDays.parse(day)?.times(LocalDays.SECONDS_A_DAY) ?: return 0
        return (minOf(end, from + LocalDays.SECONDS_A_DAY) - maxOf(start, from)).coerceAtLeast(0)
    }

    /** Seconds counted for [day] in [spans] of a column (each by the day [by] gives it); deleted spans don't count. */
    fun total(spans: List<TrackerRecord>, day: String, by: DayBy): Long =
        spans.filter { !it.deleted && dayOf(it, by) == day }.sumOf { seconds(it) ?: 0L }

    private const val DAY = 10
}
