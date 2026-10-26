package app.benbow.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.feed_scrubber_label
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Where each post of the feed sits, for the date scrubber (ADR-036 N3): its row and month, and where each year starts.
 * The scrubber goes by posts, not pixels: a busy month gets more of the track, a quiet year less.
 */
internal class ScrubIndex(rows: List<FeedRow>) {
    /** The row of each post, in feed order. */
    val rowOf: IntArray

    /** "YYYY-MM" of each post. */
    val monthOf: Array<String>

    init {
        val rowList = ArrayList<Int>()
        val months = ArrayList<String>()
        rows.forEachIndexed { row, it ->
            if (it is FeedRow.Item) {
                rowList += row
                months += it.month
            }
        }
        rowOf = rowList.toIntArray()
        monthOf = months.toTypedArray()
    }

    val size: Int get() = rowOf.size

    /** Each year's newest post as a share of the track, newest year first. */
    val years: List<Pair<String, Float>> = buildList {
        var last: String? = null
        monthOf.forEachIndexed { pos, month ->
            val year = month.take(4)
            if (year != last) add(year to share(pos))
            last = year
        }
    }

    fun share(pos: Int): Float = if (size <= 1) 0f else pos.toFloat() / (size - 1)

    fun posAt(share: Float): Int = (share.coerceIn(0f, 1f) * (size - 1)).roundToInt().coerceIn(0, (size - 1).coerceAtLeast(0))
}

/** The haptic a scrubbed month boundary gives: a stronger tick for a new year, none within a month. */
internal fun scrubTick(from: String?, to: String): HapticFeedbackType? = when {
    from == null || from == to -> null
    from.take(4) != to.take(4) -> HapticFeedbackType.SegmentTick
    else -> HapticFeedbackType.SegmentFrequentTick
}

/**
 * The feed's date scrubber (ADR-036 N3), over the feed's right edge. On a phone ([mouse] false) it shows while the
 * feed scrolls and fades 0.6 s after; its thumb is dragged (the system's back swipe gives way to it there), with a
 * tick at each month and a stronger one at each year. With a mouse the thumb stays; hovering its strip tells the month
 * there, a click goes there ([jump]: its posts read first), the thumb or the strip can be dragged. While it is held
 * or hovered, the month under it shows beside it and the years mark the track. Only for a feed long enough to need it.
 */
@Composable
internal fun FeedScrubber(
    rows: List<FeedRow>,
    state: LazyListState,
    mouse: Boolean,
    jump: suspend (pos: Int, row: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = remember(rows) { ScrubIndex(rows) }
    if (index.size < MIN_POSTS) return
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val label = stringResource(Res.string.feed_scrubber_label)
    // Where the feed is, as a share of its posts (the end once it can't go further).
    val current by remember(state, index) {
        derivedStateOf {
            if (!state.canScrollForward) 1f else index.share(rows.postNear(state.firstVisibleItemIndex) ?: 0)
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var hovered by remember { mutableStateOf<Float?>(null) }
    var shown by remember { mutableStateOf(mouse) }
    val active = mouse || dragging != null || state.isScrollInProgress
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(HIDE_AFTER_MILLIS)
            shown = false
        }
    }
    val thumbWidth by animateDpAsState(
        if (dragging != null || hovered != null) {
            8.dp
        } else if (mouse) {
            5.dp
        } else {
            6.dp
        },
        label = "thumb",
    )
    // With a mouse a narrow strip, so the posts' right edge stays clickable; on a phone a finger's width at the thumb.
    val strip = if (mouse) 16.dp else 36.dp

    BoxWithConstraints(modifier.fillMaxSize().padding(vertical = 8.dp)) {
        val height = with(density) { maxHeight.toPx() }
        val thumbHeight = with(density) { THUMB_HEIGHT.toPx() }
        val travel = (height - thumbHeight).coerceAtLeast(1f)
        val share = dragging ?: current
        val thumbTop = share * travel
        val where = monthTitle(index.monthOf[index.posAt(share)], withYear = true).orEmpty()
        // The pointer handlers live as long as the scrubber: the rows change under a drag as windows are read, and a
        // handler started again mid-drag would drop it and leave its month hanging. They read the latest of these.
        val latestIndex by rememberUpdatedState(index)
        val latestTravel by rememberUpdatedState(travel)
        val latestCurrent by rememberUpdatedState(current)

        fun shareAt(y: Float) = ((y - thumbHeight / 2) / latestTravel).coerceIn(0f, 1f)

        var lastMonth by remember { mutableStateOf<String?>(null) }
        fun scrubTo(to: Float) {
            val index = latestIndex
            dragging = to
            val pos = index.posAt(to)
            val month = index.monthOf[pos]
            scrubTick(lastMonth, month)?.let(haptic::performHapticFeedback)
            lastMonth = month
            scope.launch { state.scrollToItem(index.rowOf[pos]) }
        }

        val drag = Modifier.pointerInput(Unit) {
            try {
                detectVerticalDragGestures(
                    onDragStart = { lastMonth = latestIndex.monthOf[latestIndex.posAt(latestCurrent)] },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, dy ->
                    change.consume()
                    scrubTo(((dragging ?: latestCurrent) * latestTravel + dy) / latestTravel)
                }
            } finally {
                dragging = null
            }
        }

        // The years along the track and the month beside the thumb, while it is held or hovered: each on its own
        // backing, so they read over pictures too. They fade in and out (owner: they popped up and went too sharply),
        // fading out where they last were.
        val telling = dragging ?: hovered
        var told by remember { mutableStateOf<Float?>(null) }
        SideEffect { if (telling != null) told = telling }
        val tellAlpha by animateFloatAsState(
            if (telling != null) 1f else 0f,
            tween(if (telling != null) TELL_IN_MILLIS else TELL_OUT_MILLIS, easing = FastOutSlowInEasing),
            label = "telling",
        )
        val shownAt = telling ?: told
        if (shownAt != null && tellAlpha > 0f) {
            var lastY = -1_000f
            val gap = with(density) { 20.dp.toPx() }
            index.years.forEach { (year, at) ->
                val y = at * travel + thumbHeight / 2
                if (y - lastY < gap) return@forEach
                lastY = y
                Surface(
                    shape = RoundedCornerShape(50),
                    color = colors.surfaceContainerHighest.copy(alpha = 0.92f),
                    shadowElevation = 1.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = strip + 2.dp)
                        .offset { IntOffset(0, (y - 9.dp.toPx()).roundToInt()) }
                        .graphicsLayer { alpha = tellAlpha },
                ) {
                    Text(
                        year,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            val y = shownAt * travel + thumbHeight / 2
            Surface(
                shape = RoundedCornerShape(50),
                color = colors.primaryContainer,
                shadowElevation = 2.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = strip + 44.dp)
                    .offset { IntOffset(0, (y - 18.dp.toPx()).roundToInt()) }
                    .graphicsLayer { alpha = tellAlpha },
            ) {
                Text(
                    monthTitle(index.monthOf[index.posAt(shownAt)], withYear = true).orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        AnimatedVisibility(
            shown,
            Modifier.align(Alignment.TopEnd).fillMaxHeight().width(strip),
            enter = fadeIn(tween(SHOW_MILLIS)),
            exit = fadeOut(tween(FADE_MILLIS)),
        ) {
            // Hover is heard over the track and the thumb alike: it tells the month under the pointer.
            val hover = if (!mouse) {
                Modifier
            } else {
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val y = event.changes.firstOrNull()?.position?.y ?: continue
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> hovered = shareAt(y)
                                PointerEventType.Exit -> hovered = null
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize().then(hover)) {
                // The track shows with the years only: at rest the thumb is alone, unlike the line between two panes.
                AnimatedVisibility(
                    telling != null,
                    Modifier.align(Alignment.TopCenter).fillMaxHeight(),
                    enter = fadeIn(tween(TELL_IN_MILLIS, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(TELL_OUT_MILLIS, easing = FastOutSlowInEasing)),
                ) {
                    Box(
                        Modifier.fillMaxHeight().width(if (mouse) 6.dp else 2.dp)
                            .background(colors.onSurface.copy(alpha = if (mouse) 0.08f else 0.16f), RoundedCornerShape(50)),
                    )
                }
                if (mouse) {
                    // A click on the strip goes where it is clicked, a press drags.
                    Box(
                        Modifier.fillMaxSize().hand().pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val index = latestIndex
                                val pos = index.posAt(shareAt(offset.y))
                                scope.launch { jump(pos, index.rowOf[pos]) }
                            }
                        }.then(drag),
                    )
                }
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbTop.roundToInt()) }
                        .width(strip).height(THUMB_HEIGHT)
                        .keepFromSystemGestures()
                        .then(drag)
                        .semantics {
                            contentDescription = label
                            stateDescription = where
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.width(thumbWidth).fillMaxHeight().padding(vertical = 4.dp).clip(RoundedCornerShape(50))
                            .background(if (dragging != null) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}

/** Posts below which the feed needs no scrubber: a few flings reach its end. */
private const val MIN_POSTS = 50

/** On a phone it goes soon after the feed stops: in the way of nothing while one reads. */
private const val HIDE_AFTER_MILLIS = 600L
private const val SHOW_MILLIS = 120
private const val FADE_MILLIS = 200

// The track, the years and the month under the pointer: in gently, out a little slower.
private const val TELL_IN_MILLIS = 220
private const val TELL_OUT_MILLIS = 360
private val THUMB_HEIGHT = 48.dp
