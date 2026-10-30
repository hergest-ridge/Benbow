package app.benbow.app

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.benbow.client.tracker.TrackerRepository
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerRecord
import app.benbow.ui.theme.BenbowTheme
import kotlin.math.roundToInt

// A tracker's own way of drawing its sheet (owner, ADR-058): its marks' color and style, its grid, its columns' sizes.
// Tokens on the tracker's and its columns' records; the sheet hands them to every cell below it (LocalSheetLook), and
// TrackerInk.kt, TrackerGrid.kt and TrackerSnakes.kt draw them. An unknown token draws the default and is kept.

/** How a tracker's marks are drawn: its `mark` field; none is the soft fill it had from the start. */
internal enum class MarkStyle(val token: String?) {
    Soft(null),
    Solid("solid"),
    Marker("marker"),
    Pencil("pencil"),
    Watercolor("watercolor"),
    Tube("tube"),
    Glossy("glossy"),
    Neon("neon"),
    Outline("outline"),
    Beads("beads"),
    Snake("snake"),
    Stripes("stripes"),
    Stitch("stitch"),
    Tape("tape"),
    Dawn("dawn"),
    ;

    companion object {
        fun of(token: String?): MarkStyle = entries.firstOrNull { it.token == token } ?: Soft
    }
}

/** Lines between a tracker's cells: its `grid` field; none keeps each cell's own faint frame. */
internal enum class GridStyle(val token: String?) {
    None(null),
    Lines("lines"),
    Table("table"),
    Dashed("dashed"),
    Dots("dots"),
    Pencil("pencil"),
    Ink("ink"),
    ;

    companion object {
        fun of(token: String?): GridStyle = entries.firstOrNull { it.token == token } ?: None
    }
}

/**
 * What a sheet's cells are drawn with: [ink] every column's marks (the tracker's `color`; null: each column's own), the
 * marks' [mark] style, the [grid], the columns' [sizes]; [onCleared] offers «Вернуть» after a right click cleared a day.
 */
internal class SheetLook(
    val ink: String? = null,
    val mark: MarkStyle = MarkStyle.Soft,
    val grid: GridStyle = GridStyle.None,
    val sizes: ColumnSizes = ColumnSizes(false),
    val onCleared: (undo: TrackerRepository.() -> Unit) -> Unit = {},
)

internal val LocalSheetLook = staticCompositionLocalOf { SheetLook() }

/** [tracker]'s look as its record says. */
internal fun sheetLookOf(tracker: TrackerRecord, sizes: ColumnSizes, onCleared: (TrackerRepository.() -> Unit) -> Unit) = SheetLook(
    ink = tracker[TrackerField.COLOR],
    mark = MarkStyle.of(tracker[TrackerField.MARK]),
    grid = GridStyle.of(tracker[TrackerField.GRID]),
    sizes = sizes,
    onCleared = onCleared,
)

/** A column's ink on the sheet: the tracker's color when it has one, else the column's own, else the theme's. */
@Composable
internal fun columnInk(column: TrackerColumn): Color {
    val theme = BenbowTheme.colors
    val token = LocalSheetLook.current.ink ?: column.record[ColumnField.COLOR]
    return token?.let { theme.tokens.mark(it, theme.dark) } ?: MaterialTheme.colorScheme.primary
}

/**
 * Columns' sizes on a sheet (owner, ADR-058): each column's `width` in a month down the page and `height` in a month
 * across, a multiple of the usual. While a border is dragged the sizes live here and are written once it is let go;
 * [stretchable] (the tracker's `resize`) shows the borders, and sizes set before hold either way.
 */
@Stable
internal class ColumnSizes(val stretchable: Boolean) {
    private val widths = mutableStateMapOf<String, Float>()
    private val heights = mutableStateMapOf<String, Float>()

    fun width(column: TrackerColumn): Float = widths[column.id] ?: factorOf(column.record[ColumnField.WIDTH])

    fun height(column: TrackerColumn): Float = heights[column.id] ?: factorOf(column.record[ColumnField.HEIGHT])

    fun setWidth(column: String, factor: Float) {
        widths[column] = factor
    }

    fun setHeight(column: String, factor: Float) {
        heights[column] = factor
    }

    /** The records have the sizes now (or never will): the drawn ones go back to them. */
    fun settle() {
        widths.clear()
        heights.clear()
    }
}

/** A size as a column keeps it: a multiple of the usual within reason; none, or one that doesn't read, is the usual. */
internal fun factorOf(text: String?): Float = text?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(MIN_FACTOR, MAX_FACTOR) ?: 1f

/** A size to write: two decimals, or null (the field cleared) for the usual one. */
internal fun factorText(factor: Float): String? {
    val hundredths = (factor.coerceIn(MIN_FACTOR, MAX_FACTOR) * 100).roundToInt()
    if (hundredths == 100) return null
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}".trimEnd('0').trimEnd('.')
}

/**
 * Two neighbours after the border between them moved [dx] pixels: [a] and [b] are their factors over their usual
 * weights [baseA] and [baseB], [widthA] and [widthB] their widths now. Their weights keep their sum, so no other column
 * moves, and neither gets narrower than [least] pixels.
 */
internal fun resizePair(
    a: Float,
    baseA: Float,
    widthA: Float,
    b: Float,
    baseB: Float,
    widthB: Float,
    dx: Float,
    least: Float,
): Pair<Float, Float> {
    val both = widthA + widthB
    if (both <= 0f) return a to b
    val perWeight = (a * baseA + b * baseB) / both
    val newA = (widthA + dx).coerceIn(minOf(least, both / 2), maxOf(both - least, both / 2))
    return (newA * perWeight / baseA).coerceIn(MIN_FACTOR, MAX_FACTOR) to
        ((both - newA) * perWeight / baseB).coerceIn(MIN_FACTOR, MAX_FACTOR)
}

/** A row [usual] pixels high at [factor] after its lower border moved [dy]: no lower than [least] pixels. */
internal fun resizeRow(factor: Float, usual: Float, dy: Float, least: Float): Float =
    if (usual <= 0f) factor else ((factor * usual + dy).coerceAtLeast(least) / usual).coerceIn(MIN_FACTOR, MAX_FACTOR)

internal const val MIN_FACTOR = 0.3f
internal const val MAX_FACTOR = 8f

/**
 * A border to drag between columns ([vertical]: a line down, dragged sideways) or under a row: a strip a little wider
 * than its line takes the pointer, the line thickens while hovered or dragged; a double click puts the size back.
 */
@Composable
internal fun StretchBorder(
    vertical: Boolean,
    tag: String,
    onDrag: (Float) -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val drag = rememberDraggableState(onDrag)
    val live = hovered || dragged
    val color = if (live) MaterialTheme.colorScheme.primary else BenbowTheme.colors.divider
    Box(
        modifier.then(if (vertical) Modifier.width(BORDER).fillMaxHeight() else Modifier.height(BORDER).fillMaxWidth())
            .hoverable(interaction)
            .pointerHoverIcon(if (vertical) ResizeColumnIcon else ResizeRowIcon)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
            .draggable(
                drag,
                if (vertical) Orientation.Horizontal else Orientation.Vertical,
                interactionSource = interaction,
                onDragStopped = { onStop() },
            )
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        val thickness = if (live) 3.dp else 1.dp
        if (vertical) VerticalDivider(thickness = thickness, color = color) else HorizontalDivider(thickness = thickness, color = color)
    }
}

private val BORDER = 9.dp
