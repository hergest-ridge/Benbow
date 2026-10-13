package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.benbow.core.content.toPlainText
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.HlcClock
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.PutRevision
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncOp
import app.benbow.core.uuidV7
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

data class DaySummary(val day: String, val entries: Int)

/** An op the server refused (data-safety S5): kept and shown until the user retries it. */
data class RejectedOp(val opId: String, val entryId: String, val error: String?)

data class EntrySummary(
    val id: String,
    val day: String,
    val createdAt: Long,
    val title: String,
    val preview: String,
    val conflict: Boolean,
    val mediaCount: Int = 0,
    /** SHA-256 of the picture to show for the entry (a preview or the image itself). */
    val cover: String? = null,
    val favorite: Boolean = false,
    val mood: Int? = null,
    val tags: List<String> = emptyList(),
)

data class TagCount(val name: String, val uses: Int, val category: String? = null)

/** An entry as the feed shows it: notes in full, journal entries as a preview. */
data class FeedItem(val summary: EntrySummary, val kind: String, val text: String, val reactions: Set<String>, val comments: Int = 0)

/** A calendar day: how many entries and their average mood (-2..2, null when no mood was set). */
data class DayInfo(val day: String, val entries: Int, val mood: Double?)

/** A calendar event; [time] is "HH:MM", null for all day. */
data class EventItem(val id: String, val day: String, val title: String, val time: String?)

/** A reply in an entry's thread, shown in full. */
data class Comment(val summary: EntrySummary, val text: String)

/** What to look for; empty text and off switches match everything outside the trash. */
data class SearchQuery(val text: String = "", val tag: String? = null, val favorites: Boolean = false, val withMedia: Boolean = false)

/** A file in the app's media folder, ready to be attached ([MediaStore.import]). */
data class MediaFile(val sha256: String, val size: Long, val mime: String, val path: String, val name: String?, val capturedAt: Long?)

/** What the editor shows: the draft when there is one, otherwise the head body. */
data class EntryDetail(
    val id: String,
    val kind: String,
    val day: String,
    val createdAt: Long,
    val body: String,
    val headRevision: String?,
    val fields: Map<String, String?>,
    val conflictHeads: List<String>,
    val hasDraft: Boolean,
    /** Present attachments in gallery order. */
    val attachments: List<AttachmentState> = emptyList(),
) {
    val inTrash: Boolean get() = fields[TRASH_FIELD] == "true"
    val favorite: Boolean get() = fields[FAVORITE_FIELD] == "true"
    val pinned: Boolean get() = fields[PINNED_FIELD] == "true"

    /** For comments: the entry they answer. */
    val parentId: String? get() = fields[PARENT_FIELD]
    val mood: Int? get() = fields[MOOD_FIELD]?.toIntOrNull()
    val tags: List<String> get() = tagsOf(fields)
    val reactions: Set<String> get() = fields.filter {
        it.key.startsWith(REACTION_PREFIX) && it.value != null
    }.keys.map { it.removePrefix(REACTION_PREFIX) }.toSet()
}

// Entry metadata as namespaced LWW fields (ADR-021).
internal const val TRASH_FIELD = "deleted"
internal const val FAVORITE_FIELD = "favorite"
internal const val MOOD_FIELD = "mood"
internal const val PINNED_FIELD = "pinned"
internal const val PARENT_FIELD = "parent"
internal const val START_FIELD = "start"
internal const val AT_FIELD = "at"
internal const val REPEAT_FIELD = "repeat"
internal const val DONE_FIELD = "done"

/** The tag registry (ADR-021): a system entry whose fields are `cat:<tag key>` = category. Fixed id, so devices share it. */
internal const val TAG_REGISTRY_ID = "system-tags"
internal const val CATEGORY_PREFIX = "cat:"
internal const val TAG_PREFIX = "tag:"
internal const val REACTION_PREFIX = "reaction:"

/** Display names of the entry's tags, alphabetically. */
internal fun tagsOf(fields: Map<String, String?>): List<String> =
    fields.filter { it.key.startsWith(TAG_PREFIX) }.values.filterNotNull().sortedBy { it.lowercase() }

/** "#Работа " → "Работа"; empty when nothing usable is left. Quotes can't be in tags (they'd break the JSON path). */
fun tagName(input: String): String = input.trim().trimStart('#').replace('"', ' ').replace(Regex("""\s+"""), " ").trim()

internal fun tagKey(name: String): String = TAG_PREFIX + tagName(name).lowercase()

private val fieldsSerializer = MapSerializer(String.serializer(), String.serializer().nullable)

internal val clocksSerializer = MapSerializer(String.serializer(), Hlc.serializer())

/**
 * Offline-first journal data. Every write is one local transaction that also queues the sync op
 * (docs/sync.md#client-local-state), so nothing typed is lost when the server is unreachable.
 */
class JournalRepository(
    private val db: BenbowDb,
    private val now: () -> Long,
    private val context: CoroutineContext = Dispatchers.Default,
    private val random: Random = Random.Default,
) {
    private val q get() = db.benbowQueries
    private val clock = ClientClock(db, now)

    /** All entries outside the trash: newest day first, each day in the order it was written. */
    fun journal(): Flow<List<EntrySummary>> = q.journal(::summaryOf).asFlow().mapToList(context)

    fun days(): Flow<List<DaySummary>> = journal().map { entries ->
        entries.groupBy { it.day }.map { (day, items) -> DaySummary(day, items.size) }
    }

    fun entriesForDay(day: String): Flow<List<EntrySummary>> = q.entriesForDay(day, ::summaryOf).asFlow().mapToList(context)

    /** Most recently trashed first. */
    fun trash(): Flow<List<EntrySummary>> = q.trash(::summaryOf).asFlow().mapToList(context)

    fun entry(id: String): Flow<EntryDetail?> = combine(
        q.selectEntry(id).asFlow().mapToOneOrNull(context),
        q.selectDraft(id).asFlow().mapToOneOrNull(context),
    ) { row, draft ->
        row?.let {
            EntryDetail(
                id = it.id,
                kind = it.kind,
                day = it.day,
                createdAt = it.created_at,
                body = draft?.body ?: it.body,
                headRevision = it.head_rev,
                fields = decodeFields(it.fields),
                conflictHeads = ClientJson.decodeFromString(it.conflict_heads),
                hasDraft = draft != null,
                attachments = decodeAttachments(it.attachments).values.filter { a -> a.present }.sortedBy { a -> a.position },
            )
        }
    }

    fun createEntry(day: String, kind: String = "journal", body: String = emptyBody(random)): String {
        val time = now()
        val id = newId()
        val revision = newId()
        val (title, preview) = summarize(body)
        db.transaction {
            q.upsertEntry(id, kind, day, time, revision, body, title, preview, "[]", "{}", time, "{}", "{}", 0, null)
            SearchIndex.update(db, id)
            enqueue(CreateEntry(newId(), id, kind, day, time))
            enqueue(PutRevision(newId(), id, revision, emptyList(), body))
        }
        return id
    }

    /** Cheap autosave target for the editor (data-safety S12); not synced until [commitDraft]. */
    fun saveDraft(id: String, body: String) {
        db.transaction {
            val entry = q.selectEntry(id).executeAsOneOrNull() ?: return@transaction
            val base = q.selectDraft(id).executeAsOneOrNull()?.base_rev ?: entry.head_rev
            q.upsertDraft(id, body, base, now())
        }
    }

    /** Quick capture: every line of [text] becomes a paragraph. */
    fun createNote(day: String, text: String): String = createEntry(day, kind = "note", body = textBody(text, random))

    /** Turns the draft into a revision (docs/sync.md#revisions-from-drafts). Returns true if one was created. */
    fun commitDraft(id: String): Boolean = db.transactionWithResult { commitDraftInTransaction(id) }

    /** Drafts left behind by a crash or a killed app would never sync; called at startup, before any editor opens. */
    fun commitLeftoverDrafts(): Int = db.transactionWithResult { q.allDrafts().executeAsList().count { commitDraftInTransaction(it) } }

    /**
     * Makes [body] (an older or a concurrent version) the new head. Any draft is committed first, so
     * nothing typed is lost, and the new revision lists every conflicting head as a parent: picking a
     * version is how the user resolves a conflict (docs/sync.md#revision-conflicts-server).
     */
    fun restoreVersion(id: String, body: String) {
        db.transaction {
            commitDraftInTransaction(id)
            val entry = q.selectEntry(id).executeAsOneOrNull() ?: return@transaction
            val conflicts: List<String> = ClientJson.decodeFromString(entry.conflict_heads)
            if (body == entry.body && conflicts.isEmpty()) return@transaction
            val revision = newId()
            enqueue(PutRevision(newId(), id, revision, (listOfNotNull(entry.head_rev) + conflicts).distinct(), body))
            putHead(id, revision, body)
            q.clearConflicts(id)
        }
    }

    fun setField(id: String, field: String, value: String?) {
        db.transaction {
            val entry = q.selectEntry(id).executeAsOneOrNull() ?: return@transaction
            val hlc = clock.next()
            enqueue(SetField(newId(), id, field, value, hlc))
            q.updateFields(
                ClientJson.encodeToString(fieldsSerializer, decodeFields(entry.fields) + (field to value)),
                ClientJson.encodeToString(clocksSerializer, decodeClocks(entry.field_clocks) + (field to hlc)),
                now(),
                id,
            )
            SearchIndex.update(db, id)
            if (field == PARENT_FIELD) q.updateParent(value, id)
        }
    }

    /**
     * Links [file] (already in the app's media folder) to the entry. One transaction records the file as
     * captured here (kept until the server confirms it), queues the link and shows it right away.
     */
    fun attach(
        entryId: String,
        file: MediaFile,
        kind: String,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        preview: MediaFile? = null,
    ): String = db.transactionWithResult {
        val entry = q.selectEntry(entryId).executeAsOneOrNull() ?: error("No entry $entryId")
        for (f in listOfNotNull(file, preview)) q.putMedia(f.sha256, f.path, f.size, f.mime, "captured", 0, f.name, f.capturedAt, now())
        val current = decodeAttachments(entry.attachments)
        val meta = AttachmentMeta(
            id = newId(),
            sha256 = file.sha256,
            size = file.size,
            mime = file.mime,
            kind = kind,
            name = file.name,
            width = width,
            height = height,
            durationMs = durationMs,
            capturedAt = file.capturedAt,
            previewSha256 = preview?.sha256,
        )
        val position = (current.values.maxOfOrNull { it.position } ?: 0.0) + 1
        val state = AttachmentState(meta, position, hlc = clock.next())
        enqueue(PutAttachment(newId(), entryId, meta, state.position, state.caption, state.present, state.hlc))
        putAttachments(entryId, current + (meta.id to state))
        meta.id
    }

    /** Hides the attachment (S6: the file and the link's history stay on the server). */
    fun removeAttachment(entryId: String, attachmentId: String) {
        db.transaction {
            val entry = q.selectEntry(entryId).executeAsOneOrNull() ?: return@transaction
            val current = decodeAttachments(entry.attachments)
            val state = current[attachmentId]?.copy(present = false, hlc = clock.next()) ?: return@transaction
            enqueue(PutAttachment(newId(), entryId, state.meta, state.position, state.caption, state.present, state.hlc))
            putAttachments(entryId, current + (attachmentId to state))
        }
    }

    private fun putAttachments(entryId: String, attachments: Map<String, AttachmentState>) {
        val (count, cover) = mediaSummary(attachments)
        q.updateAttachments(ClientJson.encodeToString(attachmentsSerializer, attachments), count.toLong(), cover, now(), entryId)
    }

    fun setFavorite(id: String, favorite: Boolean) = setField(id, FAVORITE_FIELD, if (favorite) "true" else null)

    /** A reply to [parentId] (also to an old entry, as a later look back): its own entry, linked by the "parent" field. */
    fun createComment(parentId: String, day: String, text: String): String = db.transactionWithResult {
        val id = createEntry(day, kind = "comment", body = textBody(text, random))
        setField(id, PARENT_FIELD, parentId)
        id
    }

    fun comments(parentId: String): Flow<List<Comment>> =
        q.commentsOf(parentId) { id, day, createdAt, title, preview, conflictHeads, mediaCount, cover, fields, body ->
            Comment(summaryOf(id, day, createdAt, title, preview, conflictHeads, mediaCount, cover, fields), plainText(body))
        }.asFlow().mapToList(context)

    fun commentCount(parentId: String): Flow<Long> = q.commentCount(parentId).asFlow().mapToOne(context)

    // Calendar (5.1)

    /** Days of [fromDay]..[toDay] that have entries, keyed by day. */
    fun monthDays(fromDay: String, toDay: String): Flow<Map<String, DayInfo>> =
        q.monthDays(fromDay, toDay) { day, entries, mood -> DayInfo(day, entries.toInt(), mood) }.asFlow().mapToList(context)
            .map { days -> days.associateBy { it.day } }

    fun events(fromDay: String, toDay: String): Flow<List<EventItem>> = q.events(fromDay, toDay) { id, day, title, start ->
        EventItem(id, day, title, start?.substringAfter('T', "")?.takeIf { it.isNotEmpty() })
    }.asFlow().mapToList(context)

    /** An event on [day], at [time] ("HH:MM") or all day: an entry of kind "event" whose text is the title. */
    fun createEvent(day: String, title: String, time: String?): String = db.transactionWithResult {
        val id = createEntry(day, kind = "event", body = textBody(title, random))
        setField(id, START_FIELD, time?.let { "${day}T$it" } ?: day)
        id
    }

    // Reminders (5.2)

    fun reminders(): Flow<List<ReminderItem>> = q.reminders { id, title, at, repeat, done ->
        ReminderItem(id, title, at.orEmpty(), repeat, done == "true")
    }.asFlow().mapToList(context)

    /** [at] is local "YYYY-MM-DDTHH:MM"; the entry's day is its date, so the calendar shows it there. */
    fun createReminder(text: String, at: String, repeat: String?): String = db.transactionWithResult {
        val id = createEntry(at.substringBefore('T'), kind = "reminder", body = textBody(text, random))
        setField(id, AT_FIELD, at)
        if (repeat != null) setField(id, REPEAT_FIELD, repeat)
        id
    }

    fun setReminderDone(id: String, done: Boolean) = setField(id, DONE_FIELD, if (done) "true" else null)

    fun setPinned(id: String, pinned: Boolean) = setField(id, PINNED_FIELD, if (pinned) "true" else null)

    /** -2 (hard) .. 2 (great), null = not set. */
    fun setMood(id: String, mood: Int?) = setField(id, MOOD_FIELD, mood?.coerceIn(-2, 2)?.toString())

    fun addTag(id: String, name: String) {
        val clean = tagName(name)
        if (clean.isNotEmpty()) setField(id, tagKey(clean), clean)
    }

    fun removeTag(id: String, name: String) = setField(id, tagKey(name), null)

    fun toggleReaction(id: String, emoji: String, on: Boolean) = setField(id, REACTION_PREFIX + emoji, if (on) "1" else null)

    // Home (4.2)

    fun pinned(): Flow<List<EntrySummary>> = q.pinned(::summaryOf).asFlow().mapToList(context)

    /** Entries with typing not yet turned into a revision ("continue writing"). */
    fun withDrafts(): Flow<List<EntrySummary>> = q.withDrafts(::summaryOf).asFlow().mapToList(context)

    /** The same date in earlier years; [today] is "YYYY-MM-DD". */
    fun onThisDay(today: String): Flow<List<EntrySummary>> = q.onThisDay(today.substring(4), today, ::summaryOf).asFlow().mapToList(context)

    fun favoritesUpTo(lastDay: String): Flow<List<EntrySummary>> = q.favoritesUpTo(lastDay, ::summaryOf).asFlow().mapToList(context)

    fun daysWritten(fromDay: String, toDay: String): Flow<Long> = q.daysWritten(fromDay, toDay).asFlow().mapToOne(context)

    /** Newest first; the feed draws it bottom-up like a chat. */
    fun feed(limit: Long = 300): Flow<List<FeedItem>> =
        q.feed(limit) { id, kind, day, createdAt, title, preview, body, conflictHeads, mediaCount, cover, fields, comments ->
            val summary = summaryOf(id, day, createdAt, title, preview, conflictHeads, mediaCount, cover, fields)
            val text = if (kind == "journal") {
                listOf(title, preview).filter { it.isNotBlank() }.joinToString("\n")
            } else {
                plainText(body).take(FEED_TEXT_MAX)
            }
            val reactions = decodeFields(fields).filter { it.key.startsWith(REACTION_PREFIX) && it.value != null }.keys
            FeedItem(summary, kind, text, reactions.map { it.removePrefix(REACTION_PREFIX) }.toSet(), comments.toInt())
        }.asFlow().mapToList(context)

    /** Builds the search index after an update changed it (or on the first start); quick otherwise. */
    fun prepareSearch() = SearchIndex.rebuildIfNeeded(db)

    /** Every tag in use (outside the trash), most used first, with its category: suggestions, filters, the tag list. */
    fun tags(): Flow<List<TagCount>> = combine(
        q.tagCounts { name, uses -> TagCount(name.orEmpty(), uses.toInt()) }.asFlow().mapToList(context),
        q.selectEntry(TAG_REGISTRY_ID).asFlow().mapToOneOrNull(context),
    ) { counts, registry ->
        val categories = registry?.let { decodeFields(it.fields) }.orEmpty()
        counts.filter { it.name.isNotEmpty() }.map { it.copy(category = categories[CATEGORY_PREFIX + tagName(it.name).lowercase()]) }
    }

    /** Puts a tag into a category (null = none). Created once, without revisions, so two devices never conflict over it. */
    fun setTagCategory(tag: String, category: String?) {
        db.transaction {
            if (q.selectEntry(TAG_REGISTRY_ID).executeAsOneOrNull() == null) {
                val time = now()
                q.upsertEntry(
                    TAG_REGISTRY_ID, "system", "1970-01-01", time, null,
                    emptyBody(
                        random,
                    ),
                    "", "", "[]", "{}", time, "{}", "{}", 0, null,
                )
                enqueue(CreateEntry(newId(), TAG_REGISTRY_ID, "system", "1970-01-01", time))
            }
            setField(TAG_REGISTRY_ID, CATEGORY_PREFIX + tagName(tag).lowercase(), category?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    fun search(query: SearchQuery): Flow<List<EntrySummary>> = q.search(
        match = SearchIndex.match(query.text),
        tagPath = query.tag?.let { "$.\"${tagKey(it)}\"" },
        favorites = if (query.favorites) 1 else 0,
        withMedia = if (query.withMedia) 1 else 0,
        mapper = ::summaryOf,
    ).asFlow().mapToList(context)

    fun moveToTrash(id: String) = setField(id, TRASH_FIELD, "true")

    fun restore(id: String) = setField(id, TRASH_FIELD, null)

    /** Ops not yet acknowledged by the server, and ops it rejected (shown to the user, never dropped). */
    fun pendingCount(): Flow<Long> = q.pendingCount().asFlow().mapToOne(context)

    fun rejectedCount(): Flow<Long> = q.rejectedCount().asFlow().mapToOne(context)

    fun rejectedOps(): Flow<List<RejectedOp>> =
        q.rejectedOps().asFlow().mapToList(context).map { rows -> rows.map { RejectedOp(it.op_id, it.entry_id, it.last_error) } }

    /** Puts rejected ops back in the queue (e.g. after a server update fixed the cause). */
    fun retryRejected() {
        q.retryRejected()
    }

    private fun commitDraftInTransaction(id: String): Boolean {
        val draft = q.selectDraft(id).executeAsOneOrNull() ?: return false
        val entry = q.selectEntry(id).executeAsOneOrNull() ?: return false
        q.deleteDraft(id)
        if (draft.body == entry.body) return false
        val revision = newId()
        enqueue(PutRevision(newId(), id, revision, listOfNotNull(draft.base_rev), draft.body))
        putHead(id, revision, draft.body)
        return true
    }

    private fun putHead(id: String, revision: String, body: String) {
        val (title, preview) = summarize(body)
        q.updateHead(revision, body, title, preview, now(), id)
        SearchIndex.update(db, id)
    }

    private fun enqueue(op: SyncOp) = q.enqueue(op.opId, op.entryId, ClientJson.encodeToString(SyncOp.serializer(), op), now())

    private fun newId() = uuidV7(now(), random)

    companion object {
        fun emptyBody(random: Random = Random.Default): String = textBody("", random)

        fun textBody(text: String, random: Random = Random.Default): String = DocumentCodec.encode(
            Document(text.trim().lines().map { TextBlock(uuidV7(0, random).takeLast(8), children = listOf(Span(it))) }),
        )
    }
}

@Suppress("UNUSED_PARAMETER")
private fun summaryOf(
    id: String,
    day: String,
    createdAt: Long,
    title: String,
    preview: String,
    conflictHeads: String,
    mediaCount: Long,
    cover: String?,
    fields: String,
) = decodeFields(fields).let { f ->
    EntrySummary(
        id = id,
        day = day,
        createdAt = createdAt,
        title = title,
        preview = preview,
        conflict = conflictHeads != "[]",
        mediaCount = mediaCount.toInt(),
        cover = cover,
        favorite = f[FAVORITE_FIELD] == "true",
        mood = f[MOOD_FIELD]?.toIntOrNull(),
        tags = tagsOf(f),
    )
}

internal val attachmentsSerializer = MapSerializer(String.serializer(), AttachmentState.serializer())

internal fun decodeAttachments(json: String): Map<String, AttachmentState> = ClientJson.decodeFromString(attachmentsSerializer, json)

/** How many attachments are visible, and which picture stands for the entry in lists. */
internal fun mediaSummary(attachments: Map<String, AttachmentState>): Pair<Int, String?> {
    val visible = attachments.values.filter { it.present }.sortedBy { it.position }
    val cover = visible.firstNotNullOfOrNull { a ->
        a.meta.previewSha256 ?: a.meta.sha256.takeIf { a.meta.kind == "image" }
    }
    return visible.size to cover
}

/** Title = the first paragraph, preview = the rest as one line (spoilers hidden). */
internal fun summarize(body: String): Pair<String, String> {
    val document = try {
        DocumentCodec.decode(body)
    } catch (_: DocumentFormatException) {
        return "" to ""
    }
    val title = (document.blocks.firstOrNull() as? TextBlock)?.children?.joinToString("") { it.text }.orEmpty().trim()
    val rest = document.toPlainText(includeSpoilers = false).trim().removePrefix(title)
    return title.take(TITLE_MAX) to rest.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(PREVIEW_MAX)
}

private fun plainText(body: String): String = try {
    DocumentCodec.decode(body).toPlainText(includeSpoilers = false).trim()
} catch (_: DocumentFormatException) {
    ""
}

private const val FEED_TEXT_MAX = 4_000
private const val TITLE_MAX = 120
private const val PREVIEW_MAX = 200

internal fun decodeFields(json: String): Map<String, String?> = ClientJson.decodeFromString(fieldsSerializer, json)

internal fun decodeClocks(json: String): Map<String, Hlc> = ClientJson.decodeFromString(clocksSerializer, json)

/** HLC whose last value survives restarts (kept in the local settings). */
internal class ClientClock(private val db: BenbowDb, now: () -> Long) {
    private val settings = Settings(db)
    private val node = settings.get("device.id") ?: settings.get(LOCAL_NODE) ?: uuidV7(now()).also { settings.put(LOCAL_NODE, it) }
    private val clock = HlcClock(node, settings.get(Settings.HLC)?.let { ClientJson.decodeFromString(Hlc.serializer(), it) }, now)

    fun next(): Hlc = clock.next().also(::persist)

    fun observe(remote: Hlc) {
        clock.observe(remote)
        persist(clock.current)
    }

    private fun persist(hlc: Hlc) = settings.put(Settings.HLC, ClientJson.encodeToString(Hlc.serializer(), hlc))

    private companion object {
        const val LOCAL_NODE = "device.local_node"
    }
}
