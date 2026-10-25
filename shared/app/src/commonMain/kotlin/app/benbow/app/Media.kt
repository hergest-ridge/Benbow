package app.benbow.app

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import app.benbow.client.ByteSource
import app.benbow.client.LocalFile
import app.benbow.client.byteSourceOf
import app.benbow.client.randomRead
import app.benbow.core.audio.TrackInfo
import app.benbow.core.audio.TrackReader
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

/**
 * The type a file gets when the system couldn't say (Windows often can't for .flac or .opus, a browser may give none):
 * known sound files by their extension (ADR-035), anything else as it was.
 */
internal fun refineMime(mime: String?, name: String?): String {
    if (!mime.isNullOrEmpty() && mime != "application/octet-stream") return mime
    return SOUND_TYPES[name?.substringAfterLast('.', "")?.lowercase()] ?: "application/octet-stream"
}

private val SOUND_TYPES = mapOf(
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "aac" to "audio/aac",
    "wav" to "audio/wav",
    "flac" to "audio/flac",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "opus" to "audio/ogg",
    "aif" to "audio/aiff",
    "aiff" to "audio/aiff",
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
 * A sound file's tags and cover are read from the stored copy, the same way everywhere (ADR-035).
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
        val (track, cover) = (if (probe.kind == "audio") readTrack(media.imported(file)) else null) ?: (null to null)
        val previewBytes = probe.preview ?: cover
        val preview = previewBytes?.let { media.import(byteSourceOf(it), "image/jpeg", null, probe.capturedAt) }
        val entry = entryId()
        val duration = probe.durationMs ?: track?.durationMs
        val id = repo.attach(entry, file, probe.kind, probe.width, probe.height, duration, preview, track?.tags)
        attached.tryEmit(Attached(entry, id, probe.kind))
        onAttached()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        messages.tryEmit(AppMessage.AttachFailed(e.message ?: e::class.simpleName.orEmpty()))
    }
}

/** Tags and the cover (as a JPEG) are a nicety: a sound whose file or picture can't be read still attaches, under its name. */
private suspend fun readTrack(file: LocalFile): Pair<TrackInfo, ByteArray?>? = try {
    val info = TrackReader.read(file.randomRead())
    info to info.cover?.let { coverJpeg(it) }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * A sound's embedded cover as the attachment's preview: a JPEG at most [max] px across ([COVER_SIZE] by default), or null
 * when it can't be read; a picture link's small preview too (ADR-045).
 */
internal expect suspend fun coverJpeg(image: ByteArray, max: Int = COVER_SIZE): ByteArray?

/** Large enough for the player's cover across a phone (ADR-035); lists scale it down. */
internal const val COVER_SIZE = 1024

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

/**
 * Files dropped on the screen from the desktop are attached; [onHover] tells while some are over it. A link dragged from a
 * browser goes to [onLink] (ADR-038). No-op on Android.
 */
@Composable
internal expect fun Modifier.attachOnDrop(
    services: AppServices,
    entryId: suspend () -> String,
    onHover: (Boolean) -> Unit,
    onLink: (String) -> Unit = {},
): Modifier

/** Plays a video or audio file, or offers to open it outside the app where there is no player. */
@Composable
internal expect fun MediaPlayer(file: LocalFile, mime: String, modifier: Modifier = Modifier)

/**
 * A picture file shown as it is; a GIF moves (owner: GIFs by their links didn't show). Android: Coil's animated decoder;
 * the desktop and the browser: Skia plays the frames ([MovingFrames]).
 */
@Composable
internal expect fun MovingPicture(file: LocalFile, modifier: Modifier, contentScale: ContentScale)
