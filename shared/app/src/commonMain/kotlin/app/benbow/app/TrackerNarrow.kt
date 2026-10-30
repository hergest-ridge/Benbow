package app.benbow.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.trackers_drawer_hide
import app.benbow.app.resources.trackers_drawer_show
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

// A wide sheet on a phone (owner, ADR-059): the sleep sheet's hours take the whole row and the columns beside them slide
// out from the right ([SideDrawer]); months turn by pulling past the end of the page once it has stopped there
// ([MonthPull]), since a sideways finger paints hours now.

/**
 * The columns beside a sleep sheet's hours on a narrow window (a day's length, «Как»): hidden at first, pulled out
 * from the right by the grip down the sheet's edge, put away by a swipe back or a tap on the grip. [shown]: how far out,
 * 0 to 1; one for the whole sheet, so every row moves as one.
 */
@Stable
internal class SideDrawer(private val scope: CoroutineScope, private val still: Boolean) {
    val shown = Animatable(0f)

    /** Every row's part drags it alike. */
    val dragging = DraggableState { drag(it) }

    /** How wide the part it slides out is, in pixels, as the rows lay it out. */
    var width = 0f

    fun drag(delta: Float) {
        if (width <= 0f) return
        scope.launch { shown.snapTo((shown.value - delta / width).coerceIn(0f, 1f)) }
    }

    /** Let go: a fling decides, else whichever side it is nearer. */
    fun settle(velocity: Float) = to(
        when {
            velocity < -FLING -> true
            velocity > FLING -> false
            else -> shown.value > 0.5f
        },
    )

    fun toggle() = to(shown.value < 0.5f)

    private fun to(open: Boolean) {
        scope.launch { if (still) shown.snapTo(if (open) 1f else 0f) else shown.animateTo(if (open) 1f else 0f, SLIDE) }
    }
}

@Composable
internal fun rememberSideDrawer(key: Any): SideDrawer {
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    return remember(key) { SideDrawer(scope, still) }
}

/** A drag sideways moves [drawer] (left pulls it out, right puts it away). */
private fun Modifier.drawerDrag(drawer: SideDrawer): Modifier =
    draggable(drawer.dragging, Orientation.Horizontal, onDragStopped = { velocity -> drawer.settle(velocity) })

/**
 * A row's part in [drawer]: laid out [width] wide and shown as far as the drawer is out, clipped at its left edge, so
 * the hours beside it take the rest; a swipe on it moves the drawer.
 */
@Composable
internal fun RowScope.DrawerPart(drawer: SideDrawer, width: Dp, content: @Composable RowScope.() -> Unit) {
    val full = with(LocalDensity.current) { width.toPx() }
    drawer.width = full
    Row(
        Modifier.fillMaxHeight().clipToBounds()
            .layout { measurable, constraints ->
                val whole = full.roundToInt()
                val placeable = measurable.measure(constraints.copy(minWidth = whole, maxWidth = whole))
                layout((whole * drawer.shown.value).roundToInt(), placeable.height) { placeable.place(0, 0) }
            }
            .drawerDrag(drawer),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The grip down the sheet's right edge: dragged left it pulls the columns out, right puts them away; a tap does either.
 * In the header it shows which way it goes.
 */
@Composable
internal fun DrawerGrip(drawer: SideDrawer, header: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val open = drawer.shown.value > 0.5f
    val label = stringResource(if (open) Res.string.trackers_drawer_hide else Res.string.trackers_drawer_show)
    Box(
        Modifier.width(SheetSizes.GRIP).fillMaxHeight()
            .background(colors.onSurface.copy(alpha = SheetSizes.FOOT_ALPHA))
            .drawerDrag(drawer)
            .hand()
            .clickable { drawer.toggle() }
            .then(if (header) Modifier.semantics { contentDescription = label }.testTag("drawer") else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (header) {
            Icon(if (open) Icons.ChevronRight else Icons.ChevronLeft, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        } else {
            Box(Modifier.size(3.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(colors.outline.copy(alpha = SheetSizes.DOT_ALPHA)))
        }
    }
}

/**
 * Months by pulling (owner, ADR-059): a finger that comes down on a page already at its top (or bottom) and pulls on
 * past it turns to the month before (or after) once let go far enough; a page that only just reached its end stops
 * there first. [offset]: how far the page is pulled, in pixels (above zero: down at its top).
 */
@Stable
internal class MonthPull(private val scope: CoroutineScope, private val threshold: Float, private val onTurn: (Int) -> Unit) {
    var offset by mutableFloatStateOf(0f)
        private set
    private var fromTop = false
    private var fromBottom = false

    val ready: Boolean get() = abs(offset) >= threshold

    /** A finger came down: where the page stood. */
    fun press(atTop: Boolean, atBottom: Boolean) {
        fromTop = atTop
        fromBottom = atBottom
    }

    /** The finger is up: turn if pulled far enough, and the page springs back either way. */
    fun release() {
        fromTop = false
        fromBottom = false
        val was = offset
        if (was == 0f) return
        if (abs(was) >= threshold) onTurn(if (was > 0) -1 else 1)
        scope.launch { animate(was, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f)) { value, _ -> offset = value } }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Back towards the page while pulled: the pull gives way first.
            if (source != NestedScrollSource.UserInput || offset == 0f || available.y == 0f ||
                (offset > 0) == (available.y > 0)
            ) {
                return Offset.Zero
            }
            val next = offset + available.y * RESIST
            val kept = if (offset > 0) next.coerceAtLeast(0f) else next.coerceAtMost(0f)
            val used = (kept - offset) / RESIST
            offset = kept
            return Offset(0f, used)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
            if ((available.y > 0 && fromTop) || (available.y < 0 && fromBottom)) {
                offset += available.y * RESIST
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity = if (offset != 0f) available else Velocity.Zero
    }
}

@Composable
internal fun rememberMonthPull(onTurn: (Int) -> Unit): MonthPull {
    val scope = rememberCoroutineScope()
    val turn by rememberUpdatedState(onTurn)
    val threshold = with(LocalDensity.current) { PULL.toPx() }
    return remember { MonthPull(scope, threshold) { turn(it) } }
}

/** Whether a scroller stands at its top or its bottom, where a pull begins. */
internal fun ScrollableState.atTop(): Boolean = !canScrollBackward

internal fun ScrollableState.atBottom(): Boolean = !canScrollForward

/** The page under [pull]: pulled with the finger, and told where it stands when a finger comes down ([atTop], [atBottom]). */
internal fun Modifier.monthPull(pull: MonthPull, atTop: () -> Boolean, atBottom: () -> Boolean): Modifier = pointerInput(pull) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type == PointerType.Mouse) return@awaitEachGesture
        pull.press(atTop(), atBottom())
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
        } while (event.changes.any { it.pressed })
        pull.release()
    }
}.graphicsLayer { translationY = pull.offset }

/** The month a pull leads to, above the page or below it, clearer as the pull nears the point it turns at. */
@Composable
internal fun BoxScope.PullHint(pull: MonthPull, before: String, after: String) {
    val offset = pull.offset
    if (offset == 0f) return
    val colors = MaterialTheme.colorScheme
    val threshold = with(LocalDensity.current) { PULL.toPx() }
    val top = offset > 0
    Row(
        Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .graphicsLayer { translationY = offset / 2 }
            .padding(vertical = 8.dp)
            .alpha((abs(offset) / threshold).coerceIn(0f, 1f))
            .clip(RoundedCornerShape(16.dp))
            .background(if (pull.ready) colors.primaryContainer else colors.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (pull.ready) colors.onPrimaryContainer else colors.onSurface
        Icon(if (top) Icons.ArrowUp else Icons.ArrowDown, null, Modifier.size(16.dp), tint = tint)
        Text(if (top) before else after, Modifier.padding(start = 6.dp), color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

/** A pull this far turns the month; it moves the page at half the finger's way, as rubber would. */
private val PULL = 72.dp
private const val RESIST = 0.5f

/** A fling faster than this (px/s) decides the drawer's side whatever its place. */
private const val FLING = 600f
private val SLIDE = spring<Float>(dampingRatio = 0.85f, stiffness = 550f)
