package app.benbow.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.zIndex
import app.benbow.app.resources.Res
import app.benbow.app.resources.attachments
import app.benbow.app.resources.cancel
import app.benbow.app.resources.in_text
import app.benbow.app.resources.media_look_open
import app.benbow.app.resources.media_next
import app.benbow.app.resources.media_previous
import app.benbow.app.resources.strip_remove
import app.benbow.app.resources.strip_remove_ask
import app.benbow.app.resources.strip_remove_yes
import app.benbow.app.resources.strip_settings
import app.benbow.client.LocalFile
import app.benbow.client.Media
import app.benbow.core.media.MediaLook
import app.benbow.core.sync.AttachmentState
import app.benbow.ui.mouseSwipe
import app.benbow.ui.sideScroll
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal sealed interface MediaLoad {
    data object Loading : MediaLoad

    data class Ready(val file: LocalFile) : MediaLoad

    data class Unavailable(val reason: String) : MediaLoad
}

/** The file by its hash: at once when it's on the device, otherwise after a verified download. */
@Composable
internal fun rememberMediaFile(media: Media, sha256: String?): State<MediaLoad> = produceState<MediaLoad>(MediaLoad.Loading, sha256) {
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
internal fun MediaThumb(media: Media, attachment: AttachmentState, size: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(
            size,
        ).clip(
            RoundedCornerShape(12.dp),
        ).background(
            colors.surfaceContainerHigh,
        ).hand().clickable(interactionSource = null, indication = mediaIndication(), onClick = onClick)
            .semantics { contentDescription = attachment.meta.name ?: attachment.meta.kind },
        contentAlignment = Alignment.Center,
    ) {
        val visual = attachment.visual
        if (attachment.meta.kind == "drawing") DrawingPicture(media, attachment, Modifier.fillMaxSize().padding(4.dp))
        if (visual != null) {
            val load by rememberMediaFile(media, visual)
            val focus = LocalMediaLooks.current[attachment.meta.id]?.focus
            (load as? MediaLoad.Ready)?.let {
                AsyncImage(it.file, null, Modifier.fillMaxSize(), alignment = focus.alignment(), contentScale = ContentScale.Crop)
            }
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

            "image", "drawing" -> {}

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
 * new neighbours), when the entry is being edited. With [onSettings] a tap offers «Открыть» and «Настроить», and with
 * [onRemove] those outside the text have ✕, asked about once (owner, 2026-10-30).
 */
@Composable
internal fun AttachmentStrip(
    media: Media,
    attachments: List<AttachmentState>,
    onOpen: (AttachmentState) -> Unit,
    placed: Set<String> = emptySet(),
    onMove: ((id: String, after: String?, before: String?) -> Unit)? = null,
    onSettings: ((AttachmentState) -> Unit)? = null,
    onRemove: ((AttachmentState) -> Unit)? = null,
    /**
     * One outside the text pulled down out of the strip is carried (owner, 2026-10-30): where the pointer is in the
     * window as it goes, null once it ends or is let go of; [onPlace] at the end says whether it went into the text there.
     */
    onCarry: ((AttachmentState, Offset?) -> Unit)? = null,
    onPlace: ((AttachmentState, Offset) -> Boolean)? = null,
) {
    if (attachments.isEmpty()) return
    val fixed = attachments.filter { it.meta.id in placed }
    val loose = attachments.filter { it.meta.id !in placed }
    var order by remember(loose.map { it.meta.id }) { mutableStateOf(loose) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // Pulled out of the strip: which one, and where the pointer is in the window; each thumbnail's place there.
    var torn by remember { mutableStateOf<String?>(null) }
    var carried by remember { mutableStateOf<Offset?>(null) }
    val corners = remember { HashMap<String, Offset>() }
    val step = with(LocalDensity.current) { (THUMB + GAP).toPx() }
    val tearAt = with(LocalDensity.current) { (THUMB * 0.6f).toPx() }
    val haptics = LocalHapticFeedback.current
    val inText = stringResource(Res.string.in_text)
    Row(
        Modifier.sideScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        fixed.forEach { attachment ->
            Box {
                StripThumb(media, attachment, onOpen, onSettings, onRemove = null)
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
                                dragY = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val corner = corners[id] ?: Offset.Zero
                                if (torn == id) {
                                    carried = corner + change.position
                                    onCarry?.invoke(attachment, carried)
                                    return@detectDragGesturesAfterLongPress
                                }
                                dragX += amount.x
                                dragY += amount.y
                                // Down out of the strip: the gesture's first part takes it out, the rest carries it.
                                if (onCarry != null && dragY > tearAt) {
                                    order = loose
                                    torn = id
                                    dragX = 0f
                                    carried = corner + change.position
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCarry(attachment, carried)
                                    return@detectDragGesturesAfterLongPress
                                }
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
                                val at = carried
                                if (torn == id) {
                                    if (at == null || onPlace?.invoke(attachment, at) != true) onCarry?.invoke(attachment, null)
                                } else {
                                    val i = order.indexOfFirst { it.meta.id == id }
                                    if (order.map { it.meta.id } != loose.map { it.meta.id }) {
                                        onMove(id, order.getOrNull(i - 1)?.meta?.id, order.getOrNull(i + 1)?.meta?.id)
                                    }
                                }
                                torn = null
                                carried = null
                                dragging = null
                                dragX = 0f
                            },
                            onDragCancel = {
                                if (torn == id) onCarry?.invoke(attachment, null)
                                torn = null
                                carried = null
                                order = loose
                                dragging = null
                                dragX = 0f
                            },
                        )
                    }
                }
                Box(
                    Modifier.onGloballyPositioned { corners[id] = it.positionInWindow() }.zIndex(if (lifted) 1f else 0f).graphicsLayer {
                        val out = id == torn
                        translationX = if (lifted && !out) dragX else 0f
                        val scale = if (lifted && !out) 1.06f else 1f
                        scaleX = scale
                        scaleY = scale
                        // Its place in the strip stays, faint, while it is carried.
                        alpha = if (out) 0.35f else 1f
                    }.then(drag),
                ) { StripThumb(media, attachment, onOpen, onSettings, onRemove) }
            }
        }
    }
    // What is carried follows the pointer over everything, a little lifted.
    val ghost = torn?.let { t -> loose.firstOrNull { it.meta.id == t } }
    val at = carried
    if (ghost != null && at != null) {
        Popup(popupPositionProvider = remember(at) { CarriedAt(at) }) {
            Box(
                Modifier.graphicsLayer {
                    alpha = 0.92f
                    rotationZ = -3f
                    shadowElevation = 12f
                    shape = RoundedCornerShape(12.dp)
                    clip = true
                },
            ) { MediaThumb(media, ghost, THUMB) {} }
        }
    }
}

/** A thumbnail of the strip: a tap opens it, or offers «Открыть» and «Настроить»; ✕ in its corner takes it off, asked once. */
@Composable
private fun StripThumb(
    media: Media,
    attachment: AttachmentState,
    onOpen: (AttachmentState) -> Unit,
    onSettings: ((AttachmentState) -> Unit)?,
    onRemove: ((AttachmentState) -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    Box {
        MediaThumb(media, attachment, THUMB) { if (onSettings == null) onOpen(attachment) else menu = true }
        if (onRemove != null) {
            val label = stringResource(Res.string.strip_remove)
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .hand().clickable { asking = true }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Close, null, Modifier.size(16.dp), tint = Color.White) }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.media_look_open)) }, onClick = {
                menu = false
                onOpen(attachment)
            }, modifier = Modifier.hand())
            onSettings?.let { settings ->
                DropdownMenuItem(text = { Text(stringResource(Res.string.strip_settings)) }, onClick = {
                    menu = false
                    settings(attachment)
                }, modifier = Modifier.hand())
            }
        }
        DropdownMenu(asking, { asking = false }) {
            Text(
                stringResource(Res.string.strip_remove_ask),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 240.dp),
            )
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ asking = false }, modifier = Modifier.hand()) { Text(stringResource(Res.string.cancel)) }
                TextButton({
                    asking = false
                    onRemove?.invoke(attachment)
                }, modifier = Modifier.hand()) { Text(stringResource(Res.string.strip_remove_yes)) }
            }
        }
    }
}

private val THUMB = 96.dp
private val GAP = 8.dp

/** A carried thumbnail's place: centred on the pointer, at [at] in the window. */
private class CarriedAt(private val at: Offset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset((at.x - popupContentSize.width / 2f).roundToInt(), (at.y - popupContentSize.height / 2f).roundToInt())
}

/** A cover in the journal list, with "+N" when the entry has more. */
@Composable
internal fun CoverThumb(media: Media, sha256: String, count: Int) {
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
internal fun ZoomableImage(file: LocalFile, modifier: Modifier = Modifier, onZoom: (Boolean) -> Unit = {}, onTap: () -> Unit = {}) {
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
    // Full resolution (Coil caps it at 4096 px), so zooming in shows the photo's own detail.
    val context = LocalPlatformContext.current
    val request = remember(file) { ImageRequest.Builder(context).data(file).size(Size.ORIGINAL).build() }
    AsyncImage(
        model = request,
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

/** A mouse can't swipe: on desktop and in the browser pagers get side buttons (set by the app's root). */
internal val LocalPagerArrows = compositionLocalOf { false }

/** Round, translucent buttons at the sides of a pager: the previous and next picture, hidden at either end. */
@Composable
internal fun BoxScope.PagerArrows(pager: PagerState, size: Dp = 36.dp, inset: Dp = 8.dp) {
    if (!LocalPagerArrows.current || pager.pageCount < 2) return
    val scope = rememberCoroutineScope()
    if (pager.currentPage > 0) {
        PagerArrow(
            Icons.ChevronLeft,
            stringResource(Res.string.media_previous),
            size,
            Modifier.align(Alignment.CenterStart).padding(start = inset),
        ) {
            scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
        }
    }
    if (pager.currentPage < pager.pageCount - 1) {
        PagerArrow(
            Icons.ChevronRight,
            stringResource(Res.string.media_next),
            size,
            Modifier.align(Alignment.CenterEnd).padding(end = inset),
        ) {
            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        }
    }
}

@Composable
private fun PagerArrow(icon: ImageVector, label: String, size: Dp, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.38f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)), // seen on the viewer's black too
        modifier = modifier.size(size).hand(),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(size * 0.66f)) }
    }
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
    media: Media,
    items: List<AttachmentState>,
    onOpen: (AttachmentState) -> Unit,
    modifier: Modifier = Modifier,
    rounded: Boolean = true,
) {
    if (items.isEmpty()) return
    // The page count and the pages' keys read the same list. The frame below recomposes a frame later (inside
    // BoxWithConstraints), so a count taken from the new list once asked the old one for a page it didn't have:
    // an item added to a post crashed the app (MediaCarouselTest).
    val current = rememberUpdatedState(items)
    val pager = rememberPagerState { current.value.size }
    val first = items.first().meta
    val shapeRatio = if ((first.width ?: 0) > 0 && (first.height ?: 0) > 0) first.width!!.toFloat() / first.height!! else 4f / 3
    val shape = if (rounded) RoundedCornerShape(12.dp) else RectangleShape
    val label = stringResource(Res.string.attachments)
    // A tall photo in a wide window (a post on the right side) would be taller than the screen: at most 70% of it.
    // A phone's column never gets there (a card is at most 1.25 times as tall as wide), so phones look as before.
    val tallest = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.7f).toDp() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val height = (maxWidth / shapeRatio.coerceIn(0.8f, 1.9f)).coerceAtMost(tallest.coerceAtLeast(160.dp))
        val ratio = maxWidth / height
        MediaCarouselFrame(media, current, pager, ratio, shape, label, onOpen, Modifier.fillMaxWidth().height(height))
    }
}

@Composable
private fun MediaCarouselFrame(
    media: Media,
    current: State<List<AttachmentState>>,
    pager: PagerState,
    ratio: Float,
    shape: Shape,
    label: String,
    onOpen: (AttachmentState) -> Unit,
    modifier: Modifier,
) {
    val items = current.value
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).semantics { contentDescription = label },
    ) {
        // Each page the carousel's whole width (owner: no piece of the next one at the edge); the arrows and the count tell
        // there are more, and a mouse drags the pages.
        HorizontalPager(
            pager,
            key = { current.value[it].meta.id },
            modifier = Modifier.fillMaxSize().mouseSwipe(pager),
        ) { page ->
            val item = current.value[page]
            Box(
                Modifier.fillMaxSize().hand().clickable(interactionSource = null, indication = mediaIndication()) {
                    onOpen(item)
                },
                contentAlignment = Alignment.Center,
            ) {
                FramedPicture(media, item, frame = ratio)
                if (item.meta.kind == "video") {
                    item.meta.durationMs?.let { ms -> Chip(durationLabel(ms), Modifier.align(Alignment.BottomStart).padding(8.dp)) }
                }
            }
        }
        PagerArrows(pager)
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

/**
 * A picture at its best: the small preview shows at once and the original (images) fades in over it as soon as it's
 * on hand, decoded at the size it's shown, so nothing in view looks compressed. Offline, a file that isn't on the device
 * keeps its preview. [previewOnly] is for backdrops that are blurred anyway; cropped, [alignment] keeps its focus in
 * sight; a GIF not [moving] stands still on its first frame.
 */
@Composable
internal fun BestPicture(
    media: Media,
    attachment: AttachmentState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    previewOnly: Boolean = false,
    alignment: Alignment = Alignment.Center,
    moving: Boolean = true,
) {
    val meta = attachment.meta
    if (meta.kind == "drawing") return DrawingPicture(media, attachment, modifier)
    // A GIF moves once its original is on hand (its preview, or its first frame, shows meanwhile).
    val gif = meta.kind == "image" && meta.mime == "image/gif"
    Box(modifier) {
        val preview by rememberMediaFile(media, attachment.visual)
        (preview as? MediaLoad.Ready)?.let {
            AsyncImage(it.file, null, Modifier.fillMaxSize(), alignment = alignment, contentScale = contentScale, alpha = alpha)
        }
        if (!previewOnly && meta.kind == "image" && (meta.previewSha256 != null || gif)) {
            val original by rememberMediaFile(media, meta.sha256)
            (original as? MediaLoad.Ready)?.let {
                if (gif) {
                    MovingPicture(it.file, Modifier.fillMaxSize(), contentScale, playing = moving)
                } else {
                    AsyncImage(it.file, null, Modifier.fillMaxSize(), alignment = alignment, contentScale = contentScale, alpha = alpha)
                }
            }
        }
    }
}

/** How each attachment shows in its post, by id (owner, 2026-10-30): provided around a post, read by its pictures. */
internal val LocalMediaLooks = compositionLocalOf<Map<String, MediaLook>> { emptyMap() }

/** Where a picture cropped keeps its [MediaLook.focus] in sight: that point of it, or its middle. */
internal fun MediaLook.Focus?.alignment(): Alignment = this?.let { BiasAlignment(it.x * 2 - 1, it.y * 2 - 1) } ?: Alignment.Center

/**
 * A picture or a video in a frame of [frame]'s shape (width over height), as its look says ([LocalMediaLooks]): filling
 * the frame cropped around its focus; whole over a blurred, dimmed copy of itself; or by its own shape, the default:
 * cropped when the shapes are close, otherwise whole like that (a tall photo after a wide one doesn't lose most of its
 * sides). A GIF moves and a video plays by itself only when it is to ([MediaLook.plays]); else, with [badge], ▶ says so.
 */
@Composable
internal fun FramedPicture(media: Media, attachment: AttachmentState, frame: Float, badge: Boolean = true) {
    val meta = attachment.meta
    val look = LocalMediaLooks.current[meta.id] ?: MediaLook()
    val gif = meta.kind == "image" && meta.mime == "image/gif"
    val video = meta.kind == "video"
    val plays = look.plays(gif)
    val w = meta.width ?: 0
    val h = meta.height ?: 0
    val own = if (w > 0 && h > 0) w.toFloat() / h else null
    val crop = when (look.fit) {
        MediaLook.Fit.Fill -> true
        MediaLook.Fit.Whole -> false
        MediaLook.Fit.Natural -> own == null || meta.kind == "drawing" || maxOf(own / frame, frame / own) <= 1.3f
    }
    val playing = video && plays && inlineVideos
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!crop) BestPicture(media, attachment, Modifier.fillMaxSize().blur(24.dp), alpha = 0.5f, previewOnly = true)
        BestPicture(
            media,
            attachment,
            Modifier.fillMaxSize(),
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            alignment = look.focus.alignment(),
            moving = plays,
        )
        if (playing) {
            val file by rememberMediaFile(media, meta.sha256)
            (file as? MediaLoad.Ready)?.let { InlineVideo(it.file, crop, Modifier.fillMaxSize()) }
        }
        if (badge && ((video && !playing) || (gif && !plays))) PlayBadge()
    }
}
