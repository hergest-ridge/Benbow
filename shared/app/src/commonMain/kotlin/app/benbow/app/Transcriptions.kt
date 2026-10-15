package app.benbow.app

import app.benbow.client.JournalRepository
import app.benbow.client.LocalFile
import app.benbow.client.Media
import app.benbow.client.Settings
import app.benbow.client.SpeechModel
import app.benbow.core.sync.AttachmentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Turns sounds into text on this device (Android: GigaAM in a sandboxed process, 7.6); absent where it can't. */
interface Transcriber {
    /** Opens the recognizer once for several files (loading the model takes a moment) and runs [block] with it. */
    suspend fun <T> session(model: SpeechModel, block: suspend (transcribe: suspend (LocalFile) -> String) -> T): T
}

/**
 * Voice notes to text, one queue in the background: what's asked for (a tap, "all without text", or a voice note just
 * recorded here when [auto] is on) waits its turn, and one recognizer session serves everything queued at the time.
 * Nothing runs until the model is on this device; it comes only from the paired server ([model]).
 */
class Transcriptions internal constructor(
    private val transcriber: Transcriber,
    val model: SpeechModel,
    private val repo: JournalRepository,
    private val media: Media,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
    private val failed: (String) -> Unit,
) {
    private class Task(val entryId: String, val attachment: AttachmentState)

    private val queue = Channel<Task>(Channel.UNLIMITED)
    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** Attachment ids waiting or being transcribed, for the spinners. */
    val running: StateFlow<Set<String>> = _running

    /** Voice notes recorded on this device get their text on their own. */
    val auto = MutableStateFlow(settings().get(AUTO_KEY) != "false")

    init {
        scope.launch {
            for (first in queue) {
                val batch = ArrayList<Task>().apply { add(first) }
                while (true) batch += queue.tryReceive().getOrNull() ?: break
                run(batch)
            }
        }
    }

    fun setAuto(on: Boolean) {
        settings().put(AUTO_KEY, on.toString())
        auto.value = on
    }

    /** Queues one sound; ignored when it's already waiting or the model isn't here. */
    fun transcribe(entryId: String, attachment: AttachmentState) {
        if (model.state.value != SpeechModel.State.Ready) return
        val id = attachment.meta.id
        if (id in _running.value) return
        _running.update { it + id }
        queue.trySend(Task(entryId, attachment))
    }

    /** Queues every voice note and sound that has no text yet; returns how many. */
    fun transcribeAll(): Int = repo.untranscribed().onEach { (entry, attachment) -> transcribe(entry, attachment) }.size

    /** How many sounds still have no text. */
    fun untranscribedCount(): Int = repo.untranscribed().size

    /** Called for every attachment added on this device. */
    internal fun added(attached: Attached) {
        if (auto.value && (attached.kind == "voice" || attached.kind == "audio")) {
            repo.untranscribedIn(attached.entryId).firstOrNull {
                it.second.meta.id == attached.attachmentId
            }?.let { (entry, a) -> transcribe(entry, a) }
        }
    }

    private suspend fun run(batch: List<Task>) {
        try {
            transcriber.session(model) { transcribe ->
                for (task in batch) {
                    try {
                        val text = transcribe(media.file(task.attachment.meta.sha256))
                        if (text.isNotBlank()) repo.setTranscript(task.entryId, task.attachment.meta.id, text)
                    } catch (e: Exception) {
                        failed(e.message ?: e::class.simpleName.orEmpty())
                    } finally {
                        _running.update { it - task.attachment.meta.id }
                    }
                }
            }
        } catch (e: Exception) {
            failed(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            _running.update { it - batch.map { t -> t.attachment.meta.id }.toSet() }
        }
    }

    private companion object {
        const val AUTO_KEY = "speech.auto"
    }
}
