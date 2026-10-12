package app.benbow.server.backup

import app.benbow.server.storage.BackupRecord
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * GFS retention of data-safety.md#backups: everything from the last 24 h, the newest per day for
 * 14 days, per ISO week for 8 weeks, per month for 12 months, and pre-migration/pre-update
 * snapshots for 90 days. The newest verified snapshot is always kept.
 */
object Retention {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    fun keep(records: List<BackupRecord>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Set<String> {
        val verified = records.filter { it.verified }.sortedByDescending { it.createdAt }
        val keep = mutableSetOf<String>()
        verified.firstOrNull()?.let { keep += it.file }
        verified.filter { now - it.createdAt < DAY }.forEach { keep += it.file }
        verified.filter { it.reason.startsWith("pre-") && now - it.createdAt < 90 * DAY }.forEach { keep += it.file }
        fun newestPer(window: Long, key: (Instant) -> Any) = verified
            .filter { now - it.createdAt < window }
            .groupBy { key(Instant.ofEpochMilli(it.createdAt)) }
            .values.forEach { group -> keep += group.maxBy { it.createdAt }.file }
        newestPer(14 * DAY) { it.atZone(zone).toLocalDate() }
        newestPer(8 * 7 * DAY) { it.atZone(zone).let { d -> d.get(IsoFields.WEEK_BASED_YEAR) to d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) } }
        newestPer(366 * DAY) { it.atZone(zone).let { d -> d.year to d.monthValue } }
        // Unverified snapshots are never trusted, but never deleted automatically either.
        records.filter { !it.verified }.forEach { keep += it.file }
        return keep
    }
}
