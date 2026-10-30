package app.benbow.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.benbow.core.tracker.HourWindow
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The sleep sheet's snakes (owner, ADR-058: «резиновые»): a day's painted hours drawn as runs whose ends spring to where
// they belong, stretching after the pointer while a drag goes on and settling on whole hours once it is let go; a run
// swells and thins as it stretches. The hours under them are only taps' targets; the strip draws everything.

/** A stretch of painted hours on a day's strip, from [start] to [end] in hours (fractional while a drag stretches it). */
internal data class HourRun(val start: Float, val end: Float) {
    val length: Float get() = end - start
}

/** Painted hours as runs: each stretch of neighbours one run. */
internal fun runsOf(painted: List<Boolean>): List<HourRun> {
    val runs = mutableListOf<HourRun>()
    var from = -1
    for (i in 0..painted.size) {
        val on = i < painted.size && painted[i]
        if (on && from < 0) from = i
        if (!on && from >= 0) {
            runs += HourRun(from.toFloat(), i.toFloat())
            from = -1
        }
    }
    return runs
}

/**
 * What a strip shows while a drag goes on: its painted runs with the stretch from the hour [from] to the pointer at [at]
 * (hours, fractional) joined to them, or cut out of them when [erasing] (the drag started on a painted hour).
 */
internal fun dragRuns(painted: List<Boolean>, from: Int, at: Float, erasing: Boolean): List<HourRun> {
    val count = painted.size.toFloat()
    val low = minOf(from.toFloat(), at).coerceIn(0f, count)
    val high = maxOf(from + 1f, at).coerceIn(0f, count)
    val runs = runsOf(painted)
    return if (erasing) cut(runs, low, high) else join(runs + HourRun(low, high))
}

/**
 * Whether a drag from the hour [from] to [at] (hours, fractional) takes hours out (owner, ADR-059): one that starts on
 * an empty hour paints; one that starts on a painted hour and goes on along its snake cuts it; one that starts at a
 * snake's end and goes out past it stretches the snake. Null while the pointer is still on a painted hour it started
 * on: nothing is decided yet.
 */
internal fun dragErases(painted: List<Boolean>, from: Int, at: Float): Boolean? {
    if (!painted.getOrElse(from) { false }) return false
    if (at >= from && at < from + 1) return null
    return painted.getOrElse(if (at >= from + 1) from + 1 else from - 1) { false }
}

/** Where a drag from [from] to the hour [to] lands once let go: whole hours, as [dragEdits] writes them. */
internal fun droppedRuns(painted: List<Boolean>, from: Int, to: Int, erasing: Boolean): List<HourRun> =
    dragRuns(painted, minOf(from, to), maxOf(from, to) + 1f, erasing)

private fun join(runs: List<HourRun>): List<HourRun> = runs.sortedBy { it.start }.fold(mutableListOf()) { out, run ->
    val last = out.lastOrNull()
    if (last != null && run.start <= last.end) out[out.lastIndex] = HourRun(last.start, max(last.end, run.end)) else out += run
    out
}

private fun cut(runs: List<HourRun>, low: Float, high: Float): List<HourRun> = runs.flatMap { run ->
    listOf(HourRun(run.start, min(run.end, low)), HourRun(max(run.start, high), run.end))
        .filter { it.length > EPSILON && (it.end <= low || it.start >= high) }
}

private fun overlap(a: HourRun, b: HourRun): Float = min(a.end, b.end) - max(a.start, b.start)

/** A run as drawn: its ends spring towards its [goal]; [leaving] ones shrink into a neighbour or to nothing. */
@Stable
internal class RubberRun(start: Float, end: Float) {
    val start = Animatable(start)
    val end = Animatable(end)
    var goal by mutableStateOf(HourRun(start, end))
    var leaving = false

    /**
     * How much thicker than at rest (owner: rubbery): thinner while stretched past its goal or moving fast, fuller while
     * it is still short of it, as rubber keeps its volume.
     */
    fun swell(): Float {
        val stretch = if (goal.length > EPSILON) ((end.value - start.value) / goal.length).coerceIn(0.2f, 3f) else 1f
        val speed = abs(start.velocity) + abs(end.velocity)
        return (1f / sqrt(stretch)).coerceIn(0.82f, 1.18f) * (1f - (speed * SPEED_THINNING).coerceAtMost(0.15f))
    }
}

/**
 * The runs [target] asks for, each matched to the one drawn that it overlaps most, so a run grows, shrinks, splits and
 * joins rather than being drawn anew: a new one comes out of the run it was cut from, or from its middle; one no longer
 * there goes into the run it joined, or to its middle. [dragging]: the ends follow the pointer more tightly.
 */
@Composable
internal fun rubberRuns(target: List<HourRun>, dragging: Boolean): List<RubberRun> {
    val runs = remember { mutableStateListOf<RubberRun>().apply { addAll(target.map { RubberRun(it.start, it.end) }) } }
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    LaunchedEffect(target) {
        retarget(
            runs,
            target,
            scope,
            if (still) {
                null
            } else if (dragging) {
                FOLLOW
            } else {
                RUBBER
            },
        )
    }
    return runs
}

private fun retarget(runs: SnapshotStateList<RubberRun>, target: List<HourRun>, scope: CoroutineScope, spec: AnimationSpec<Float>?) {
    val free = runs.toMutableList()
    for (goal in target) {
        val run = free.filter { overlap(it.goal, goal) >= 0f }.maxByOrNull { overlap(it.goal, goal) }?.also { free.remove(it) }
            ?: runs.firstOrNull { min(it.end.value, goal.end) > max(it.start.value, goal.start) }
                ?.let { parent -> RubberRun(parent.start.value, parent.end.value) }?.also { runs += it }
            ?: ((goal.start + goal.end) / 2).let { middle -> RubberRun(middle, middle) }.also { runs += it }
        run.leaving = false
        run.goal = goal
        scope.launch { spring(run, goal, spec) }
    }
    for (run in free) {
        val into = target.filter { overlap(it, run.goal) >= 0f }.maxByOrNull { overlap(it, run.goal) }
        val goal = into ?: ((run.goal.start + run.goal.end) / 2).let { HourRun(it, it) }
        run.leaving = true
        run.goal = goal
        scope.launch {
            spring(run, goal, spec)
            if (run.leaving) runs.remove(run)
        }
    }
}

private suspend fun spring(run: RubberRun, goal: HourRun, spec: AnimationSpec<Float>?) = coroutineScope {
    if (spec == null) {
        run.start.snapTo(goal.start)
        run.end.snapTo(goal.end)
    } else {
        launch { run.start.animateTo(goal.start, spec) }
        launch { run.end.animateTo(goal.end, spec) }
    }
}

/** A drag on a strip: the hour it started on, where the pointer is now (in hours), whether it takes hours out. */
private data class HourDrag(val from: Int, val at: Float, val erasing: Boolean?)

/**
 * A day's hours of a sleep column, along a row ([DayRow]) or down a day's column when [down] (a month across): a tap
 * paints or clears one, a drag the hours it passes ([hourGestures], [dragEdits]; from a snake's end outwards it
 * stretches the snake, [dragErases]), a long press alone opens the day's spans ([onSpans]), a right click clears the
 * day's row with «Вернуть». A mouse lights the hour under it; each hour a drag reaches ticks under a finger.
 */
@Composable
internal fun HourStrip(
    column: TrackerColumn,
    day: LocalDate,
    window: HourWindow,
    own: List<TrackerRecord>,
    enabled: Boolean,
    write: TrackerWrite,
    onSpans: () -> Unit,
    modifier: Modifier,
    down: Boolean = false,
) {
    val date = "$day"
    val look = LocalSheetLook.current
    val haptic = LocalHapticFeedback.current
    val ink = columnInk(column)
    val grid = sheetGrid(ink)
    val layer = MaterialTheme.colorScheme.onSurface.copy(alpha = SheetSizes.HOVER_ALPHA)
    val dot = MaterialTheme.colorScheme.outline.copy(alpha = SheetSizes.DOT_ALPHA)
    val starts = hourStarts(window, day, column.dayBy)
    val hours = window.hours
    val painted = starts.map { covered(own, it) }
    var drag by remember(column.id, date) { mutableStateOf<HourDrag?>(null) }
    // A drop's runs until the store has them, so a let-go drag never jumps back for a frame.
    var dropped by remember(column.id, date) { mutableStateOf<List<HourRun>?>(null) }
    var hovered by remember(column.id, date) { mutableStateOf<Int?>(null) }
    LaunchedEffect(painted) { dropped = null }
    LaunchedEffect(dropped) { if (dropped != null) delay(DROP_HOLD_MILLIS).also { dropped = null } }
    val target = drag?.let { d -> d.erasing?.let { dragRuns(painted, d.from, d.at, it) } ?: runsOf(painted) } ?: dropped ?: runsOf(painted)
    val runs = rubberRuns(target, dragging = drag != null)
    val hoveredRun = hovered?.takeIf { painted.getOrElse(it) { false } && drag == null }
    val hoverSwell by animateFloatAsState(if (hoveredRun != null) HOVER_SWELL else 1f, RUBBER)
    val tapHour = { i: Int ->
        val edits = hourTap(own, starts[i])
        write { together { edits.forEach { editSpan(column.id, it) } } }
    }
    val gestures = if (!enabled) {
        Modifier
    } else {
        Modifier.hand().hourGestures(
            starts.size,
            own,
            down,
            onTap = tapHour,
            onHold = { onSpans() },
            onDrag = { from, at, held ->
                val was = drag
                when {
                    was == null -> haptic.performHapticFeedback(if (held) HapticFeedbackType.LongPress else HapticFeedbackType.SegmentTick)
                    was.at.toInt() != at.toInt() -> haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                // Decided once, as the pointer first leaves the hour it started on, and kept for the whole drag.
                drag = HourDrag(from, at, was?.erasing ?: dragErases(painted, from, at))
            },
            onCancel = { drag = null },
            onDrop = { from, to ->
                val erasing = drag?.erasing ?: dragErases(painted, from, to + 0.5f) ?: false
                dropped = droppedRuns(painted, from, to, erasing)
                drag = null
                val edits = dragEdits(own, starts, from, to, erasing)
                write { together { edits.forEach { editSpan(column.id, it) } } }
            },
            onHover = { hovered = it },
            onClear = {
                val clear = rowClear(own, starts)
                if (clear != null) {
                    write { clearDay(column.id, date, clear) }
                    look.onCleared { unclearDay(column.id, date, clear) }
                }
            },
        )
    }
    val drawing = Modifier.gridCell(grid).drawBehind {
        val count = starts.size
        val length = if (down) size.height else size.width
        val across = if (down) size.width else size.height
        val slot = length / count
        fun span(from: Float, to: Float, inset: Float, thick: Float): Rect {
            val a = from * slot + inset
            val b = to * slot - inset
            val side = (across - thick) / 2
            return if (down) Rect(side, a, side + thick, b) else Rect(a, side, b, side + thick)
        }
        if (grid != null) {
            for (i in 1 until count) {
                val at = i * slot
                val strong = hours.getOrNull(i)?.let { it % STRONG_HOURS == 0 } == true
                val (from, to) = if (down) Offset(0f, at) to Offset(size.width, at) else Offset(at, 0f) to Offset(at, size.height)
                if (grid.style == GridStyle.Dots) {
                    gridDot(grid.colors, if (down) Offset(size.width, at) else Offset(at, size.height), strong)
                } else {
                    gridLine(grid.style, grid.colors, from, to, strong = strong, seed = i, minor = !strong)
                }
            }
        } else {
            painted.forEachIndexed { i, on -> if (!on) drawCircle(dot, 1.5.dp.toPx(), span(i + 0.5f, i + 0.5f, 0f, 0f).center) }
        }
        hovered?.takeIf { drag == null }?.let { i ->
            drawHover(span(i.toFloat(), i + 1f, 1.dp.toPx(), across - 2.dp.toPx()), SheetSizes.CELL_CORNER.toPx(), ink, layer)
        }
        val rest = across * SheetSizes.BAR_HEIGHT
        val gap = 1.dp.toPx()
        for (run in runs) {
            val from = run.start.value
            val to = run.end.value
            if (to - from < EPSILON) continue
            val swollen = if (hoveredRun != null && hoveredRun + 0.5f in run.goal.start..run.goal.end) hoverSwell else 1f
            val thick = (rest * run.swell() * swollen).coerceAtMost(across)
            val rect = span(from, to, gap, thick)
            val corner = if (look.mark == MarkStyle.Soft) SheetSizes.BAR_CORNER.toPx() else thick / 2
            drawMark(look.mark, ink, rect, down, corner, units = max(1, run.goal.length.roundToInt()), seed = from.roundToInt())
        }
    }
    val cell = @Composable { i: Int, weight: Modifier ->
        HourCell(enabled = enabled, tag = "${TrackerCells.id(column.id, date)}:$i", modifier = weight, vertical = down) { tapHour(i) }
    }
    if (down) {
        Column(modifier.then(drawing).then(gestures)) { starts.indices.forEach { cell(it, Modifier.weight(1f)) } }
    } else {
        Row(modifier.then(drawing).then(gestures)) { starts.indices.forEach { cell(it, Modifier.weight(1f)) } }
    }
}

/**
 * Taps and drags on a day's hours ([count] of them along the row, or down when [down]; gestures restarting when [key]
 * changes): a tap on one ([onTap]); a drag over them ([onDrag] with where it started, where the pointer is, in hours, as
 * it goes, and whether it began with a long press; [onDrop] with where it started and ended). A mouse starts one by
 * pressing and moving (owner, ADR-051); a finger along a row by moving sideways at once (owner, ADR-059: months no
 * longer swipe there, so a sideways finger is free), while an upright move still scrolls the sheet; a finger down a
 * day's column (a month across, whose days scroll sideways) after a long press. A long press with no drag opens the
 * day's spans ([onHold]). A mouse's hover tells its hour ([onHover]) and its right click clears the day ([onClear]),
 * seen before the taps and drags, which then let that press be.
 */
private fun Modifier.hourGestures(
    count: Int,
    key: Any,
    down: Boolean,
    onTap: (Int) -> Unit,
    onHold: (Int) -> Unit,
    onDrag: (Int, Float, Boolean) -> Unit,
    onCancel: () -> Unit,
    onDrop: (Int, Int) -> Unit,
    onHover: (Int?) -> Unit,
    onClear: () -> Unit,
): Modifier = pointerInput(count, key, down) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            if (change.type != PointerType.Mouse) continue
            val hour = hourAt(if (down) change.position.y else change.position.x, if (down) size.height else size.width, count)
            when (event.type) {
                PointerEventType.Press -> if (event.buttons.isSecondaryPressed) {
                    change.consume()
                    onClear()
                } else {
                    onHover(null)
                }

                PointerEventType.Exit -> onHover(null)

                PointerEventType.Enter, PointerEventType.Move -> onHover(if (event.buttons.areAnyPressed) null else hour)

                else -> Unit
            }
        }
    }
}.pointerInput(count, key, down) {
    detectTapGestures(onTap = { onTap(hourAt(if (down) it.y else it.x, if (down) size.height else size.width, count)) })
}.pointerInput(count, key, down) {
    // After the taps' detector in the chain, so it sees each event first and takes what it drags (no tap after a drag).
    val length = { if (down) size.height else size.width }
    val along = { p: Offset -> if (down) p.y else p.x }
    val at = { p: Offset -> hourAt(along(p), length(), count) }
    val fraction = { p: Offset -> if (length() <= 0) 0f else (along(p) / length() * count).coerceIn(0f, count.toFloat()) }
    awaitEachGesture {
        val press = awaitFirstDown(requireUnconsumed = false)
        if (press.isConsumed) return@awaitEachGesture // a right click, taken above
        var clicked = false
        // The move that passed the slop: a drag from the start. None: held still, a long press.
        val moving = when {
            press.type == PointerType.Mouse -> withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                awaitTouchSlopOrCancellation(press.id) { change, _ -> change.consume() }.also { clicked = it == null }
            }

            !down -> withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                awaitHorizontalTouchSlopOrCancellation(press.id) { change, _ -> change.consume() }.also { clicked = it == null }
            }

            else -> {
                clicked = awaitLongPressOrCancellation(press.id) == null
                null
            }
        }
        if (clicked) return@awaitEachGesture // a tap, or a finger scrolling the sheet
        // One that moved paints at once; one held still may still be only a hold.
        val held = moving == null
        val from = at(press.position)
        var to = at((moving ?: press).position)
        var moved = !held
        onDrag(from, fraction((moving ?: press).position), held)
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == press.id }
            if (change == null) {
                onCancel()
                return@awaitEachGesture
            }
            change.consume()
            if (!change.pressed) break
            val now = at(change.position)
            if (now != to) {
                to = now
                moved = true
            }
            onDrag(from, fraction(change.position), held)
        }
        if (moved) {
            onDrop(from, to)
        } else {
            onCancel()
            onHold(from)
        }
    }
}

/** A run's ends spring a little past where they belong and settle (owner: rubbery, but firm: once past, then home). */
private val RUBBER = spring<Float>(dampingRatio = 0.62f, stiffness = 700f)

/** While dragged, the ends keep close to the pointer, with a little give. */
private val FOLLOW = spring<Float>(dampingRatio = 0.62f, stiffness = 900f)
private const val HOVER_SWELL = 1.12f
private const val SPEED_THINNING = 0.006f
private const val STRONG_HOURS = 6
private const val DROP_HOLD_MILLIS = 2_000L
private const val EPSILON = 0.001f
