package app.benbow.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A mouse drags what scrolls sideways (owner: a carousel in the web couldn't be moved with a mouse at all): Compose's
 * scrollables answer touch only. Every horizontal row, strip and pager in the app takes this ([sideScroll] for a plain
 * scrolling row), and new ones should too; touch stays with the scrollable itself. A pager settles on the page the drag
 * points to, anything else glides on a little after a quick drag; a press without a drag still clicks what it is on.
 * Put it outside the scrolling (before `horizontalScroll`, on a pager's or a row's own modifier).
 */
fun Modifier.mouseSwipe(state: ScrollableState): Modifier = pointerInput(state) {
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            var over = 0f
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop ->
                change.consume()
                over = slop
            } ?: return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(start.uptimeMillis, start.position)
            state.dispatchRawDelta(-over)
            horizontalDrag(start.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                state.dispatchRawDelta(-change.positionChange().x)
                change.consume()
            }
            val velocity = tracker.calculateVelocity().x
            launch { settle(state, velocity) }
        }
    }
}

/** `horizontalScroll` that a mouse can drag too. */
fun Modifier.sideScroll(state: ScrollState): Modifier = mouseSwipe(state).horizontalScroll(state)

private suspend fun settle(state: ScrollableState, velocity: Float) {
    if (state is PagerState) {
        val position = state.currentPage + state.currentPageOffsetFraction
        val page = when {
            velocity < -FLING -> ceil(position).toInt()
            velocity > FLING -> floor(position).toInt()
            else -> position.roundToInt()
        }
        state.animateScrollToPage(page.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
    } else if (abs(velocity) > FLING) {
        state.animateScrollBy(-velocity * GLIDE_SECONDS, tween(GLIDE_MILLIS, easing = LinearOutSlowInEasing))
    }
}

/** Pixels a second past which a drag is a throw: a pager turns the page, a row glides on. */
private const val FLING = 400f
private const val GLIDE_SECONDS = 0.18f
private const val GLIDE_MILLIS = 360
