package app.benbow.client.calendar

import app.benbow.client.SWATCHES
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** The built-in calendar of events made in Benbow («Мои события»); it has no record until it is renamed or recolored. */
const val MY_CALENDAR = "mine"

/** Its color until the owner picks another (a Keep palette name, like tag colors). */
const val MY_CALENDAR_COLOR = "sage"

/** Fields of an event record (docs/data-model.md#calendar). */
object EventField {
    const val CALENDAR = "calendar"
    const val TITLE = "title"
    const val START = "start"
    const val END = "end"
    const val DESCRIPTION = "description"
    const val LOCATION = "location"
    const val RRULE = "rrule"

    /** Days the series skips, "YYYY-MM-DD" separated by commas. */
    const val EXDATE = "exdate"
    const val DELETED = "deleted"
}

/** Fields of a calendar record. */
object CalendarField {
    const val NAME = "name"
    const val COLOR = "color"

    /** "false" hides it (the checkbox); absent = shown. */
    const val VISIBLE = "visible"
    const val DELETED = "deleted"
}

/**
 * A calendar event; [start] and [end] are local "YYYY-MM-DD" (all day) or "YYYY-MM-DDTHH:MM". For a repeating
 * event they are one occurrence's, and [seriesStart] is where the series begins.
 */
data class CalendarEvent(
    val id: String,
    val calendar: String,
    val title: String,
    val start: String,
    val end: String? = null,
    val description: String? = null,
    val location: String? = null,
    /** RRULE text when it repeats ([Recurrence]); kept as is even when this build can't read it. */
    val rrule: String? = null,
    val exdates: Set<String> = emptySet(),
    val seriesStart: String = start,
) {
    val day: String get() = start.take(DATE_LENGTH)

    /** The last day it shows on (the start day unless it lasts longer). */
    val lastDay: String get() = end?.take(DATE_LENGTH)?.takeIf { it > day } ?: day

    /** "HH:MM", null for all day. */
    val time: String? get() = start.substringAfter('T', "").takeIf { it.isNotEmpty() }

    val endTime: String? get() = end?.substringAfter('T', "")?.takeIf { it.isNotEmpty() }

    val repeats: Boolean get() = rrule != null

    fun covers(date: String): Boolean = date in day..lastDay

    /** The occurrences that touch [from]..[to]: this event itself, or the series' days there. */
    internal fun occurrences(from: LocalDate, to: LocalDate): List<CalendarEvent> {
        val rule = rrule?.let(Recurrence::parse) ?: return listOf(this)
        val first = parseDay(day) ?: return emptyList()
        val span = parseDay(lastDay)?.let { first.daysUntil(it) } ?: 0
        val skipped = exdates.mapNotNull(::parseDay).toSet()
        return rule.dates(first, from.minus(span, DateTimeUnit.DAY), to, skipped).map { date ->
            copy(
                start = date.toString() + start.drop(DATE_LENGTH),
                end = end?.let { date.plus(span, DateTimeUnit.DAY).toString() + it.drop(DATE_LENGTH) },
            )
        }
    }
}

/** A calendar as its panel shows it; [name] null = the built-in default name. */
data class CalendarItem(val id: String, val name: String?, val color: String, val visible: Boolean, val events: Int = 0) {
    val builtIn: Boolean get() = id == MY_CALENDAR
}

/** A calendar's color until one is picked: steady for its id, so every device draws it the same. */
fun defaultCalendarColor(id: String): String = if (id ==
    MY_CALENDAR
) {
    MY_CALENDAR_COLOR
} else {
    SWATCHES[(id.hashCode() and Int.MAX_VALUE) % SWATCHES.size]
}

internal const val DATE_LENGTH = 10

/** The last day a repeating event without an end can show on. */
internal const val FOREVER = "9999-12-31"

internal fun parseDay(text: String): LocalDate? = runCatching { LocalDate.parse(text.take(DATE_LENGTH)) }.getOrNull()
