package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/** What the device read about a file when it was picked or captured. */
internal data class MediaProbe(
    val mime: String,
    val name: String?,
    /** image | video | audio | voice | file */
    val kind: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val capturedAt: Long? = null,
    /** A small JPEG for lists and galleries (pictures and video frames), so other devices needn't fetch originals. */
    val preview: ByteArray? = null,
)

internal fun kindOf(mime: String): String = when {
    mime.startsWith("image/") -> "image"
    mime.startsWith("video/") -> "video"
    mime.startsWith("audio/") -> "audio"
    else -> "file"
}

/**
 * Reads the file's details ([probe], off the main thread), copies it and its preview into the app's
 * media folder, then attaches it. Runs in the app scope, so leaving the screen doesn't cut it short.
 * [onAttached] runs only after success (e.g. to delete a camera's temporary file); failures are reported.
 */
internal fun AppServices.attach(
    entryId: suspend () -> String,
    open: () -> InputStream,
    onAttached: () -> Unit = {},
    probe: () -> MediaProbe,
): Job = scope.launch {
    try {
        val probe = probe()
        val file = open().use { media.import(it, probe.mime, probe.name, probe.capturedAt) }
        val preview = probe.preview?.let { media.import(it.inputStream(), "image/jpeg", null, probe.capturedAt) }
        repo.attach(entryId(), file, probe.kind, probe.width, probe.height, probe.durationMs, preview)
        onAttached()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        messages.tryEmit(AppMessage.AttachFailed(e.message ?: e::class.simpleName.orEmpty()))
    }
}

/** Buttons that add photos, videos, voice notes or files to an entry; what's offered depends on the device. */
@Composable
internal expect fun MediaBar(services: AppServices, entryId: suspend () -> String, modifier: Modifier = Modifier)

/** Plays a video or audio file, or offers to open it outside the app where there is no player. */
@Composable
internal expect fun MediaPlayer(file: File, mime: String, modifier: Modifier = Modifier)
