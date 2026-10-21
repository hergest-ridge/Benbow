package app.benbow.core.media

import app.benbow.core.api.StorageKind
import app.benbow.core.api.StorageMonth
import app.benbow.core.api.StorageReport
import app.benbow.core.api.StorageState

/**
 * What the files weigh (ADR-041, «Место»): each file once, by what it is and how alive it is, from who holds it
 * ([MediaRefs]). A file counts as the most alive of its holders (shown beats in the trash beats taken off) and as what
 * its strongest reference makes it (an attachment's kind before a preview, a preview before a name in the text); held
 * by nothing, it is unreferenced. Its month is the day of the entry that holds it most alive.
 */
object StorageCount {
    /** [files]: every file the store has, by SHA-256 with its size. The disks and backups are the caller's to add. */
    fun of(entries: Collection<MediaEntry>, files: Map<String, Long>): StorageReport {
        val refs = MediaRefs(entries)
        val byId = entries.associateBy { it.id }
        val kinds = LinkedHashMap<String, Long>()
        val states = LinkedHashMap<String, Long>()
        val months = HashMap<String, MutableMap<String, Long>>()
        for ((sha, size) in files) {
            val all = refs.of(sha)
            val holders = all.filter { it.holds }.sortedWith(compareBy({ STATES.indexOf(it.state) }, { ROLES.indexOf(it.role) }))
            val kind = kindOf(byId, (holders.ifEmpty { all }).minByOrNull { ROLES.indexOf(it.role) })
            val best = holders.firstOrNull()
            val state = when (best?.state) {
                null, MediaState.Purged -> StorageState.UNREFERENCED
                MediaState.Live -> StorageState.LIVE
                MediaState.Trash -> StorageState.TRASH
                MediaState.Removed -> StorageState.REMOVED
            }
            kinds[kind] = (kinds[kind] ?: 0) + size
            states[state] = (states[state] ?: 0) + size
            val month = best?.let { byId[it.entryId]?.day }?.take(7)?.takeIf { it.length == 7 } ?: continue
            val bucket = months.getOrPut(month) { LinkedHashMap() }
            bucket[kind] = (bucket[kind] ?: 0) + size
        }
        return StorageReport(
            files = files.size,
            bytes = files.values.sum(),
            kinds = kinds,
            states = states,
            months = months.entries.sortedBy { it.key }.map { StorageMonth(it.key, it.value) },
        )
    }

    private val STATES = listOf(MediaState.Live, MediaState.Trash, MediaState.Removed)
    private val ROLES = listOf(MediaRole.File, MediaRole.Preview, MediaRole.Text)

    private fun kindOf(entries: Map<String, MediaEntry>, ref: MediaRef?): String = when (ref?.role) {
        MediaRole.File -> ref.attachmentId?.let { entries[ref.entryId]?.attachments?.get(it)?.meta?.kind }?.let(StorageKind::of)
            ?: StorageKind.FILE

        MediaRole.Preview, MediaRole.Text -> StorageKind.PREVIEW

        null -> StorageKind.FILE
    }
}
