package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.benbow.app.resources.Res
import app.benbow.app.resources.attachments
import app.benbow.app.resources.in_text
import app.benbow.client.MediaStore
import app.benbow.core.sync.AttachmentState
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
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
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh).clickable(onClick = onClick)
            .semantics { contentDescription = attachment.meta.name ?: attachment.meta.kind },
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

/**
 * The entry's attachments as a row of thumbnails above the text, in gallery order. Those placed in the text come first
 * (marked, in the text's order); the rest can be put in order by a long press and drag ([onMove] gets the item and its
 * new neighbours), when the entry is being edited.
 */
@Composable
internal fun AttachmentStrip(
    media: MediaStore,
    attachments: List<AttachmentState>,
    onOpen: (AttachmentState) -> Unit,
    placed: Set<String> = emptySet(),
    onMove: ((id: String, after: String?, before: String?) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return
    val fixed = attachments.filter { it.meta.id in placed }
    val loose = attachments.filter { it.meta.id !in placed }
    var order by remember(loose.map { it.meta.id }) { mutableStateOf(loose) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val step = with(LocalDensity.current) { (THUMB + GAP).toPx() }
    val haptics = LocalHapticFeedback.current
    val inText = stringResource(Res.string.in_text)
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        fixed.forEach { attachment ->
            Box {
                MediaThumb(media, attachment, THUMB) { onOpen(attachment) }
                Chip(inText, Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
        }
        // Keyed, so a thumbnail keeps its gesture while it changes places.
        order.forEach { attachment ->
            key(attachment.meta.id) {
                val id = attachment.meta.id
                val lifted = id == dragging
                val drag = if (onMove == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragging = id
                                dragX = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragX += amount.x
                                val i = order.indexOfFirst { it.meta.id == id }
                                if (dragX > step / 2 && i < order.lastIndex) {
                                    order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                                    dragX -= step
                                } else if (dragX < -step / 2 && i > 0) {
                                    order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                                    dragX += step
                                }
                            },
                            onDragEnd = {
                                val i = order.indexOfFirst { it.meta.id == id }
                                if (order.map { it.meta.id } != loose.map { it.meta.id }) {
                                    onMove(id, order.getOrNull(i - 1)?.meta?.id, order.getOrNull(i + 1)?.meta?.id)
                                }
                                dragging = null
                                dragX = 0f
                            },
                            onDragCancel = {
                                order = loose
                                dragging = null
                                dragX = 0f
                            },
                        )
                    }
                }
                Box(
                    Modifier.zIndex(if (lifted) 1f else 0f).graphicsLayer {
                        translationX = if (lifted) dragX else 0f
                        val scale = if (lifted) 1.06f else 1f
                        scaleX = scale
                        scaleY = scale
                    }.then(drag),
                ) { MediaThumb(media, attachment, THUMB) { onOpen(attachment) } }
            }
        }
    }
}

private val THUMB = 96.dp
private val GAP = 8.dp

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

/**
 * Full-screen picture: pinch to zoom, drag when zoomed, double tap to zoom in or back. Not zoomed, one-finger drags pass
 * through (the pager swipes, a pull down closes); [onZoom] says when that changes, [onTap] reports a single tap.
 */
@Composable
internal fun ZoomableImage(file: File, modifier: Modifier = Modifier, onZoom: (Boolean) -> Unit = {}, onTap: () -> Unit = {}) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = { value: Float ->
        val was = scale > 1f
        scale = value
        if (scale == 1f) offset = Offset.Zero
        if (was != scale > 1f) onZoom(scale > 1f)
    }
    val state = rememberTransformableState { change, pan, _ ->
        zoom((scale * change).coerceIn(1f, 6f))
        if (scale > 1f) offset += pan * scale
    }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { zoom(if (scale > 1f) 1f else 2.5f) })
            }
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@Composable
internal fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/**
 * A post's pictures and videos as one swipeable card (the first one's shape, within reason): a counter and dots when there
 * are several, a play button and length on videos. A tap opens the full-screen viewer at that item.
 */
@Composable
internal fun MediaCarousel(
    media: MediaStore,
    items: List<AttachmentState>,
    onOpen: (AttachmentState) -> Unit,
    modifier: Modifier = Modifier,
    rounded: Boolean = true,
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    val first = items.first().meta
    val ratio = if ((first.width ?: 0) > 0 && (first.height ?: 0) > 0) first.width!!.toFloat() / first.height!! else 4f / 3
    val shape = if (rounded) RoundedCornerShape(12.dp) else RectangleShape
    val label = stringResource(Res.string.attachments)
    Box(
        modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.8f, 1.9f)).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).semantics { contentDescription = label },
    ) {
        HorizontalPager(pager, key = { items[it].meta.id }, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(item) }, contentAlignment = Alignment.Center) {
                item.visual?.let { sha ->
                    val load by rememberMediaFile(media, sha)
                    (load as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                if (item.meta.kind == "video") {
                    PlayBadge()
                    item.meta.durationMs?.let { ms -> Chip(durationLabel(ms), Modifier.align(Alignment.BottomStart).padding(8.dp)) }
                }
            }
        }
        if (items.size > 1) {
            Chip("${pager.currentPage + 1}/${items.size}", Modifier.align(Alignment.TopEnd).padding(8.dp))
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(items.size.coerceAtMost(10)) { i ->
                    val on = i == pager.currentPage.coerceAtMost(9)
                    Box(Modifier.size(if (on) 7.dp else 6.dp).background(Color.White.copy(alpha = if (on) 0.95f else 0.55f), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
    }
}
