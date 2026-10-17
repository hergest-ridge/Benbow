package app.benbow.app

import app.benbow.client.ByteSource
import app.benbow.client.SpeechModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The microphone for dictation (7.10): Android's; none elsewhere. */
interface DictationMic {
    /** Starts a take, written to disk as it's recorded; null when the microphone can't be opened. */
    fun start(): MicTake?

    /** Takes a process left on disk when it died mid-dictation, oldest first; finished ones are dropped here. */
    fun leftovers(): List<TakeFile>
}

/** A take on disk, kept until [delete]. */
interface TakeFile {
    /** The entry it was dictated into, written beside it ([MicTake.belongsTo]); null for a quick note. */
    val entryId: String?
    val capturedAt: Long

    /** The take as a voice note (AAC in MP4). */
    suspend fun voiceNote(): VoiceNoteFile

    fun delete()
}

/** One take while it's recorded: 16 kHz mono samples, and the whole of it on disk. */
interface MicTake : TakeFile {
    /** The samples as recorded; ends after [stop], or with the error when the microphone fails. */
    val audio: Flow<FloatArray>

    /** Loudness of the latest moment, 0..1. */
    val level: Float
    val elapsedMs: Long

    fun stop()

    /** Notes the take's entry beside it, so a take left by a crash finds its way back. */
    fun belongsTo(entryId: String)

    /** Its text is in: on disk it waits only for «Приложить запись», and a crash just drops it. */
    fun finished()
}

/** A take encoded as a voice note, ready to attach; [delete] removes the encoded copy. */
class VoiceNoteFile(
    val open: suspend () -> ByteSource,
    val name: String,
    val durationMs: Long,
    val capturedAt: Long,
    val delete: () -> Unit,
)

/** Where dictated text goes (7.10): the editor at its cursor, or a quick note's field. */
internal interface DictationTarget {
    /** Puts a phrase in as typed text; false when the target is gone (its screen was left). Called in order. */
    suspend fun insert(phrase: String): Boolean

    /** Takes this take's phrases out again (✕), when nothing else was typed since. */
    suspend fun takeBack() {}

    /** A take begins: the entry it belongs to (the editor's, made now if new); null for a quick note, whose entry comes later. */
    suspend fun begin(): String?
}

/**
 * Dictation on this device (7.10, ADR-033): a take from [mic] becomes text phrase by phrase ([dictate]) in the target
 * it was started for. Nothing said is lost: a take that fails, or whose target went away before its text arrived, is
 * kept as a voice note (of the target's entry, else of a new note today); a good take's audio waits for «Приложить
 * запись» while that message shows, then goes. Takes a dead process left on disk become voice notes on start. Takes
 * are recognized one after another, so a quick second take never overtakes the first; the microphone never waits.
 */
class Dictations internal constructor(
    private val services: AppServices,
    private val mic: DictationMic,
    private val transcriber: Transcriber,
    val model: SpeechModel,
) {
    private val _active = MutableStateFlow<MicTake?>(null)

    /** The take being recorded, for the strip and the lamp. */
    val active: StateFlow<MicTake?> = _active

    /** Phrases cut but not text yet. */
    val pending = MutableStateFlow(0)

    /** Recognition failed during the current take: it's going to be a voice note. */
    val failing = MutableStateFlow(false)

    private val thrownAway = MutableStateFlow<Set<MicTake>>(emptySet())
    private val inTurn = Mutex()

    init {
        services.scope.launch { mic.leftovers().forEach { keepAsVoice(it, it.entryId) } }
    }

    /** Starts dictating into [target]; false when the model isn't here, a take is running or the microphone won't open. */
    internal fun start(target: DictationTarget): Boolean {
        if (_active.value != null || model.state.value != SpeechModel.State.Ready) return false
        val take = mic.start() ?: return false
        failing.value = false
        _active.value = take
        services.scope.launch { run(take, target) }
        return true
    }

    /** Lets the текст key up; with [keep] false (✕) the take is thrown away, its text taken back. */
    internal fun stop(keep: Boolean) {
        val take = _active.value ?: return
        if (!keep) thrownAway.update { it + take }
        _active.value = null
        take.stop()
    }

    /** «Приложить запись» was tapped ([attach]) or the message went: the take's audio joins the entry or goes. */
    internal fun settle(message: AppMessage.Dictated, attach: Boolean) {
        services.scope.launch { if (attach) keepAsVoice(message.take, message.entryId) else message.take.delete() }
    }

    private suspend fun run(take: MicTake, target: DictationTarget) {
        var entry: String? = null
        var lost = false
        var inserted = 0
        val end = inTurn.withLock {
            entry = runCatching { target.begin() }.getOrNull()?.also { take.belongsTo(it) }
            dictate(transcriber, model, take.audio, pending, onFailure = { failing.value = true }) { phrase ->
                if (take in thrownAway.value) return@dictate
                if (target.insert(phrase)) inserted++ else lost = true
            }
        }
        _active.compareAndSet(take, null) // the microphone may have stopped by itself
        when {
            take in thrownAway.value -> {
                if (inserted > 0) target.takeBack()
                take.delete()
                thrownAway.update { it - take }
            }

            end == DictationEnd.Failed || lost -> {
                services.messages.tryEmit(AppMessage.DictationKept)
                keepAsVoice(take, entry)
            }

            end == DictationEnd.Silence -> take.delete()

            entry != null -> {
                take.finished()
                services.messages.tryEmit(AppMessage.Dictated(take, entry))
            }

            else -> take.delete()
        }
    }

    /** Attaches [take] as a voice note to [entryId], or to a new note today when there's none (or it's gone). */
    private suspend fun keepAsVoice(take: TakeFile, entryId: String?) {
        val note = try {
            take.voiceNote()
        } catch (e: Exception) {
            services.messages.tryEmit(AppMessage.AttachFailed(e.message ?: e::class.simpleName.orEmpty()))
            return // the take stays on disk and is tried again on the next start
        }
        val entry = entryId?.takeIf { services.repo.hasEntry(it) } ?: services.repo.createNote(services.today(), "")
        services.attach({ entry }, note.open, onAttached = {
            note.delete()
            take.delete()
        }) { MediaProbe("audio/mp4", note.name, "voice", durationMs = note.durationMs, capturedAt = note.capturedAt) }
    }
}
