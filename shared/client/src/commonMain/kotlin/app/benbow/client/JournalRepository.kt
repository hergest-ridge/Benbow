package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.audio.TrackTags
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.Span
import app.benbow.core.content.TagNames
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Instant

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
    /** As they read (merged ones once, none from the trash), once a list has been through [GentleRules.apply]. */
    val tags: List<TagRef> = emptyList(),
    /** The ids of the tags the entry carries, whatever became of them (Бережно's rules go by these). */
    val tagIds: List<String> = emptyList(),
    /** A Keep-like note color, one of [SWATCHES]; null = the theme's own. */
    val background: String? = null,
    /** Бережно (ADR-031): what it shows as, its own mark (set by hand), and what the rules alone would give. */
    val veil: Veil? = null,
    val ownVeil: Veil? = null,
    val ruleVeil: Veil? = null,
    /** "Отложить до…": quiet until this day ("YYYY-MM-DD"), then back with a card on Home until the owner answers it. */
    val until: String? = null,
)

/** A tag: [uses] entries outside the trash (merged tags' too); [category] is the category's id. */
data class TagCount(val id: String, val name: String, val uses: Int, val category: String? = null, val archived: Boolean = false)

/** An entry as the feed shows it: notes in full, journal entries as a preview; [media] = its pictures and videos in order. */
data class FeedItem(
    val summary: EntrySummary,
    val kind: String,
    val text: String,
    val reactions: Set<String>,
    val comments: Int = 0,
    val media: List<AttachmentState> = emptyList(),
    /** The document itself (Benbow Document JSON), for the feed's light formatting. */
    val body: String = "",
    /** Voice notes and sounds, in gallery order. */
    val audio: List<AttachmentState> = emptyList(),
    /** What was said in each voice note or sound, by attachment id (7.6). */
    val transcripts: Map<String, String> = emptyMap(),
    /** What each sound file says it is (ADR-035), by attachment id. */
    val tracks: Map<String, TrackTags> = emptyMap(),
)

/**
 * A calendar day: how many entries and their average mood (-2..2, null when no mood was set); [openMood] leaves out
 * quiet entries (ADR-031), so a hard day kept out of the way doesn't tint the month either.
 */
data class DayInfo(val day: String, val entries: Int, val mood: Double?, val openMood: Double? = mood)

/** A reply in an entry's thread, shown in full, with its pictures and files ([media], in order). */
data class Comment(
    val summary: EntrySummary,
    val text: String,
    /** The document itself (Benbow Document JSON), drawn like a feed post (spoilers stay hidden, not dropped). */
    val body: String = "",
    val media: List<AttachmentState> = emptyList(),
    /** What was said in each voice note or sound, by attachment id (7.6). */
    val transcripts: Map<String, String> = emptyMap(),
    /** What each sound file says it is (ADR-035), by attachment id. */
    val tracks: Map<String, TrackTags> = emptyMap(),
)

/** What to look for; empty text and off switches match everything outside the trash. [tag] is a tag's id. */
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
    /** As they read ([entry] reads them through the tag registry). */
    val tags: List<TagRef> = tagsOf(fields),
) {
    val inTrash: Boolean get() = fields[TRASH_FIELD] == "true"
    val favorite: Boolean get() = fields[FAVORITE_FIELD] == "true"
    val pinned: Boolean get() = fields[PINNED_FIELD] == "true"

    /** For comments: the entry they answer. */
    val parentId: String? get() = fields[PARENT_FIELD]
    val mood: Int? get() = fields[MOOD_FIELD]?.toIntOrNull()
    val background: String? get() = fields[BACKGROUND_FIELD]?.takeIf { it in SWATCHES }

    /** Бережно: the entry's own mark (set by hand); the rules may add theirs ([GentleRules.ruled]). */
    val ownVeil: Veil? get() = Veil.of(fields[VEIL_FIELD])
    val until: String? get() = fields[VEIL_UNTIL_FIELD]

    /** The ids of the tags the entry carries, whatever became of them (Бережно's rules go by these). */
    val tagIds: List<String> get() = tagsOf(fields).map { it.id }
    val reactions: Set<String> get() = fields.filter {
        it.key.startsWith(REACTION_PREFIX) && it.value != null
    }.keys.map { it.removePrefix(REACTION_PREFIX) }.toSet()

    /** What was said in each voice note or sound, by attachment id (7.6). */
    val transcripts: Map<String, String> get() = transcriptsOf(fields)

    /** What each sound file says it is (ADR-035), by attachment id. */
    val tracks: Map<String, TrackTags> get() = TrackTags.of(fields)
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
internal const val BACKGROUND_FIELD = "background"
internal const val VEIL_FIELD = "veil"
internal const val VEIL_UNTIL_FIELD = "veil_until"

/** Attachments shown as pictures (a video by its preview frame). */
private val VISUAL_KINDS = setOf("image", "video", "drawing")

/** The tag registry (ADR-021, ADR-032): a system entry whose fields name, sort and color tags by id. Fixed id, so devices share it. */
internal const val TAG_REGISTRY_ID = TagNames.REGISTRY_ID
internal const val TAG_PREFIX = TagNames.TAG_PREFIX
internal const val REACTION_PREFIX = "reaction:"

/** `transcript:<attachment id>` = the text of a voice note or sound, recognized on a phone (7.6); synced like a tag. */
internal const val TRANSCRIPT_PREFIX = "transcript:"

internal fun transcriptsOf(fields: Map<String, String?>): Map<String, String> = fields.mapNotNull { (key, value) ->
    if (key.startsWith(TRANSCRIPT_PREFIX) &&
        value != null
    ) {
        key.removePrefix(TRANSCRIPT_PREFIX) to value
    } else {
        null
    }
}.toMap()

/** The tags the entry carries (`tag:<id>` = the name it was put on with), by that name; [TagRegistry.resolve] gives how they read. */
internal fun tagsOf(fields: Map<String, String?>): List<TagRef> = fields.mapNotNull { (key, value) ->
    if (key.startsWith(TAG_PREFIX) && value != null) TagRef(key.removePrefix(TAG_PREFIX), value) else null
}.sortedBy { it.name.lowercase() }

/** "#Работа " → "Работа"; empty when nothing usable is left. Quotes can't be in tags (they'd break the JSON path). */
fun tagName(input: String): String = input.trim().trimStart('#').replace('"', ' ').replace(Regex("""\s+"""), " ").trim()

private val fieldsSerializer = MapSerializer(String.serializer(), String.serializer().nullable)

internal val clocksSerializer = MapSerializer(String.serializer(), Hlc.serializer())

/**
 * Offline-first journal data. Every write is one local transaction that also queues the sync op
 * (docs/sync.md#client-local-state), so nothing typed is lost when the server is unreachable.
 */
class JournalRepository(
    internal val db: BenbowDb,
    internal val now: () -> Long,
    internal val context: CoroutineContext = Dispatchers.Default,
    private val random: Random = Random.Default,
    /** The journal's day now (the app's starts at 04:00); "Отложить до…" compares with it. */
    private val today: () -> String = {
        Instant.fromEpochMilliseconds(now()).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
    },
) {
    internal val q get() = db.benbowQueries
    private val clock = ClientClock(db, now)

    /** All entries outside the trash: newest day first, each day in the order it was written. */
    fun journal(): Flow<List<EntrySummary>> = q.journal(::summaryOf).asFlow().mapToList(context).veiled()

    fun days(): Flow<List<DaySummary>> = journal().map { entries ->
        entries.groupBy { it.day }.map { (day, items) -> DaySummary(day, items.size) }
    }

    fun entriesForDay(day: String): Flow<List<EntrySummary>> = q.entriesForDay(day, ::summaryOf).asFlow().mapToList(context).veiled()

    /** Most recently trashed first. */
    fun trash(): Flow<List<EntrySummary>> = q.trash(::summaryOf).asFlow().mapToList(context).veiled()

    fun entry(id: String): Flow<EntryDetail?> = combine(
        q.selectEntry(id).asFlow().mapToOneOrNull(context),
        q.selectDraft(id).asFlow().mapToOneOrNull(context),
        tagRegistry(),
    ) { row, draft, registry ->
        row?.let {
            val fields = decodeFields(it.fields)
            EntryDetail(
                id = it.id,
                kind = it.kind,
                day = it.day,
                createdAt = it.created_at,
                body = draft?.body ?: it.body,
                headRevision = it.head_rev,
                fields = fields,
                conflictHeads = ClientJson.decodeFromString(it.conflict_heads),
                hasDraft = draft != null,
                attachments = galleryOrder(decodeAttachments(it.attachments).values, draft?.body ?: it.body),
                tags = registry.resolve(tagsOf(fields)),
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
            val before = decodeFields(entry.fields)
            enqueue(SetField(newId(), id, field, value, hlc))
            q.updateFields(
                ClientJson.encodeToString(fieldsSerializer, before + (field to value)),
                ClientJson.encodeToString(clocksSerializer, decodeClocks(entry.field_clocks) + (field to hlc)),
                now(),
                id,
            )
            SearchIndex.update(db, id)
            if (id == TAG_REGISTRY_ID && TagNames.shapesTags(field)) {
                SearchIndex.retag(db, TagNames.changed(TagNames.of(before), TagNames.of(before + (field to value))))
            }
            if (field == PARENT_FIELD) q.updateParent(value, id)
            if (field == Moments.FIELD) Moments.apply(db, id, mapOf(field to value))
        }
    }

    /** Moves the entry to another day and time (the `when` field); it shows there on every device. */
    fun moveTo(id: String, moment: LocalDateTime) = setField(id, Moments.FIELD, Moments.format(moment))

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
        track: TrackTags? = null,
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
        // A sound's tags (ADR-035) go with it, after the link, so a device never sees them without the file.
        if (track != null && !track.isEmpty) setField(entryId, TrackTags.FIELD_PREFIX + meta.id, track.encode())
        meta.id
    }

    /** Hides the attachment (S6: the file and the link's history stay on the server). */
    fun removeAttachment(entryId: String, attachmentId: String) = setPresent(entryId, attachmentId, false)

    /** Shows a hidden attachment again ("Вернуть"). */
    fun restoreAttachment(entryId: String, attachmentId: String) = setPresent(entryId, attachmentId, true)

    private fun setPresent(entryId: String, attachmentId: String, present: Boolean) {
        db.transaction {
            val entry = q.selectEntry(entryId).executeAsOneOrNull() ?: return@transaction
            val current = decodeAttachments(entry.attachments)
            val state = current[attachmentId]?.copy(present = present, hlc = clock.next()) ?: return@transaction
            enqueue(PutAttachment(newId(), entryId, state.meta, state.position, state.caption, state.present, state.hlc))
            putAttachments(entryId, current + (attachmentId to state))
        }
    }

    /** Whether the entry is on this device (in the trash too). */
    fun hasEntry(id: String): Boolean = q.selectEntry(id).executeAsOneOrNull() != null

    /**
     * Puts an attachment between two others in the hand-made order ([after] / [before], null = that end). Pictures
     * placed in the text keep the text's order; this only orders the rest.
     */
    fun moveAttachment(entryId: String, attachmentId: String, after: String?, before: String?) {
        db.transaction {
            val entry = q.selectEntry(entryId).executeAsOneOrNull() ?: return@transaction
            val current = decodeAttachments(entry.attachments)
            val state = current[attachmentId] ?: return@transaction
            val low = after?.let { current[it]?.position }
            val high = before?.let { current[it]?.position }
            val position = when {
                low != null && high != null -> (low + high) / 2
                low != null -> low + 1
                high != null -> high - 1
                else -> return@transaction
            }
            val moved = state.copy(position = position, hlc = clock.next())
            enqueue(PutAttachment(newId(), entryId, moved.meta, moved.position, moved.caption, moved.present, moved.hlc))
            putAttachments(entryId, current + (attachmentId to moved))
        }
    }

    /** Names an attachment (its caption: a sound's title, a picture's label); blank = back to the default. */
    fun setCaption(entryId: String, attachmentId: String, caption: String?) {
        db.transaction {
            val entry = q.selectEntry(entryId).executeAsOneOrNull() ?: return@transaction
            val current = decodeAttachments(entry.attachments)
            val state = current[attachmentId] ?: return@transaction
            val clean = caption?.trim()?.takeIf { it.isNotEmpty() }
            if (clean == state.caption) return@transaction
            val named = state.copy(caption = clean, hlc = clock.next())
            enqueue(PutAttachment(newId(), entryId, named.meta, named.position, named.caption, named.present, named.hlc))
            putAttachments(entryId, current + (attachmentId to named))
        }
    }

    private fun putAttachments(entryId: String, attachments: Map<String, AttachmentState>) {
        val (count, cover) = mediaSummary(attachments)
        q.updateAttachments(ClientJson.encodeToString(attachmentsSerializer, attachments), count.toLong(), cover, now(), entryId)
        SearchIndex.update(db, entryId) // names of sounds are searchable
    }

    /** Voice notes and sounds outside the trash that have no text yet, with their entries (7.6); music (ADR-035) isn't. */
    fun untranscribed(): List<Pair<String, AttachmentState>> = q.allEntryIds().executeAsList().flatMap(::untranscribedIn)

    /** The same for one entry. */
    fun untranscribedIn(id: String): List<Pair<String, AttachmentState>> {
        val entry = q.selectEntry(id).executeAsOneOrNull() ?: return emptyList()
        val fields = decodeFields(entry.fields)
        if (fields[TRASH_FIELD] == "true") return emptyList()
        val done = transcriptsOf(fields).keys + TrackTags.of(fields).filterValues { it.isMusic }.keys
        return decodeAttachments(entry.attachments).values
            .filter { it.present && (it.meta.kind == "voice" || it.meta.kind == "audio") && it.meta.id !in done }
            .map { id to it }
    }

    /** Keeps what was said in a voice note or sound; blank removes it. */
    fun setTranscript(entryId: String, attachmentId: String, text: String?) =
        setField(entryId, TRANSCRIPT_PREFIX + attachmentId, text?.trim()?.takeIf { it.isNotEmpty() })

    fun setFavorite(id: String, favorite: Boolean) = setField(id, FAVORITE_FIELD, if (favorite) "true" else null)

    /** A reply to [parentId] (also to an old entry, as a later look back): its own entry, linked by the "parent" field. */
    fun createComment(parentId: String, day: String, text: String): String = db.transactionWithResult {
        val id = createEntry(day, kind = "comment", body = textBody(text, random))
        setField(id, PARENT_FIELD, parentId)
        id
    }

    fun comments(parentId: String): Flow<List<Comment>> =
        q.commentsOf(parentId) { id, day, createdAt, title, preview, conflictHeads, mediaCount, cover, fields, body, attachments ->
            val media = galleryOrder(decodeAttachments(attachments).values, body)
            val summary = summaryOf(id, day, createdAt, title, preview, conflictHeads, mediaCount, cover, fields)
            val decoded = decodeFields(fields)
            Comment(summary, plainText(body), body, media, transcriptsOf(decoded), TrackTags.of(decoded))
        }.asFlow().mapToList(context)

    fun commentCount(parentId: String): Flow<Long> = q.commentCount(parentId).asFlow().mapToOne(context)

    // Calendar (5.1)

    /** Days of [fromDay]..[toDay] that have entries, keyed by day. */
    fun monthDays(fromDay: String, toDay: String): Flow<Map<String, DayInfo>> =
        combine(q.dayEntries(fromDay, toDay).asFlow().mapToList(context), gentleRules()) { rows, rules ->
            rows.groupBy { it.day }.mapValues { (day, entries) ->
                // Each mood with whether the entry is quiet now (its own mark or a rule): a quiet one tints nothing.
                val moods = entries.mapNotNull { row ->
                    val f = decodeFields(row.fields)
                    f[MOOD_FIELD]?.toIntOrNull()?.let {
                        it to
                            (rules.veilOf(Veil.of(f[VEIL_FIELD]), f[VEIL_UNTIL_FIELD], day, tagsOf(f).map { it.id }) == Veil.Quiet)
                    }
                }
                DayInfo(
                    day,
                    entries.size,
                    moods.map { it.first }.averageOrNull(),
                    moods.filterNot { it.second }.map { it.first }.averageOrNull(),
                )
            }
        }

    // Mood check-ins (5.4)

    /**
     * A mood check-in: an entry of kind "checkin" with the mood, its emotion words as tags in the
     * "Эмоции" category (affect labeling, ux.md#psychology) and optional words of its own.
     */
    fun createCheckin(day: String, mood: Int, emotions: List<String>, text: String): String = db.transactionWithResult {
        val words = emotions.map(::tagName).filter { it.isNotEmpty() }.distinct()
        val body = text.trim().ifEmpty { words.joinToString(", ") }
        val id = createEntry(day, kind = "checkin", body = textBody(body, random))
        setMood(id, mood)
        words.forEach { word -> addTag(id, word)?.let { setTagCategory(it, EMOTIONS) } }
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

    /** The note's color, one of [SWATCHES] (anything else, or null, clears it). */
    fun setBackground(id: String, color: String?) = setField(id, BACKGROUND_FIELD, color?.takeIf { it in SWATCHES })

    /** Бережно (ADR-031): a view only; the entry's text, revisions, sync and exports stay as they are. */
    fun setVeil(id: String, veil: Veil?) = setField(id, VEIL_FIELD, veil?.value)

    fun toggleReaction(id: String, emoji: String, on: Boolean) = setField(id, REACTION_PREFIX + emoji, if (on) "1" else null)

    // Home (4.2)

    fun pinned(): Flow<List<EntrySummary>> = q.pinned(::summaryOf).asFlow().mapToList(context).veiled()

    /** Entries with typing not yet turned into a revision ("continue writing"). */
    fun withDrafts(): Flow<List<EntrySummary>> = q.withDrafts(::summaryOf).asFlow().mapToList(context).veiled()

    /** The same date in earlier years; [today] is "YYYY-MM-DD". */
    fun onThisDay(today: String): Flow<List<EntrySummary>> =
        q.onThisDay(today.substring(4), today, ::summaryOf).asFlow().mapToList(context).veiled()

    fun favoritesUpTo(lastDay: String): Flow<List<EntrySummary>> =
        q.favoritesUpTo(lastDay, ::summaryOf).asFlow().mapToList(context).veiled()

    fun daysWritten(fromDay: String, toDay: String): Flow<Long> = q.daysWritten(fromDay, toDay).asFlow().mapToOne(context)

    /** Newest first; the feed draws it bottom-up like a chat. */
    fun feed(limit: Long = 300): Flow<List<FeedItem>> =
        q.feed(limit) { id, kind, day, createdAt, title, preview, body, conflictHeads, mediaCount, cover, fields, attachments, comments ->
            val summary = summaryOf(id, day, createdAt, title, preview, conflictHeads, mediaCount, cover, fields)
            val text = if (kind == "journal") {
                listOf(title, preview).filter { it.isNotBlank() }.joinToString("\n")
            } else {
                plainText(body).take(FEED_TEXT_MAX)
            }
            val decoded = decodeFields(fields)
            val reactions = decoded.filter { it.key.startsWith(REACTION_PREFIX) && it.value != null }.keys
            val gallery = galleryOrder(decodeAttachments(attachments).values, body)
            val media = gallery.filter { it.meta.kind in VISUAL_KINDS }
            val audio = gallery.filter { it.meta.kind == "voice" || it.meta.kind == "audio" }
            FeedItem(
                summary, kind, text,
                reactions.map {
                    it.removePrefix(REACTION_PREFIX)
                }.toSet(),
                comments.toInt(), media, body, audio, transcriptsOf(decoded), TrackTags.of(decoded),
            )
        }.asFlow().mapToList(context).let { feed ->
            combine(feed, gentleRules()) { items, rules -> items.map { it.copy(summary = rules.apply(it.summary)) } }
        }

    /** Builds the search index after an update changed it (or on the first start); quick otherwise. */
    fun prepareSearch() = SearchIndex.rebuildIfNeeded(db)

    /**
     * Titles and previews are this device's own cache of each body ([summarize]); when the way they are made changes,
     * they are made again once (7.8: spoilers left out of titles as well). Nothing synced or stored by the user changes.
     */
    fun prepareSummaries() {
        val settings = Settings(db)
        if (settings.get(SUMMARIES_KEY) == SUMMARIES_VERSION) return
        db.transaction {
            q.allEntryIds().executeAsList().forEach { id ->
                val entry = q.selectEntry(id).executeAsOneOrNull() ?: return@forEach
                val (title, preview) = summarize(entry.body)
                if (title != entry.title || preview != entry.preview) q.updateSummary(title, preview, id)
            }
            settings.put(SUMMARIES_KEY, SUMMARIES_VERSION)
        }
    }

    /** Бережно's rules (ADR-031) with the tag categories they need. */
    fun gentleRules(): Flow<GentleRules> = combine(
        q.selectEntry(GENTLE_REGISTRY_ID).asFlow().mapToOneOrNull(context),
        q.selectEntry(TAG_REGISTRY_ID).asFlow().mapToOneOrNull(context),
    ) { rules, tags ->
        GentleRules.of(rules?.let { decodeFields(it.fields) }.orEmpty(), tags?.let { decodeFields(it.fields) }.orEmpty(), today())
    }

    /** Adds or changes a rule; a view only, entries stay as they are. */
    fun setGentleRule(rule: GentleRule) = setRegistryField(RULE_PREFIX + rule.id, rule.encode(), GENTLE_REGISTRY_ID)

    fun removeGentleRule(id: String) = setRegistryField(RULE_PREFIX + id, null, GENTLE_REGISTRY_ID)

    fun newRuleId(): String = newId()

    private fun Flow<List<EntrySummary>>.veiled(): Flow<List<EntrySummary>> =
        combine(this, gentleRules()) { entries, rules -> entries.map(rules::apply) }

    /** "Отложить до…" (ADR-031): quiet until [until], then back; null brings it back now (or answers the Home card). */
    fun setVeilUntil(id: String, until: String?) = setField(id, VEIL_UNTIL_FIELD, until)

    /** Entries put off until a day that has come and not answered yet, oldest first. */
    fun returned(today: String): Flow<List<EntrySummary>> = q.returned(today, ::summaryOf).asFlow().mapToList(context).veiled()

    /** A registry is created once, without revisions, so two devices never conflict over it; its fields are LWW. */
    internal fun setRegistryField(name: String, value: String?, registry: String = TAG_REGISTRY_ID) {
        db.transaction {
            if (q.selectEntry(registry).executeAsOneOrNull() == null) {
                val time = now()
                q.upsertEntry(
                    registry, "system", "1970-01-01", time, null,
                    emptyBody(
                        random,
                    ),
                    "", "", "[]", "{}", time, "{}", "{}", 0, null,
                )
                enqueue(CreateEntry(newId(), registry, "system", "1970-01-01", time))
            }
            setField(registry, name, value)
        }
    }

    fun search(query: SearchQuery): Flow<List<EntrySummary>> = SearchIndex.terms(query.text).let { terms ->
        q.search(
            m1 = terms.getOrNull(0)?.either,
            m2 = terms.getOrNull(1)?.either,
            m3 = terms.getOrNull(2)?.either,
            m4 = terms.getOrNull(3)?.either,
            rest = terms.drop(4).takeIf { it.isNotEmpty() }?.joinToString(" ") { it.prefix },
            tagged = if (query.tag != null) 1 else 0,
            tagKeys = query.tag?.let { tag -> registry().family(tag).map { TAG_PREFIX + it } }.orEmpty(),
            favorites = if (query.favorites) 1 else 0,
            withMedia = if (query.withMedia) 1 else 0,
            mapper = ::summaryOf,
        ).asFlow().mapToList(context).veiled()
    }

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

    internal fun newId() = uuidV7(now(), random)

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
        tagIds = tagsOf(f).map { it.id },
        background = f[BACKGROUND_FIELD]?.takeIf { it in SWATCHES },
        veil = effectiveVeil(Veil.of(f[VEIL_FIELD]), null),
        ownVeil = Veil.of(f[VEIL_FIELD]),
        until = f[VEIL_UNTIL_FIELD],
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
    // Spoilers stay out of both (ADR-031): lists, Home and search show them, and they show the words openly.
    val first = document.blocks.firstOrNull() as? TextBlock
    val title = first?.children?.filter { SPOILER !in it.marks }?.joinToString("") { it.text }.orEmpty().squeezed()
    val rest = (if (first != null) document.copy(blocks = document.blocks.drop(1)) else document).toPlainText(includeSpoilers = false)
    return title.take(TITLE_MAX) to rest.lines().map { it.squeezed() }.filter { it.isNotEmpty() }.joinToString(" ").take(PREVIEW_MAX)
}

private const val SPOILER = "spoiler"
private const val SUMMARIES_KEY = "summaries.version"
private const val SUMMARIES_VERSION = "2"

/** Runs of spaces as one (a hidden spoiler leaves two behind), trimmed. */
private fun String.squeezed(): String = replace(Regex(" {2,}"), " ").trim()

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

private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()
