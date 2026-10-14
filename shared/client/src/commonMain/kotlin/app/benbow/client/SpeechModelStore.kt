package app.benbow.client

import app.benbow.core.api.SpeechModelFile
import app.benbow.core.api.SpeechModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The speech-recognition model on this device (7.6, ADR-025), in [dir]. It comes only from the paired server, and
 * each file is checked against the hashes pinned in this build ([SpeechModelSpec]), not against what the server says,
 * so a changed or broken file is never used. Downloads resume where they stopped.
 */
class SpeechModelStore(
    private val dir: File,
    private val api: () -> BenbowApi,
    private val files: List<SpeechModelFile> = SpeechModelSpec.files,
) {
    sealed interface State {
        data object Missing : State

        /** The server doesn't have it yet: the owner downloads it on the server's page first. */
        data object NotOnServer : State

        data class Downloading(val done: Long, val total: Long) : State

        data object Ready : State

        data class Failed(val message: String) : State
    }

    private val marker = File(dir, "verified")

    private val _state = MutableStateFlow(if (verified()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state

    fun file(name: String): File = File(dir, name)

    /** Fetches what's missing from the server and checks it; safe to call again after a failure or a restart. */
    suspend fun fetch() = withContext(Dispatchers.IO) {
        if (verified()) return@withContext _state.run { value = State.Ready }
        try {
            val api = api()
            if (!api.speechModel().ready) return@withContext _state.run { value = State.NotOnServer }
            dir.mkdirs()
            marker.delete()
            val total = files.sumOf { it.size }
            var before = 0L
            for (spec in files) {
                val target = file(spec.name)
                if (!(target.length() == spec.size && sha256(target) == spec.sha256)) {
                    target.delete()
                    val part = File(dir, spec.name + ".part")
                    if (part.length() > spec.size) part.delete()
                    var stalled = 0
                    while (part.length() < spec.size) {
                        val had = part.length()
                        api.downloadSpeechFile(spec.name, part)
                        _state.value = State.Downloading(before + part.length(), total)
                        if (part.length() <= had && ++stalled >= MAX_STALLS) error("${spec.name}: the download doesn't move")
                    }
                    if (part.length() != spec.size || sha256(part) != spec.sha256) {
                        part.delete()
                        error("${spec.name} doesn't match its pinned hash")
                    }
                    check(part.renameTo(target)) { "can't place ${spec.name}" }
                }
                before += spec.size
                _state.value = State.Downloading(before, total)
            }
            marker.writeText(SpeechModelSpec.ID)
            _state.value = State.Ready
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** Removes the model (frees ~230 MB); it can be fetched again. */
    fun delete() {
        dir.deleteRecursively()
        _state.value = State.Missing
    }

    /** Checked once after the download; afterwards sizes are enough (hashing 230 MB on every start would be slow). */
    private fun verified() = marker.isFile && marker.readText() == SpeechModelSpec.ID && files.all { file(it.name).length() == it.size }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_STALLS = 3
    }
}
