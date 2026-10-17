package app.benbow.app

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign

/** What a key does to a list (desktop and web, ADR-036 N2). */
internal sealed interface ScrollKey {
    /** PgUp / PgDn and Space: a screen less a tenth, so the last lines stay in sight. */
    data class Page(val down: Boolean) : ScrollKey

    /** ↑ / ↓: a step. */
    data class Step(val down: Boolean) : ScrollKey

    data object Home : ScrollKey

    data object End : ScrollKey

    /** Stops the middle button's autoscroll. */
    data object Escape : ScrollKey
}

internal fun scrollKeyOf(key: Key, shift: Boolean, ctrl: Boolean, alt: Boolean): ScrollKey? = when {
    alt -> null
    key == Key.MoveHome -> ScrollKey.Home
    key == Key.MoveEnd -> ScrollKey.End
    ctrl -> null
    key == Key.PageDown -> ScrollKey.Page(down = true)
    key == Key.PageUp -> ScrollKey.Page(down = false)
    key == Key.Spacebar -> ScrollKey.Page(down = !shift)
    key == Key.DirectionDown -> ScrollKey.Step(down = true)
    key == Key.DirectionUp -> ScrollKey.Step(down = false)
    key == Key.Escape -> ScrollKey.Escape
    else -> null
}

/**
 * How fast the middle button's autoscroll goes, in dp per second, with the pointer [dy] dp below (or above) the
 * point it was pressed at: still within [DEAD_ZONE_DP], then faster the farther it goes, up to [MAX_SPEED_DP].
 */
internal fun autoscrollSpeed(dy: Float): Float {
    val distance = abs(dy) - DEAD_ZONE_DP
    if (distance <= 0f) return 0f
    return sign(dy) * min(MAX_SPEED_DP, distance * 6f + distance * distance * 0.04f)
}

/** A short press of the middle button with hardly a move keeps the autoscroll going after it is let go. */
internal fun autoscrollSticks(heldMillis: Long, moved: Float): Boolean = heldMillis < STICKY_MILLIS && moved < STICKY_MOVE_DP

/**
 * Desktop and web (ADR-036 N2): a list moves with PgUp / PgDn and Space (a screen less a tenth, gliding; a held key
 * keeps one glide going instead of queuing them), ↑ / ↓, Home and End ([home], [end]: each list knows its ends), and
 * with the middle button: the list scrolls toward the pointer, faster the farther it is from where it was pressed;
 * a click with hardly a move keeps it going until the next click, Esc or the wheel. The list takes the keyboard
 * whenever it is pressed on, and when it shows unless [takeFocus] is false (search: its field keeps the typing).
 * Off ([enabled] false) on phones.
 */
@Composable
internal fun Modifier.pageScrolling(
    state: ScrollableState,
    enabled: Boolean,
    takeFocus: Boolean = true,
    home: suspend () -> Unit,
    end: suspend () -> Unit,
): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    val scrolling = remember(state) { PageScroller(state, scope) }
    val focus = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    val step = with(LocalDensity.current) { STEP_DP.dp.toPx() }
    LaunchedEffect(focus) { if (takeFocus) runCatching { focus.requestFocus() } }
    // The keys sit outside the focus target, so they hear it (and whatever inside it lets a key go).
    return this
        .onSizeChanged { scrolling.viewport = it.height.toFloat() }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val key = scrollKeyOf(event.key, event.isShiftPressed, event.isCtrlPressed || event.isMetaPressed, event.isAltPressed)
            when (key) {
                null -> false

                ScrollKey.Escape -> scrolling.stopAuto()

                ScrollKey.Home -> {
                    scrolling.stopAuto()
                    scope.launch { home() }
                    true
                }

                ScrollKey.End -> {
                    scrolling.stopAuto()
                    scope.launch { end() }
                    true
                }

                is ScrollKey.Page -> {
                    scrolling.glide((if (key.down) 1f else -1f) * scrolling.viewport * PAGE_SHARE)
                    true
                }

                is ScrollKey.Step -> {
                    scrolling.glide((if (key.down) 1f else -1f) * step)
                    true
                }
            }
        }
        .focusRequester(focus)
        .focusable()
        .pointerInput(scrolling) {
            awaitPointerEventScope {
                var pressedAt = 0L
                var swallow = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    when (event.type) {
                        PointerEventType.Press -> {
                            runCatching { focus.requestFocus() }
                            if (scrolling.anchor != null) {
                                // The click that ends a sticky autoscroll ends it and nothing more (no post opens).
                                scrolling.stopAuto()
                                swallow = true
                                change.consume()
                            } else if (event.buttons.isTertiaryPressed) {
                                pressedAt = change.uptimeMillis
                                scrolling.startAuto(change.position, density)
                                change.consume()
                            }
                        }

                        PointerEventType.Move -> if (scrolling.anchor != null) {
                            scrolling.pointer = change.position
                            change.consume()
                        }

                        PointerEventType.Release -> when {
                            swallow -> {
                                swallow = false
                                change.consume()
                            }

                            scrolling.anchor != null && !scrolling.sticky -> {
                                val moved = (change.position - scrolling.anchor!!).getDistance() / density
                                if (autoscrollSticks(
                                        change.uptimeMillis - pressedAt,
                                        moved,
                                    )
                                ) {
                                    scrolling.sticky = true
                                } else {
                                    scrolling.stopAuto()
                                }
                                change.consume()
                            }
                        }

                        PointerEventType.Scroll -> scrolling.stopAuto()
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()
            scrolling.anchor?.let { drawAnchor(it, colors.surfaceContainerHighest, colors.onSurface) }
        }
}

/** Home for a lazy list: its first item. */
internal suspend fun LazyListState.toFirst() = scrollToItem(0)

/** End for a lazy list: its last item. */
internal suspend fun LazyListState.toLast() = scrollToItem((layoutInfo.totalItemsCount - 1).coerceAtLeast(0))

/** The keys' glide and the middle button's autoscroll of one list. */
@Stable
private class PageScroller(private val state: ScrollableState, private val scope: CoroutineScope) {
    var viewport = 0f

    /** Where the middle button was pressed while the autoscroll runs (drawn there), null otherwise. */
    var anchor by mutableStateOf<Offset?>(null)
        private set
    var pointer = Offset.Zero
    var sticky = false

    private var remaining = 0f
    private var glide: Job? = null
    private var auto: Job? = null

    /** Adds [by] px to the glide under way, or starts one: it eases out over about a fifth of a second. */
    fun glide(by: Float) {
        stopAuto()
        remaining += by
        if (glide?.isActive == true) return
        glide = scope.launch {
            try {
                state.scroll(MutatePriority.UserInput) {
                    var last = withFrameNanos { it }
                    while (abs(remaining) > 0.5f) {
                        val now = withFrameNanos { it }
                        val step = remaining * (1f - exp(-(now - last) / GLIDE_NANOS))
                        last = now
                        val used = scrollBy(step)
                        remaining -= step
                        if (abs(used) < abs(step) / 2) break // the list's end
                    }
                }
            } finally {
                remaining = 0f
            }
        }
    }

    fun startAuto(at: Offset, density: Float) {
        glide?.cancel()
        anchor = at
        pointer = at
        sticky = false
        auto = scope.launch {
            try {
                state.scroll(MutatePriority.UserInput) {
                    var last = withFrameNanos { it }
                    while (true) {
                        val now = withFrameNanos { it }
                        val from = anchor ?: break
                        val speed = autoscrollSpeed((pointer.y - from.y) / density) * density
                        scrollBy(speed * (now - last) / 1_000_000_000f)
                        last = now
                    }
                }
            } finally {
                anchor = null
                sticky = false
            }
        }
    }

    /** True when there was an autoscroll to stop. */
    fun stopAuto(): Boolean {
        val running = anchor != null
        anchor = null
        sticky = false
        auto?.cancel()
        return running
    }
}

/** The autoscroll's mark where the middle button was pressed: a round button with an arrow up and an arrow down. */
private fun DrawScope.drawAnchor(at: Offset, fill: Color, ink: Color) {
    val radius = 14.dp.toPx()
    drawCircle(fill.copy(alpha = 0.92f), radius, at)
    drawCircle(ink.copy(alpha = 0.5f), radius, at, style = Stroke(1.dp.toPx()))
    val w = 4.dp.toPx()
    val h = 4.dp.toPx()
    val gap = 3.dp.toPx()
    for (up in listOf(true, false)) {
        val tip = if (up) at.y - gap - h else at.y + gap + h
        val base = if (up) at.y - gap else at.y + gap
        drawPath(
            Path().apply {
                moveTo(at.x, tip)
                lineTo(at.x - w, base)
                lineTo(at.x + w, base)
                close()
            },
            ink.copy(alpha = 0.8f),
        )
    }
}

/** A page is the viewport less a tenth: the last lines of the screen before stay in sight at the top. */
private const val PAGE_SHARE = 0.9f
private const val STEP_DP = 56f
private const val GLIDE_NANOS = 60_000_000f
private const val DEAD_ZONE_DP = 12f
private const val MAX_SPEED_DP = 6_000f
private const val STICKY_MILLIS = 300L
private const val STICKY_MOVE_DP = 8f
