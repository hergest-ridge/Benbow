package app.benbow.core

/**
 * Local dates and times as Benbow writes them ("YYYY-MM-DD", "YYYY-MM-DDTHH:MM" or "…:SS"), counted from 1970-01-01 by
 * the proleptic Gregorian calendar (H. Hinnant's civil algorithms): for code that also runs on the server, which has no
 * kotlinx-datetime. Times are floating, as everywhere in the app (ADR-028): no zone, no daylight saving.
 */
internal object LocalDays {
    /** Days from 1970-01-01. */
    fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** The year, month and day [epochDay] days after 1970-01-01. */
    fun civil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }

    /** "2026-10-27" as days from 1970-01-01; null for anything that isn't a real day in that form. */
    fun parse(day: String): Long? {
        if (day.length != DAY || day[4] != '-' || day[7] != '-') return null
        val year = day.take(4).toIntOrNull() ?: return null
        val month = day.substring(5, 7).toIntOrNull() ?: return null
        val date = day.substring(8, 10).toIntOrNull() ?: return null
        if (month !in 1..12 || date !in 1..31) return null
        return epochDay(year, month, date).takeIf { civil(it) == Triple(year, month, date) }
    }

    fun format(epochDay: Long): String {
        val (y, m, d) = civil(epochDay)
        return y.toString().padStart(4, '0') + "-" + m.toString().padStart(2, '0') + "-" + d.toString().padStart(2, '0')
    }

    fun plus(day: String, days: Int): String? = parse(day)?.let { format(it + days) }

    /** 1 for Monday to 7 for Sunday. */
    fun weekday(day: String): Int? = parse(day)?.let { (it + 3).mod(7) + 1 }

    /** "2026-10-27T07:05" or "…T07:05:30" as seconds from 1970-01-01T00:00; null for anything else. */
    fun seconds(time: String): Long? {
        if ((time.length != DAY + 6 && time.length != DAY + 9) || time[DAY] != 'T' || time[DAY + 3] != ':') return null
        val day = parse(time.take(DAY)) ?: return null
        val hour = time.substring(DAY + 1, DAY + 3).toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = time.substring(DAY + 4, DAY + 6).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val second = if (time.length == DAY + 6) {
            0
        } else {
            if (time[DAY + 6] != ':') return null
            time.substring(DAY + 7).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        }
        return day * SECONDS_A_DAY + hour * 3600 + minute * 60 + second
    }

    const val SECONDS_A_DAY = 86_400L
    private const val DAY = 10 // "YYYY-MM-DD"
}
