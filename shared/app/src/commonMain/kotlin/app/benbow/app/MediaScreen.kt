package app.benbow.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.media_remove
import app.benbow.app.resources.media_remove_confirm
import app.benbow.app.resources.media_unavailable
import app.benbow.app.resources.server_cancel
import app.benbow.core.sync.AttachmentState
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * An entry's attachments full screen, in gallery order: swipe sideways between them, pull down (or up) to close, tap to
 * hide the bars. Pictures zoom (pinch, double tap); while zoomed, dragging pans instead of swiping. A video plays only
 * on the page in view; its neighbours show their preview frame.
 */
@Composable
internal fun MediaScreen(services: AppServices, nav: Navigator, route: Route.Media) {
    val entry by services.repo.entry(route.entryId).collectAsStateWithLifecycle(null)
    val items = entry?.attachments
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items == null) return@Box
        if (items.isEmpty()) {
            LaunchedEffect(Unit) { nav.back() }
            return@Box
        }
        MediaPager(services, nav, route.entryId, items, start = items.indexOfFirst { it.meta.id == route.attachmentId }.coerceAtLeast(0))
    }
}

@Composable
private fun MediaPager(services: AppServices, nav: Navigator, entryId: String, items: List<AttachmentState>, start: Int) {
    val pager = rememberPagerState(initialPage = start) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismissAt = with(LocalDensity.current) { 120.dp.toPx() }
    val drag = rememberDraggableState { dragY += it }
    val fade = (1f - abs(dragY) / (dismissAt * 4)).coerceIn(0f, 1f)
    LaunchedEffect(pager.currentPage) { zoomed = false }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade)).draggable(
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
            key = { items[it].meta.id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val active = page == pager.currentPage
            MediaPage(
                services,
                items[page],
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
        AnimatedVisibility(chrome && dragY == 0f, enter = fadeIn(), exit = fadeOut()) {
            val current = items.getOrNull(pager.currentPage)
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(nav::back) { Icon(Icons.Back, null, tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    current?.meta?.capturedAt?.let {
                        Text(dateTimeLabel(it), color = Color.White, style = MaterialTheme.typography.titleSmall)
                    }
                    if (items.size > 1) {
                        Text(
                            "${pager.currentPage + 1} / ${items.size}",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (current !=
                    null
                ) {
                    IconButton({ confirm = true }) { Icon(Icons.Delete, stringResource(Res.string.media_remove), tint = Color.White) }
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
                    services.scope.launch { services.repo.removeAttachment(entryId, current.meta.id) }
                    if (items.size == 1) nav.back()
                }) { Text(stringResource(Res.string.media_remove)) }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(Res.string.server_cancel)) } },
        )
    }
}

/** One page: the zoomable picture, the video player (only while in view), or a player or name for other files. */
@Composable
private fun MediaPage(
    services: AppServices,
    attachment: AttachmentState,
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
