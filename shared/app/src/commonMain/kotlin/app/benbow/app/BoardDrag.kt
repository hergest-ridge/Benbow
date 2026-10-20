package app.benbow.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridLayoutInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Carrying a card to another place in a staggered grid (ADR-040; the K0 spike, meant for K3). A finger lifts a card
 * after a long press, a mouse after a few pixels of dragging. The lifted card follows the pointer above the grid while
 * its own place, left blank, moves to the card under the pointer and the others glide aside (`animateItem`); near the
 * grid's top and bottom the grid scrolls by itself. Let go, the card lands in its place.
 *
 * Coordinates are the board's: the box that holds the grid, takes the gestures and draws the lifted card.
 */
@Stable
internal class BoardDrag(
    val grid: LazyStaggeredGridState,
    /** Which grid items are cards (headers aren't). */
    private val isCard: (Any) -> Boolean,
    /** Whether the lifted card may take the place of another (pinned and other cards stay apart). */
    private val canSwap: (lifted: Any, other: Any) -> Boolean,
    /** Puts the lifted card where the other one is, the rest moving one place along. */
    private val move: (lifted: Any, other: Any) -> Unit,
) {
    /** The lifted card; null while none is. */
    var key: Any? by mutableStateOf(null)
        private set

    /** The lifted card's top-left as drawn, and its size. */
    var offset by mutableStateOf(Offset.Zero)
        private set
    var size by mutableStateOf(IntSize.Zero)
        private set

    /** 0 resting, 1 lifted: the card's scale and shadow. */
    val lift = Animatable(0f)

    /** The board's layout, the grid's place in it and the blank place's bounds as drawn (gliding included). */
    var board: LayoutCoordinates? = null
    var gridBounds = Rect.Zero
    var slot: Rect? = null

    private var pointer = Offset.Zero
    private var grab = Offset.Zero
    private var over: Any? = null
    private var movedIn: LazyStaggeredGridLayoutInfo? = null
    private var landing = false

    /** An item's bounds in the board, where the grid has placed it (not where an animation shows it on the way). */
    fun boundsOf(item: LazyStaggeredGridItemInfo): Rect {
        val top = gridBounds.top + item.offset.y - grid.layoutInfo.viewportStartOffset
        return Rect(Offset(gridBounds.left + item.offset.x, top), item.size.toSize())
    }

    /** The card under [point], if any. */
    fun cardAt(point: Offset): LazyStaggeredGridItemInfo? =
        grid.layoutInfo.visibleItemsInfo.firstOrNull { isCard(it.key) && boundsOf(it).contains(point) }

    fun pickUp(card: LazyStaggeredGridItemInfo, at: Offset) {
        val bounds = boundsOf(card)
        size = card.size
        offset = bounds.topLeft
        slot = bounds
        grab = at - bounds.topLeft
        pointer = at
        over = null
        movedIn = null
        key = card.key
    }

    /** The pointer moved: the card follows, and takes the place of the card under the pointer. */
    fun follow(to: Offset, haptic: HapticFeedback) {
        pointer = to
        offset = to - grab
        retarget(haptic)
    }

    private fun retarget(haptic: HapticFeedback) {
        val lifted = key ?: return
        val layout = grid.layoutInfo
        if (layout === movedIn) return // the last move isn't laid out yet: the old bounds would move it twice
        val under = layout.visibleItemsInfo.firstOrNull {
            it.key != lifted && isCard(it.key) && canSwap(lifted, it.key) && boundsOf(it).contains(pointer)
        }?.key
        // Once the two have changed places the other card may still be under the pointer: it has to leave it first.
        if (under != over) over = null
        if (under == null || over != null) return
        // A grid keeps its first visible item in place; when that one moves, keep the scroll where it is instead.
        val first = layout.visibleItemsInfo.minByOrNull { it.index }?.key
        if (first == lifted || first == under) grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
        move(lifted, under)
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        over = under
        movedIn = layout
    }

    /** While a card is carried: near the grid's edges the grid scrolls, faster the closer the pointer is. */
    suspend fun scrollNearEdges(edge: Float, fastest: Float, haptic: HapticFeedback) {
        var last = withFrameNanos { it }
        while (key != null && !landing) {
            val now = withFrameNanos { it }
            val seconds = ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            val top = gridBounds.top + edge
            val bottom = gridBounds.bottom - edge
            val push = when {
                pointer.y < top -> -min(1f, (top - pointer.y) / edge)
                pointer.y > bottom -> min(1f, (pointer.y - bottom) / edge)
                else -> 0f
            }
            if (push != 0f && grid.scrollBy(push * abs(push) * fastest * seconds) != 0f) retarget(haptic)
        }
    }

    /** Let go: the card settles into its place (following it while it still glides there), then shows in the grid. */
    suspend fun land() = coroutineScope {
        landing = true
        launch { lift.animateTo(0f, tween(180)) }
        var last = withFrameNanos { it }
        var spent = 0f
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1e9f
            last = now
            spent += seconds
            val target = slot?.topLeft ?: break
            offset += (target - offset) * (1f - exp(-seconds / 0.05f))
            if (((target - offset).getDistance() < 0.5f && !lift.isRunning) || spent > 0.6f) break
        }
        key = null
        slot = null
        landing = false
    }
}

/**
 * The board's gestures, taken before the grid sees them (it scrolls only when they let it): a finger held still on a
 * card lifts it (no move before letting go: [onChoose]); a finger that moves first scrolls; two fingers pinch for
 * fewer or more columns ([onPinch] with -1 or +1). A mouse drags a card at once and clicks it open as usual.
 * The callbacks are read once (the gestures live as long as [drag]): they should reach the state they change.
 */
internal fun Modifier.boardGestures(
    drag: BoardDrag,
    scope: CoroutineScope,
    haptic: HapticFeedback,
    edge: Float,
    fastest: Float,
    onChoose: (Any) -> Unit,
    onPinch: (Int) -> Unit,
    /** A carried card was let go where it now is (its place is to be kept). */
    onDrop: (Any) -> Unit,
): Modifier = pointerInput(drag) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val card = drag.cardAt(down.position).takeIf { drag.key == null }
        if (down.type == PointerType.Mouse) {
            if (card == null || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val start = awaitSlop(down) ?: return@awaitEachGesture // a click: the card opens
            drag.pickUp(card, down.position)
            scope.launch { drag.lift.animateTo(1f, tween(120)) }
            scope.launch { drag.scrollNearEdges(edge, fastest, haptic) }
            drag.follow(start.position, haptic)
            carry(drag, haptic)
            onDrop(card.key)
            scope.launch { drag.land() }
            return@awaitEachGesture
        }
        val still = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitMoveOrUp(down) }
        when {
            still == Press.Pinch -> pinch(onPinch)

            still == null && card != null -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                drag.pickUp(card, down.position)
                scope.launch { drag.lift.animateTo(1f, tween(120)) }
                scope.launch { drag.scrollNearEdges(edge, fastest, haptic) }
                val moved = carry(drag, haptic, slopFrom = down.position)
                if (moved) onDrop(card.key) else onChoose(card.key)
                scope.launch { drag.land() }
            }

            still != Press.Up -> watchForPinch(onPinch) // a scroll, or a long press beside the cards
        }
    }
}

private enum class Press { Moved, Up, Pinch }

/** Waits while the finger stays put; returns how that ended (never, if it outlasts the caller's timeout). */
private suspend fun AwaitPointerEventScope.awaitMoveOrUp(down: PointerInputChange): Press {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.count { it.pressed } > 1) return Press.Pinch
        val change = event.changes.firstOrNull { it.id == down.id } ?: return Press.Up
        if (!change.pressed) return Press.Up
        if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return Press.Moved
    }
}

/** A mouse: the change that went past the slop, or null when the button came up first. */
private suspend fun AwaitPointerEventScope.awaitSlop(down: PointerInputChange): PointerInputChange? {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) return null
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            change.consume()
            return change
        }
    }
}

/** Follows the pointer until it is let go, keeping every change from the grid and the card; returns whether it moved. */
private suspend fun AwaitPointerEventScope.carry(drag: BoardDrag, haptic: HapticFeedback, slopFrom: Offset? = null): Boolean {
    var moved = slopFrom == null
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull() ?: return moved
        event.changes.forEach { it.consume() }
        if (!change.pressed) return moved
        if (!moved && (change.position - slopFrom!!).getDistance() > viewConfiguration.touchSlop) moved = true
        if (moved) drag.follow(change.position, haptic)
    }
}

/** A gesture that started as something else may still become a pinch when a second finger comes. */
private suspend fun AwaitPointerEventScope.watchForPinch(onPinch: (Int) -> Unit) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val fingers = event.changes.count { it.pressed }
        if (fingers == 0) return
        if (fingers > 1) return pinch(onPinch)
    }
}

/** Two fingers apart by a quarter more: one column fewer (bigger cards); closer by a fifth: one more. */
private suspend fun AwaitPointerEventScope.pinch(onPinch: (Int) -> Unit) {
    var base = 0f
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val fingers = event.changes.filter { it.pressed }
        if (fingers.isEmpty()) return
        event.changes.forEach { it.consume() } // no scrolling while pinching
        if (fingers.size < 2) {
            base = 0f
            continue
        }
        val distance = (fingers[0].position - fingers[1].position).getDistance()
        when {
            base == 0f -> base = distance
            distance / base > 1.25f -> onPinch(-1).also { base = distance }
            distance / base < 0.8f -> onPinch(1).also { base = distance }
        }
    }
}
