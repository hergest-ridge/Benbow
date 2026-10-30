package app.benbow.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.benbow.ui.theme.BenbowTheme
import kotlin.math.max

// A tracker's grid (owner, ADR-058: for reading the sheet at a glance, in a style of its own): every cell draws its own
// right and bottom edge, so the lines meet without doubling; the sleep sheet's strip draws its hours' lines inside it.

/** The grid's colors in this theme: its [line], a [strong] one (weeks, six hours), a pencil's and the marks' [ink]. */
internal class GridInk(val line: Color, val strong: Color, val pencil: Color, val ink: Color)

/** The sheet's grid as cells draw it: its style and colors. */
internal class GridPaint(val style: GridStyle, val colors: GridInk)

/** The sheet's grid with the column's [ink]; null without one. */
@Composable
internal fun sheetGrid(ink: Color = MaterialTheme.colorScheme.primary): GridPaint? {
    val style = LocalSheetLook.current.grid
    return if (style == GridStyle.None) null else GridPaint(style, gridInk(ink))
}

/** The grid's colors in this theme, with the marks' [ink]. */
@Composable
internal fun gridInk(ink: Color): GridInk {
    val colors = MaterialTheme.colorScheme
    return GridInk(BenbowTheme.colors.divider, colors.outlineVariant, colors.outline.copy(alpha = PENCIL_ALPHA), ink)
}

private const val PENCIL_ALPHA = 0.6f

/**
 * A cell's edges in the tracker's [grid]: its right one and its bottom one, [strongRight] and [strongBottom] drawn
 * heavier where the style marks them (the table's weeks). Nothing without a grid.
 */
internal fun Modifier.gridCell(
    grid: GridPaint?,
    right: Boolean = true,
    bottom: Boolean = true,
    strongRight: Boolean = false,
    strongBottom: Boolean = false,
    seed: Int = 0,
): Modifier {
    if (grid == null) return this
    val (style, colors) = grid.style to grid.colors
    return drawBehind {
        if (right) gridLine(style, colors, Offset(size.width, 0f), Offset(size.width, size.height), strongRight, seed)
        if (bottom) gridLine(style, colors, Offset(0f, size.height), Offset(size.width, size.height), strongBottom, seed + 1)
        if (style == GridStyle.Dots && right && bottom) gridDot(colors, Offset(size.width, size.height), strongRight || strongBottom)
    }
}

/**
 * A line of the grid from [from] to [to], in [style]: thin, a table's (heavier where [strong]), dashed, a pencil's that
 * wobbles a little, or in the marks' ink; dots draw at corners instead ([gridDot]).
 */
internal fun DrawScope.gridLine(
    style: GridStyle,
    colors: GridInk,
    from: Offset,
    to: Offset,
    strong: Boolean = false,
    seed: Int = 0,
    minor: Boolean = false,
) {
    val thin = 1.dp.toPx()
    val fade = if (minor) 0.55f else 1f
    // The line lies just inside the cell, so the next cell's own frame never covers it.
    val inside = Offset(if (from.x == to.x) -thin / 2 else 0f, if (from.y == to.y) -thin / 2 else 0f)
    val a = from + inside
    val b = to + inside
    when (style) {
        GridStyle.None, GridStyle.Dots -> Unit

        GridStyle.Lines -> drawLine(colors.line.copy(alpha = colors.line.alpha * fade), a, b, thin)

        GridStyle.Table -> if (strong) {
            drawLine(colors.strong, a, b, 1.6.dp.toPx())
        } else {
            drawLine(colors.line.copy(alpha = colors.line.alpha * fade), a, b, thin)
        }

        GridStyle.Dashed -> drawLine(
            (if (strong) colors.strong else colors.line).copy(alpha = (if (strong) colors.strong else colors.line).alpha * fade),
            a,
            b,
            thin,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )

        GridStyle.Pencil -> {
            val path = Path()
            val length = (b - a).getDistance()
            val steps = max(1, (length / 6.dp.toPx()).toInt())
            val across = if (a.x == b.x) Offset(1f, 0f) else Offset(0f, 1f)
            path.moveTo(a.x, a.y)
            for (i in 1..steps) {
                val p = a + (b - a) * (i.toFloat() / steps) + across * pencilWobble(seed, i, 0.6.dp.toPx())
                path.lineTo(p.x, p.y)
            }
            drawPath(path, colors.pencil.copy(alpha = colors.pencil.alpha * fade * if (strong) 1.6f else 1f), style = Stroke(thin))
        }

        GridStyle.Ink -> drawLine(colors.ink.copy(alpha = (if (strong) 0.8f else 0.42f) * fade), a, b, if (strong) 1.4.dp.toPx() else thin)
    }
}

/** A dot of the dot grid at a corner, as a bullet journal's page has. */
internal fun DrawScope.gridDot(colors: GridInk, at: Offset, strong: Boolean = false) {
    drawCircle(if (strong) colors.strong else colors.line, (if (strong) 1.8.dp else 1.3.dp).toPx(), at)
}

private fun pencilWobble(seed: Int, i: Int, amount: Float): Float {
    val n = (seed * 92_837_111 xor i * 689_287_499).let { (it xor (it ushr 15)) * 2_246_822_519.toInt() }
    return ((n ushr 9) and 0xFF) / 255f * 2f * amount - amount
}

/** A hovered cell (owner, ADR-058): a light state layer and a ring in the column's ink. */
internal fun DrawScope.drawHover(rect: Rect, corner: Float, ink: Color, layer: Color) {
    drawRoundRect(layer, rect.topLeft, rect.size, CornerRadius(corner))
    val w = 1.5.dp.toPx()
    drawRoundRect(
        ink.copy(alpha = 0.7f),
        rect.topLeft + Offset(w / 2, w / 2),
        rect.size.copy(rect.width - w, rect.height - w),
        CornerRadius(corner),
        style = Stroke(w),
    )
}

/** A sample of [style] for its tile in «Оформление трекера»: a few cells' worth of the grid. */
internal fun DrawScope.drawGridSample(style: GridStyle, colors: GridInk) {
    val columns = 5
    val rows = 3
    val w = size.width / columns
    val h = size.height / rows
    for (c in 1 until columns) {
        gridLine(style, colors, Offset(c * w, 0f), Offset(c * w, size.height), strong = c == 3, seed = c)
    }
    for (r in 1 until rows) {
        gridLine(style, colors, Offset(0f, r * h), Offset(size.width, r * h), strong = r == 2, seed = 10 + r)
    }
    if (style == GridStyle.Dots) {
        for (c in 1 until columns) for (r in 1 until rows) gridDot(colors, Offset(c * w, r * h), strong = c == 3 || r == 2)
    }
    if (style == GridStyle.None) {
        for (c in 0 until columns) {
            for (r in 0 until rows) {
                val inset = 2.dp.toPx()
                drawRoundRect(
                    colors.strong.copy(alpha = 0.6f),
                    Offset(c * w + inset, r * h + inset),
                    Size(w - 2 * inset, h - 2 * inset),
                    CornerRadius(4.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
            }
        }
    }
}
