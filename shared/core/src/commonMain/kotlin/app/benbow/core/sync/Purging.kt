package app.benbow.core.sync

/**
 * Deleting for good (ADR-041, docs/data-safety.md "Purge"): what the server's purge does to an entry. Only the server
 * runs it; [SyncRules] then keeps it final: a purged link absorbs every later put of it, an entry marked [FIELD] takes
 * every new link already purged, and no device may write [FIELD]. The admin page's undo is the only way back.
 */
object Purging {
    /** An entry deleted for good: it leaves every device and the trash; its revisions stay on the server, unseen. */
    const val FIELD = "purged"

    fun isPurged(entry: EntryState): Boolean = entry.fields[FIELD]?.value == "true"

    /** A clock for the server's own writes to [entry]: now, and past every clock already on it. */
    fun clockFor(entry: EntryState, now: Long): Hlc {
        val latest = (entry.attachments.values.map { it.hlc } + entry.fields.values.map { it.hlc }).maxByOrNull { it } ?: Hlc.ZERO
        return Hlc(maxOf(now, latest.wall + 1), 0, SERVER_NODE)
    }

    /** [entry] with [links] taken off and purged (they hold no file any more) and, when [whole], the entry marked. */
    fun apply(entry: EntryState, links: Collection<String>, whole: Boolean, hlc: Hlc): EntryState {
        val purged = links.mapNotNull { id -> entry.attachments[id]?.let { id to it.copy(present = false, purged = true, hlc = hlc) } }
        val fields = if (whole) entry.fields + (FIELD to LwwValue("true", hlc)) else entry.fields
        return entry.copy(attachments = entry.attachments + purged, fields = fields)
    }

    /** Undoing within the quarantine: the links in [before] back as they were, at [hlc]; the entry unmarked when [whole]. */
    fun undo(entry: EntryState, before: Map<String, AttachmentState>, whole: Boolean, hlc: Hlc): EntryState {
        val back = before.filterKeys { it in entry.attachments }.mapValues { (_, state) -> state.copy(purged = false, hlc = hlc) }
        val fields = if (whole) entry.fields + (FIELD to LwwValue(null, hlc)) else entry.fields
        return entry.copy(attachments = entry.attachments + back, fields = fields)
    }

    private const val SERVER_NODE = "server"
}
