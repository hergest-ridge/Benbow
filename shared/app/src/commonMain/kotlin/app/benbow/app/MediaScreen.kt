package app.benbow.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.library_open_entry
import app.benbow.app.resources.media_remove
import app.benbow.app.resources.media_remove_confirm
import app.benbow.app.resources.media_unavailable
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.server_cancel
import app.benbow.client.EntryDetail
import app.benbow.client.library
import app.benbow.core.media.AttachmentKey
import app.benbow.core.sync.AttachmentState
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** One page of the viewer: an attachment and the entry holding it; [curtain] names its entry while that is under the curtain. */
internal data class ViewerItem(
    val entryId: String,
    val attachment: AttachmentState,
    val curtain: String? = null,
    /** The entry's day, said when the file doesn't know when it was made (the library's items come from many). */
    val day: String? = null,
) {
    val key: String get() = "$entryId/${attachment.meta.id}"
}

/**
 * An entry's attachments full screen, in gallery order: swipe sideways between them, pull down (or up) to close, tap to
 * hide the bars. Pictures zoom (pinch, double tap); while zoomed, dragging pans instead of swiping. A video plays only
 * on the page in view; its neighbours show their preview frame.
 */
@Composable
internal fun MediaScreen(services: AppServices, nav: Navigator, route: Route.Media) {
    val entry by services.repo.entry(route.entryId).collectAsStateWithLifecycle(null)
    val items = entry?.let { e -> e.attachments.map { ViewerItem(e.id, it) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items == null) return@Box
        if (items.isEmpty()) {
            LaunchedEffect(Unit) { nav.back() }
            return@Box
        }
        MediaPager(
            services,
            nav,
            items,
            start = items.indexOfFirst { it.attachment.meta.id == route.attachmentId }.coerceAtLeast(0),
            entryOf = { entry },
            confirmRemove = true,
        )
    }
}

/**
 * The library's viewer (ADR-041): the library's attachments as it shows them (its section, order and Бережно), across
 * entries, with «Открыть запись». Taking one out puts it in the trash at once, with «Вернуть» on the snackbar.
 */
@Composable
internal fun LibraryViewer(services: AppServices, nav: Navigator, route: Route.LibraryMedia) {
    val all by remember { services.repo.library() }.collectAsStateWithLifecycle(null)
    val gentle = LocalGentle.current
    val shown = remember(all, route.view, gentle) { all?.let { route.view.of(it, gentle) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = shown ?: return@Box
        if (list.isEmpty()) {
            LaunchedEffect(Unit) { nav.back() }
            return@Box
        }
        val byKey = remember(list) { list.associateBy { "${it.entryId}/${it.attachment.meta.id}" } }
        val items = remember(list, gentle) {
            list.map { item -> ViewerItem(item.entryId, item.attachment, item.postId.takeIf { item.curtained(gentle) }, item.day) }
        }
        MediaPager(
            services,
            nav,
            items,
            start = items.indexOfFirst { it.key == route.key }.coerceAtLeast(0),
            entryOf = { null },
            confirmRemove = false,
            onOpenEntry = { item -> byKey[item.key]?.let { nav.open(Route.Editor(it.postId, it.day, readOnly = true)) } },
        )
    }
}

@Composable
private fun MediaPager(
    services: AppServices,
    nav: Navigator,
    items: List<ViewerItem>,
    start: Int,
    entryOf: (String) -> EntryDetail?,
    /** An entry's own viewer asks first; the library's puts the attachment in the trash at once («Вернуть» brings it back). */
    confirmRemove: Boolean,
    onOpenEntry: ((ViewerItem) -> Unit)? = null,
) {
    val pager = rememberPagerState(initialPage = start) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var confirm by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    val dismissAt = with(LocalDensity.current) { 120.dp.toPx() }
    val drag = rememberDraggableState { dragY += it }
    val fade = (1f - abs(dragY) / (dismissAt * 4)).coerceIn(0f, 1f)
    LaunchedEffect(pager.currentPage) { zoomed = false }
    // Keys on a keyboard: ← and → go through the pictures, Esc closes.
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }
    val step = { by: Int ->
        val to = (pager.currentPage + by).coerceIn(0, items.lastIndex)
        if (to != pager.currentPage) scope.launch { pager.animateScrollToPage(to) }
    }
    val remove = { item: ViewerItem ->
        if (confirmRemove) {
            services.scope.launch { services.repo.removeAttachment(item.entryId, item.attachment.meta.id) }
        } else {
            services.trashMedia(listOf(AttachmentKey(item.entryId, item.attachment.meta.id)))
        }
        if (items.size == 1) nav.back()
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade))
            .focusRequester(keys)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> step(-1).let { true }
                    Key.DirectionRight -> step(1).let { true }
                    Key.Escape -> nav.back().let { true }
                    else -> false
                }
            }
            .focusable()
            .draggable(
                drag,
                Orientation.Vertical,
                enabled = !zoomed,
                onDragStopped = { velocity ->
                    if (abs(dragY) > dismissAt || abs(velocity) > 2_500f) {
                        nav.back()
                    } else {
                        scope.launch { animate(dragY, 0f) { value, _ -> dragY = value } }
                    }
                },
            ),
    ) {
        HorizontalPager(
            pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { items[it].key },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val active = page == pager.currentPage
            val curtain = item.curtain
            if (curtain != null && curtain !in opened) {
                // Under the curtain (Бережно) until a tap opens it, with the rest of its entry.
                Box(Modifier.fillMaxSize().hand().clickable { opened = opened + curtain }, contentAlignment = Alignment.Center) {
                    Curtain(Modifier.padding(24.dp).widthIn(max = 480.dp))
                }
                return@HorizontalPager
            }
            MediaPage(
                services,
                item.attachment,
                entryOf(item.entryId),
                active = active,
                onZoom = { if (active) zoomed = it },
                onTap = { chrome = !chrome },
                modifier = Modifier.graphicsLayer {
                    if (active) {
                        translationY = dragY
                        val shrink = 1f - (abs(dragY) / (dismissAt * 8)).coerceAtMost(0.15f)
                        scaleX = shrink
                        scaleY = shrink
                    }
                },
            )
        }
        if (chrome && dragY == 0f && !zoomed) PagerArrows(pager, size = 48.dp, inset = 16.dp)
        AnimatedVisibility(chrome && dragY == 0f, enter = fadeIn(), exit = fadeOut()) {
            val current = items.getOrNull(pager.currentPage)
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(nav::back, modifier = Modifier.hand()) { Icon(Icons.Back, null, tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    val months = stringArrayResource(Res.array.months_genitive)
                    val time =
                        current?.attachment?.meta?.capturedAt?.let { dateTimeLabel(it) } ?: current?.day?.let { dateLabel(it, months) }
                    time?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleSmall) }
                    if (items.size > 1) {
                        Text(
                            "${pager.currentPage + 1} / ${items.size}",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (current != null && onOpenEntry != null) {
                    TextButton({ onOpenEntry(current) }, modifier = Modifier.hand()) {
                        Text(stringResource(Res.string.library_open_entry), color = Color.White)
                    }
                }
                if (current != null) {
                    val label = stringResource(if (confirmRemove) Res.string.media_remove else Res.string.move_to_trash)
                    IconButton({ if (confirmRemove) confirm = true else remove(current) }, modifier = Modifier.hand()) {
                        Icon(Icons.Delete, label, tint = Color.White)
                    }
                }
            }
        }
    }
    val current = items.getOrNull(pager.currentPage)
    if (confirm && current != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(Res.string.media_remove_confirm)) },
            confirmButton = {
                TextButton({
                    confirm = false
                    remove(current)
                }, modifier = Modifier.hand()) { Text(stringResource(Res.string.media_remove)) }
            },
            dismissButton = {
                TextButton({ confirm = false }, modifier = Modifier.hand()) { Text(stringResource(Res.string.server_cancel)) }
            },
        )
    }
}

/** One page: the zoomable picture, the video player (only while in view), or a player or name for other files. */
@Composable
private fun MediaPage(
    services: AppServices,
    attachment: AttachmentState,
    entry: EntryDetail?,
    active: Boolean,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val meta = attachment.meta
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (meta.kind == "video" && !active) {
            attachment.visual?.let { sha ->
                val preview by rememberMediaFile(services.media, sha)
                (preview as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
            PlayBadge()
            return@Box
        }
        if (attachment.isSound) {
            // Sounds play in the app's player (ADR-035), from here on through the entry's sounds.
            val tracks = entry?.tracks.orEmpty()
            VoiceRow(
                services,
                entry?.id,
                attachment,
                entry?.transcripts?.get(meta.id),
                Modifier.padding(24.dp).widthIn(max = 480.dp),
                tags = tracks[meta.id],
                queue = { queue(entry?.id, entry?.attachments ?: listOf(attachment), tracks) },
            )
            return@Box
        }
        if (meta.kind == "drawing") {
            // On a sheet of paper: the ink is in the theme's text color, which a black backdrop would swallow.
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(16.dp)) {
                DrawingPicture(services.media, attachment, Modifier.padding(8.dp))
            }
            return@Box
        }
        when (val load = rememberMediaFile(services.media, meta.sha256).value) {
            MediaLoad.Loading -> Loading()

            is MediaLoad.Unavailable -> Text(
                stringResource(Res.string.media_unavailable, load.reason),
                color = Color.White,
                modifier = Modifier.padding(24.dp),
            )

            is MediaLoad.Ready -> if (meta.kind == "image") {
                ZoomableImage(load.file, onZoom = onZoom, onTap = onTap)
            } else {
                LaunchedEffect(Unit) { services.player.pause() } // a video's sound, not the music under it
                MediaPlayer(load.file, meta.mime)
            }
        }
    }
}

/** A round play button over a video's preview frame. */
@Composable
internal fun PlayBadge(modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = CircleShape, modifier = modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Play, null, Modifier.size(32.dp)) }
    }
}
