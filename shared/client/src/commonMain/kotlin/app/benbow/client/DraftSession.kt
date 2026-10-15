package app.benbow.client

import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.toDocument
import app.benbow.core.text.RichText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One editor session (docs/sync.md#revisions-from-drafts, data-safety S12). Every change reaches the
 * local draft within [SAVE_DELAY]. The draft becomes a revision after [IDLE_COMMIT] without typing,
 * every [TYPING_COMMIT] of continuous typing, and on [commit] (editor closed, app backgrounded).
 * A new entry ([entryId] = null) is created only once it has text, so an editor opened by mistake
 * leaves nothing behind.
 *
 * [onChange] and [commit] are called from the UI thread; the work runs in [scope], which must outlive
 * the screen so the final commit still happens after it is gone.
 */
@OptIn(ExperimentalAtomicApi::class)
class DraftSession(
    private val repo: JournalRepository,
    private val scope: CoroutineScope,
    entryId: String?,
    private val day: String,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val id = MutableStateFlow(entryId)

    /** Null until a new entry got its first text. */
    val entryIdFlow: StateFlow<String?> = id.asStateFlow()
    val entryId: String? get() = id.value

    /** Typing not yet in the local DB (it gets there within [SAVE_DELAY]); the browser asks before closing then. */
    val pending: Boolean get() = unsaved.load() != null

    private val unsaved = AtomicReference<RichText?>(null)
    private val mutex = Mutex()
    private var saveJob: Job? = null
    private var idleJob: Job? = null
    private var lastChange: TimeMark? = null
    private var typingSince: TimeMark? = null

    /** Cheap: the text is converted and written later, in [scope]. */
    fun onChange(text: RichText) {
        unsaved.store(text)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY)
            save()
        }
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_COMMIT)
            commitNow()
        }
        if (lastChange.let { it == null || it.elapsedNow() >= IDLE_COMMIT }) typingSince = timeSource.markNow()
        lastChange = timeSource.markNow()
        if (typingSince!!.elapsedNow() >= TYPING_COMMIT) {
            typingSince = timeSource.markNow()
            scope.launch { commitNow() }
        }
    }

    /** Saves whatever is pending and turns the draft into a revision. */
    fun commit(): Job {
        idleJob?.cancel()
        lastChange = null
        return scope.launch { commitNow() }
    }

    /** The entry's id, creating the entry now if it has none yet (attaching a photo to a new entry). */
    suspend fun ensureEntry(): String = mutex.withLock {
        entryId ?: run {
            val text = unsaved.exchange(null)
            val body = text?.let { DocumentCodec.encode(it.toDocument()) } ?: JournalRepository.emptyBody()
            repo.createEntry(day, body = body).also { id.value = it }
        }
    }

    private suspend fun save() = mutex.withLock {
        val text = unsaved.exchange(null) ?: return@withLock
        val body = DocumentCodec.encode(text.toDocument())
        val id = entryId
        when {
            id != null -> repo.saveDraft(id, body)
            text.text.isNotBlank() -> this.id.value = repo.createEntry(day, body = body)
        }
    }

    private suspend fun commitNow() {
        save()
        mutex.withLock { entryId?.let(repo::commitDraft) }
    }

    companion object {
        val SAVE_DELAY = 400.milliseconds
        val IDLE_COMMIT = 30.seconds
        val TYPING_COMMIT = 2.minutes
    }
}
