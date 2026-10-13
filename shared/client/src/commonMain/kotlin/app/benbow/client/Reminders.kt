package app.benbow.client

import java.time.LocalDateTime
import java.time.ZoneId

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
            DAILY -> 1L
            WEEKLY -> 7L
            else -> return first.takeIf { !it.isBefore(now) }
        }
        if (!first.isBefore(now)) return first
        val behind = java.time.Duration.between(first, now).toDays() / step
        var next = first.plusDays(behind * step)
        while (next.isBefore(now)) next = next.plusDays(step)
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
fun List<ReminderItem>.upcoming(now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault(), days: Long = 30, limit: Int = 64): List<Due> =
    mapNotNull { r ->
        r.nextAt(now)?.takeIf { it.isBefore(now.plusDays(days)) }?.let { Due(r.id, r.text, it.atZone(zone).toInstant().toEpochMilli()) }
    }
        .sortedBy { it.atMillis }
        .take(limit)
