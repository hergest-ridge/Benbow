package app.benbow.app

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.benbow.client.ByteSource
import app.benbow.client.LocalFile
import app.benbow.client.byteSourceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    open: suspend () -> ByteSource,
    onAttached: () -> Unit = {},
    probe: suspend () -> MediaProbe,
): Job = scope.launch {
    try {
        val probe = probe()
        val file = open().use { media.import(it, probe.mime, probe.name, probe.capturedAt) }
        val preview = probe.preview?.let { media.import(byteSourceOf(it), "image/jpeg", null, probe.capturedAt) }
        val entry = entryId()
        val id = repo.attach(entry, file, probe.kind, probe.width, probe.height, probe.durationMs, preview)
        attached.tryEmit(Attached(entry, id, probe.kind))
        onAttached()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        messages.tryEmit(AppMessage.AttachFailed(e.message ?: e::class.simpleName.orEmpty()))
    }
}

/**
 * Buttons that add photos, videos, voice notes or files to an entry; what's offered depends on the device. [tools]
 * follow them (the editor's drawing button). On Android the dictaphone's keys close the row (7.10): «голос» records a
 * voice note, «текст» writes into [dictation].
 */
@Composable
internal expect fun MediaBar(
    services: AppServices,
    entryId: suspend () -> String,
    dictation: DictationTarget?,
    modifier: Modifier = Modifier,
    tools: @Composable RowScope.() -> Unit = {},
)

/** The текст key for a quick note (7.10d), where this device can dictate; nothing elsewhere. */
@Composable
internal expect fun DictationKey(services: AppServices, target: DictationTarget, modifier: Modifier = Modifier)

/** Files dropped on the screen from the desktop are attached; [onHover] tells while some are over it. No-op on Android. */
@Composable
internal expect fun Modifier.attachOnDrop(services: AppServices, entryId: suspend () -> String, onHover: (Boolean) -> Unit): Modifier

/** Plays a video or audio file, or offers to open it outside the app where there is no player. */
@Composable
internal expect fun MediaPlayer(file: LocalFile, mime: String, modifier: Modifier = Modifier)
