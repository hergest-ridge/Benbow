package app.benbow.client.calendar

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * How an event repeats: the RFC 5545 RRULE subset of docs/data-model.md#calendar (FREQ, INTERVAL, COUNT,
 * UNTIL, BYDAY, BYMONTHDAY). Occurrences are computed for the range on screen, never stored as copies.
 */
data class Recurrence(
    val freq: Freq,
    val interval: Int = 1,
    val count: Int? = null,
    val until: LocalDate? = null,
    val byDay: List<Weekday> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** A weekday, in a monthly rule optionally the [nth] one of the month (-1 = the last). */
    data class Weekday(val day: DayOfWeek, val nth: Int? = null)

    /** Back to RRULE text (without the "RRULE:" prefix). */
    fun format(): String = buildList {
        add("FREQ=$freq")
        if (interval != 1) add("INTERVAL=$interval")
        count?.let { add("COUNT=$it") }
        until?.let { add("UNTIL=" + it.toString().replace("-", "")) }
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.joinToString(",") { (it.nth?.toString() ?: "") + CODES.getValue(it.day) })
        if (byMonthDay.isNotEmpty()) add("BYMONTHDAY=" + byMonthDay.joinToString(","))
    }.joinToString(";")

    /**
     * Start days of the occurrences that fall on [from]..[to], for a series starting on [start]. [except] removes
     * days (EXDATE) after COUNT has been applied, as RFC 5545 does.
     */
    fun dates(start: LocalDate, from: LocalDate, to: LocalDate, except: Set<LocalDate> = emptySet()): List<LocalDate> {
        val last = listOfNotNull(to, until).min()
        val found = ArrayList<LocalDate>()
        var made = 0
        var period = 0
        while (period < MAX_PERIODS) {
            val candidates = candidates(start, period++)
            if (candidates.isEmpty() && periodStart(start, period - 1) > last) break
            for (date in candidates) {
                if (date < start) continue
                if (date > last) return found
                made++
                if (date >= from && date !in except) found += date
                if (count != null && made >= count) return found
            }
        }
        return found
    }

    /** The occurrences a period (a day, week, month or year of the series) yields, in order. */
    private fun candidates(start: LocalDate, period: Int): List<LocalDate> {
        val step = period * interval
        return when (freq) {
            Freq.DAILY -> listOf(start.plus(step, DateTimeUnit.DAY)).filter { d -> byDay.isEmpty() || byDay.any { it.day == d.dayOfWeek } }

            Freq.WEEKLY -> {
                val monday = start.minus(start.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY).plus(step, DateTimeUnit.WEEK)
                val days = byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) }.distinct().sortedBy { it.isoDayNumber }
                days.map { monday.plus(it.isoDayNumber - 1, DateTimeUnit.DAY) }
            }

            Freq.MONTHLY -> {
                val first = LocalDate(start.year, start.month, 1).plus(step, DateTimeUnit.MONTH)
                val length = first.daysUntil(first.plus(1, DateTimeUnit.MONTH))
                val days = when {
                    byMonthDay.isNotEmpty() -> byMonthDay.map { if (it < 0) length + it + 1 else it }
                    byDay.isNotEmpty() -> byDay.flatMap { weekday -> weekdaysOf(first, length, weekday) }
                    else -> listOf(start.day)
                }
                days.filter { it in 1..length }.distinct().sorted().map { LocalDate(first.year, first.month, it) }
            }

            Freq.YEARLY -> {
                val year = start.year + step
                // February 29 only comes back in leap years.
                listOfNotNull(runCatching { LocalDate(year, start.month, start.day) }.getOrNull())
            }
        }
    }

    private fun periodStart(start: LocalDate, period: Int): LocalDate = when (freq) {
        Freq.DAILY -> start.plus(period * interval, DateTimeUnit.DAY)
        Freq.WEEKLY -> start.plus(period * interval, DateTimeUnit.WEEK)
        Freq.MONTHLY -> LocalDate(start.year, start.month, 1).plus(period * interval, DateTimeUnit.MONTH)
        Freq.YEARLY -> LocalDate(start.year + period * interval, Month.JANUARY, 1)
    }

    private fun weekdaysOf(first: LocalDate, length: Int, weekday: Weekday): List<Int> {
        val offset = (weekday.day.isoDayNumber - first.dayOfWeek.isoDayNumber + 7) % 7
        val all = (1 + offset..length step 7).toList()
        val nth = weekday.nth ?: return all
        return listOfNotNull(if (nth > 0) all.getOrNull(nth - 1) else all.getOrNull(all.size + nth))
    }

    companion object {
        private val CODES = mapOf(
            DayOfWeek.MONDAY to "MO",
            DayOfWeek.TUESDAY to "TU",
            DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH",
            DayOfWeek.FRIDAY to "FR",
            DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )

        /** Safety net for odd rules (e.g. "every 31st" with a huge interval): no endless loops. */
        private const val MAX_PERIODS = 200_000

        /**
         * Reads RRULE text ("FREQ=WEEKLY;BYDAY=MO,WE", with or without the "RRULE:" prefix). Null for anything
         * outside the subset: such an event shows once, on its start, and keeps its rule untouched.
         */
        fun parse(rule: String): Recurrence? {
            val parts = rule.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate { part ->
                val (key, value) = part.split('=', limit = 2).let { it[0].uppercase() to it.getOrElse(1) { "" } }
                key to value
            }
            val freq = parts["FREQ"]?.let { f -> Freq.entries.firstOrNull { it.name == f.uppercase() } } ?: return null
            if (parts.keys.any { it !in KNOWN }) return null
            val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it >= 1 } ?: if ("INTERVAL" in parts) return null else 1
            val count = parts["COUNT"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: return null }
            val until = parts["UNTIL"]?.let { parseDate(it) ?: return null }
            val byDay = parts["BYDAY"]?.split(',')?.map { parseWeekday(it) ?: return null }.orEmpty()
            val byMonthDay = parts["BYMONTHDAY"]?.split(',')?.map { d ->
                d.toIntOrNull()?.takeIf { it != 0 && it in -31..31 } ?: return null
            }.orEmpty()
            val fits = when (freq) {
                Freq.DAILY, Freq.WEEKLY -> byMonthDay.isEmpty() && byDay.all { it.nth == null }
                Freq.MONTHLY -> byDay.isEmpty() || byMonthDay.isEmpty()
                Freq.YEARLY -> byDay.isEmpty() && byMonthDay.isEmpty()
            }
            return if (fits) Recurrence(freq, interval, count, until, byDay, byMonthDay) else null
        }

        private val KNOWN = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "WKST")

        /** "20261231" or "20261231T235959Z": the date part. */
        private fun parseDate(text: String): LocalDate? {
            val digits = text.take(8)
            if (digits.length != 8 || !digits.all { it.isDigit() }) return null
            return runCatching { LocalDate(digits.take(4).toInt(), digits.substring(4, 6).toInt(), digits.takeLast(2).toInt()) }.getOrNull()
        }

        private fun parseWeekday(text: String): Weekday? {
            val code = text.trim().takeLast(2).uppercase()
            val day = CODES.entries.firstOrNull { it.value == code }?.key ?: return null
            val number = text.trim().dropLast(2)
            if (number.isEmpty()) return Weekday(day)
            val nth = number.removePrefix("+").toIntOrNull()?.takeIf { it != 0 && it in -5..5 } ?: return null
            return Weekday(day, nth)
        }
    }
}
