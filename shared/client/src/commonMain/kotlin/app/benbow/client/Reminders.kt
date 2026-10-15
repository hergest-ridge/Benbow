package app.benbow.client

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/** A reminder (an entry of kind "reminder"): [at] is local "YYYY-MM-DDTHH:MM", [repeat] daily, weekly or null. */
data class ReminderItem(val id: String, val text: String, val at: String, val repeat: String?, val done: Boolean) {
    /**
     * When it should next ring, at or after [now]. Repeats move forward from the first time instead of
     * rewriting the entry, so devices never argue over it. Null when it is done or a past one-off.
     */
    fun nextAt(now: LocalDateTime): LocalDateTime? {
        if (done) return null
        val first = runCatching { LocalDateTime.parse(at) }.getOrNull() ?: return null
        val step = when (repeat) {
            DAILY -> 1
            WEEKLY -> 7
            else -> return first.takeIf { it >= now }
        }
        if (first >= now) return first
        var next = first.plusDays(first.date.daysUntil(now.date) / step * step)
        while (next < now) next = next.plusDays(step)
        return next
    }

    companion object {
        const val DAILY = "daily"
        const val WEEKLY = "weekly"
    }
}

/** A reminder due at an instant, as a platform's alarm wants it. */
data class Due(val id: String, val text: String, val atMillis: Long)

/** The next [limit] rings within [days] from [now], soonest first. */
fun List<ReminderItem>.upcoming(
    now: LocalDateTime,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    days: Int = 30,
    limit: Int = 64,
): List<Due> = mapNotNull { r ->
    r.nextAt(now)?.takeIf { it < now.plusDays(days) }?.let { Due(r.id, r.text, it.toInstant(zone).toEpochMilliseconds()) }
}
    .sortedBy { it.atMillis }
    .take(limit)

private fun LocalDateTime.plusDays(days: Int) = LocalDateTime(date.plus(days, DateTimeUnit.DAY), time)
