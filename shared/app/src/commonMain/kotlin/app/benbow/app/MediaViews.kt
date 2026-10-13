package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.benbow.client.MediaStore
import app.benbow.core.sync.AttachmentState
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import java.io.File

internal sealed interface MediaLoad {
    data object Loading : MediaLoad

    data class Ready(val file: File) : MediaLoad

    data class Unavailable(val reason: String) : MediaLoad
}

/** The file by its hash: at once when it's on the device, otherwise after a verified download. */
@Composable
internal fun rememberMediaFile(media: MediaStore, sha256: String?): State<MediaLoad> = produceState<MediaLoad>(MediaLoad.Loading, sha256) {
    if (sha256 == null) return@produceState
    value = media.localFile(sha256)?.let { MediaLoad.Ready(it) } ?: try {
        MediaLoad.Ready(media.file(sha256))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MediaLoad.Unavailable(e.message ?: e::class.simpleName.orEmpty())
    }
}

internal fun durationLabel(ms: Long): String {
    val seconds = ms / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** The picture standing for an attachment: its preview, or the image itself. */
internal val AttachmentState.visual: String? get() = meta.previewSha256 ?: meta.sha256.takeIf { meta.kind == "image" }

@Composable
internal fun MediaThumb(media: MediaStore, attachment: AttachmentState, size: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val visual = attachment.visual
        if (visual != null) {
            val load by rememberMediaFile(media, visual)
            (load as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        val meta = attachment.meta
        val awaiting by remember(meta.sha256) { media.awaitingUpload(meta.sha256) }.collectAsState(false)
        if (awaiting) {
            // Not on the server yet: a small amber dot, gone once the server confirmed the file.
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(10.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
        }
        when (meta.kind) {
            "video" -> Badge(Icons.Play, meta.durationMs)

            "voice", "audio" -> Badge(Icons.Mic, meta.durationMs)

            "image" -> {}

            else -> Text(
                meta.name ?: "?",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, durationMs: Long?) {
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp))
            durationMs?.let { Text(durationLabel(it), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** The entry's attachments as a row of thumbnails above the text. */
@Composable
internal fun AttachmentStrip(media: MediaStore, attachments: List<AttachmentState>, onOpen: (AttachmentState) -> Unit) {
    if (attachments.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(attachments, key = { it.meta.id }) { attachment -> MediaThumb(media, attachment, 96.dp) { onOpen(attachment) } }
    }
}

/** A cover in the journal list, with "+N" when the entry has more. */
@Composable
internal fun CoverThumb(media: MediaStore, sha256: String, count: Int) {
    val load by rememberMediaFile(media, sha256)
    Box(
        Modifier.padding(
            start = 12.dp,
        ).size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        (load as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (count > 1) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            ) {
                Text(
                    "+${count - 1}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Full-screen picture: pinch to zoom, drag when zoomed, double tap to zoom in or back. */
@Composable
internal fun ZoomableImage(file: File, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan * scale
    }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .transformable(state)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@Composable
internal fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
