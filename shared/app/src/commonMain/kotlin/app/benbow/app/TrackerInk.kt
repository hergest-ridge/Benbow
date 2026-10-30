package app.benbow.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// A tracker's marks in their style (owner, ADR-058): a day's cell or a run of the sleep sheet's hours (a «змейка»), each
// drawn into the room it is given. Everything is drawn in code from the ink, so every style follows the theme and the
// mark's color; a run's length goes along its row ([vertical]: down a day's column, a month across).

/**
 * Draws a mark of [style] in [ink] into [rect]: its length along the row ([vertical]: down), its thickness across.
 * [corner] rounds the plain styles (a cell's corner, a run's ends); [strength] (0 to 1) makes it fainter, as a count on
 * its way to the goal or a low rating; [units] is how many hours a run holds (beads, one per hour); [seed] keeps a hand
 * drawn style's wobble the same from frame to frame.
 */
internal fun DrawScope.drawMark(
    style: MarkStyle,
    ink: Color,
    rect: Rect,
    vertical: Boolean,
    corner: Float,
    strength: Float = 1f,
    units: Int = 1,
    seed: Int = 0,
) {
    if (rect.width <= 0.5f || rect.height <= 0.5f || strength <= 0f) return
    val mark = Mark(rect, vertical)
    val a = strength.coerceIn(0f, 1f)
    val round = min(corner, min(rect.width, rect.height) / 2)
    when (style) {
        MarkStyle.Soft -> roundRect(rect, round, ink.copy(alpha = SheetSizes.MARK_ALPHA * a))

        MarkStyle.Solid -> roundRect(rect, round, ink.copy(alpha = 0.88f * a))

        MarkStyle.Marker -> marker(mark, ink, a, seed)

        MarkStyle.Pencil -> pencil(mark, ink, a, round)

        MarkStyle.Watercolor -> watercolor(mark, ink, a, round, seed)

        MarkStyle.Tube -> tube(mark, ink, a, glossy = false)

        MarkStyle.Glossy -> tube(mark, ink, a, glossy = true)

        MarkStyle.Neon -> neon(mark, ink, a)

        MarkStyle.Outline -> {
            roundRect(rect, round, ink.copy(alpha = 0.1f * a))
            val inset = 0.8.dp.toPx()
            drawRoundRect(
                ink.copy(alpha = 0.85f * a),
                Offset(rect.left + inset, rect.top + inset),
                rect.size.copy(rect.width - 2 * inset, rect.height - 2 * inset),
                CornerRadius(max(0f, round - inset)),
                style = Stroke(1.6.dp.toPx()),
            )
        }

        MarkStyle.Beads -> beads(mark, ink, a, max(1, units))

        MarkStyle.Snake -> snake(mark, ink, a)

        MarkStyle.Stripes -> stripes(mark, ink, a, round)

        MarkStyle.Stitch -> {
            roundRect(rect, round, ink.copy(alpha = 0.16f * a))
            val inset = min(2.5.dp.toPx(), min(rect.width, rect.height) / 4)
            val path = roundPath(rect.deflate(inset), max(0f, round - inset))
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.5.dp.toPx(), 2.8.dp.toPx()))
            drawPath(path, ink.copy(alpha = 0.9f * a), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
        }

        MarkStyle.Tape -> tape(mark, ink, a)

        MarkStyle.Dawn -> dawn(mark, ink, a)
    }
}

/** A sample of [style] for its tile in «Оформление трекера»: a short run of three hours across the middle of [area]. */
internal fun DrawScope.drawMarkSample(style: MarkStyle, ink: Color, area: Rect = Rect(Offset.Zero, size)) {
    val thick = min(area.height * 0.42f, 18.dp.toPx())
    val length = area.width * 0.7f
    val rect = Rect(Offset(area.center.x - length / 2, area.center.y - thick / 2), Size(length, thick))
    drawMark(
        style,
        ink,
        rect,
        vertical = false,
        corner = if (style ==
            MarkStyle.Soft
        ) {
            SheetSizes.BAR_CORNER.toPx()
        } else {
            thick / 2
        },
        units = 3,
    )
}

/** A mark's room as length along and thickness across, and where a point along and across lies on the page. */
private class Mark(val rect: Rect, val vertical: Boolean) {
    val length = if (vertical) rect.height else rect.width
    val thick = if (vertical) rect.width else rect.height

    fun at(along: Float, across: Float) = if (vertical) {
        Offset(rect.left + across, rect.top + along)
    } else {
        Offset(
            rect.left + along,
            rect.top + across,
        )
    }

    /** A brush across the mark, from its top (or left, when vertical) to its bottom. */
    fun across(vararg stops: Pair<Float, Color>): Brush = if (vertical) {
        Brush.horizontalGradient(*stops, startX = rect.left, endX = rect.right)
    } else {
        Brush.verticalGradient(*stops, startY = rect.top, endY = rect.bottom)
    }

    /** A brush along the mark, from its start to its end. */
    fun along(vararg stops: Pair<Float, Color>): Brush = if (vertical) {
        Brush.verticalGradient(*stops, startY = rect.top, endY = rect.bottom)
    } else {
        Brush.horizontalGradient(*stops, startX = rect.left, endX = rect.right)
    }

    /** A part of the mark: [from] to [to] along, [top] to [bottom] across (fractions of its thickness). */
    fun part(from: Float, to: Float, top: Float, bottom: Float): Rect {
        val a = at(from, thick * top)
        val b = at(to, thick * bottom)
        return Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }
}

private fun DrawScope.roundRect(rect: Rect, radius: Float, color: Color) =
    drawRoundRect(color, rect.topLeft, rect.size, CornerRadius(radius))

private fun roundPath(rect: Rect, radius: Float) = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }

/** A wobble of up to [amount] that stays the same for the same [seed] and [i]. */
private fun wobble(seed: Int, i: Int, amount: Float): Float {
    val n = (seed * 73_856_093 xor i * 19_349_663).let { (it xor (it ushr 13)) * 1_274_126_177 }
    return ((n ushr 8) and 0xFF) / 255f * 2f * amount - amount
}

/** A highlighter: a flat stroke with a chisel's slanted ends, the ink pooling at them and a streak along. */
private fun DrawScope.marker(m: Mark, ink: Color, a: Float, seed: Int) {
    val k = min(m.thick * 0.35f, m.length * 0.25f)
    val w = 0.7.dp.toPx()
    val path = Path().apply {
        moveTo(m.at(k, wobble(seed, 1, w) + w).x, m.at(k, wobble(seed, 1, w) + w).y)
        m.at(m.length, wobble(seed, 2, w) + w).let { lineTo(it.x, it.y) }
        m.at(m.length - k, m.thick - w + wobble(seed, 3, w)).let { lineTo(it.x, it.y) }
        m.at(0f, m.thick - w + wobble(seed, 4, w)).let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, ink.copy(alpha = 0.42f * a))
    clipPath(path) {
        val edge = 2.5.dp.toPx()
        drawLine(ink.copy(alpha = 0.2f * a), m.at(k, 0f), m.at(0f, m.thick), edge)
        drawLine(ink.copy(alpha = 0.2f * a), m.at(m.length, 0f), m.at(m.length - k, m.thick), edge)
        drawLine(Color.White.copy(alpha = 0.16f * a), m.at(0f, m.thick * 0.3f), m.at(m.length, m.thick * 0.3f), 1.dp.toPx())
        drawLine(ink.copy(alpha = 0.12f * a), m.at(0f, m.thick * 0.72f), m.at(m.length, m.thick * 0.72f), 1.dp.toPx())
    }
}

/** Pencil hatching across, inside a sketched outline. */
private fun DrawScope.pencil(m: Mark, ink: Color, a: Float, round: Float) {
    val path = roundPath(m.rect, round)
    val step = 3.5.dp.toPx()
    clipPath(path) {
        var x = -m.thick
        while (x < m.length) {
            drawLine(ink.copy(alpha = 0.72f * a), m.at(x, m.thick), m.at(x + m.thick, 0f), 1.1.dp.toPx(), StrokeCap.Round)
            x += step
        }
    }
    drawPath(path, ink.copy(alpha = 0.55f * a), style = Stroke(1.dp.toPx()))
    val shift = 0.5.dp.toPx()
    drawPath(roundPath(m.rect.translate(shift, -shift), round), ink.copy(alpha = 0.22f * a), style = Stroke(0.8.dp.toPx()))
}

/** Watercolor: a thin wash, pools of pigment along it and a darker edge where the water dried. */
private fun DrawScope.watercolor(m: Mark, ink: Color, a: Float, round: Float, seed: Int) {
    roundRect(m.rect, round, ink.copy(alpha = 0.2f * a))
    val pools = max(1, (m.length / max(1f, m.thick)).roundToInt())
    clipPath(roundPath(m.rect.inflate(1.dp.toPx()), round)) {
        repeat(pools) { i ->
            val center = m.at(
                (i + 0.5f) * m.length / pools + wobble(seed, i, m.thick * 0.2f),
                m.thick / 2 + wobble(seed, i + 50, m.thick * 0.15f),
            )
            val radius = m.thick * (0.6f + wobble(seed, i + 99, 0.15f))
            drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.34f * a), ink.copy(alpha = 0f)), center, radius), radius, center)
        }
    }
    drawPath(roundPath(m.rect.deflate(0.6.dp.toPx()), round), ink.copy(alpha = 0.36f * a), style = Stroke(1.2.dp.toPx()))
}

/** A soft shadow under a raised mark: a few spreading layers below and to the right, as the light comes from above. */
private fun DrawScope.shadow(rect: Rect, radius: Float, a: Float, strength: Float) {
    for (i in 1..3) {
        val o = i * 0.9.dp.toPx()
        roundRect(rect.translate(o * 0.6f, o * 1.2f).inflate(o * 0.5f), radius + o * 0.5f, Color.Black.copy(alpha = strength * a))
    }
}

/** A raised tube with its shadow, lit from above; [glossy]: deeper, with a hard shine and a sparkle. */
private fun DrawScope.tube(m: Mark, ink: Color, a: Float, glossy: Boolean) {
    val pill = m.thick / 2
    shadow(m.rect, pill, a, if (glossy) 0.1f else 0.075f)
    val body = if (glossy) {
        m.across(0f to lerp(ink, Color.White, 0.25f), 0.55f to ink, 1f to lerp(ink, Color.Black, 0.5f))
    } else {
        m.across(0f to lerp(ink, Color.White, 0.45f), 0.5f to ink, 1f to lerp(ink, Color.Black, 0.35f))
    }
    drawRoundRect(body, m.rect.topLeft, m.rect.size, CornerRadius(pill), alpha = a)
    val inset = pill * if (glossy) 0.6f else 0.8f
    if (m.length - 2 * inset < m.thick * 0.3f) return
    if (glossy) {
        val shine = m.part(inset, m.length - inset, 0.08f, 0.46f)
        val radius = min(shine.width, shine.height) / 2
        drawRoundRect(
            m.across(0f to Color.White.copy(alpha = 0.75f * a), 0.5f to Color.White.copy(alpha = 0.1f * a)),
            shine.topLeft,
            shine.size,
            CornerRadius(radius),
        )
        drawCircle(Color.White.copy(alpha = 0.9f * a), m.thick * 0.07f, m.at(m.length - pill * 0.9f, m.thick * 0.28f))
    } else {
        val line = m.part(inset, m.length - inset, 0.16f, 0.36f)
        roundRect(line, min(line.width, line.height) / 2, Color.White.copy(alpha = 0.42f * a))
    }
}

/** Neon: a glow spreading around a light core in a bright tube. */
private fun DrawScope.neon(m: Mark, ink: Color, a: Float) {
    val pill = m.thick / 2
    for (i in 4 downTo 1) {
        val g = i * 1.5.dp.toPx()
        roundRect(m.rect.inflate(g), pill + g, ink.copy(alpha = 0.07f * a))
    }
    val inset = 0.75.dp.toPx()
    drawRoundRect(ink.copy(alpha = 0.9f * a), m.rect.topLeft, m.rect.size, CornerRadius(pill), style = Stroke(1.5.dp.toPx()))
    val core = m.part(m.thick * 0.22f, m.length - m.thick * 0.22f, 0.3f, 0.7f).deflate(inset / 2)
    if (core.width > 0f && core.height > 0f) roundRect(core, min(core.width, core.height) / 2, lerp(ink, Color.White, 0.6f).copy(alpha = a))
}

/** Beads on a string, one an hour, each lit from the top left. */
private fun DrawScope.beads(m: Mark, ink: Color, a: Float, units: Int) {
    val step = m.length / units
    val radius = min(m.thick, step) * 0.42f
    if (units > 1) {
        drawLine(
            ink.copy(alpha = 0.55f * a),
            m.at(step / 2, m.thick / 2),
            m.at(m.length - step / 2, m.thick / 2),
            m.thick * 0.16f,
            StrokeCap.Round,
        )
    }
    repeat(units) { i ->
        val center = m.at((i + 0.5f) * step, m.thick / 2)
        val light = center + Offset(-radius * 0.3f, -radius * 0.3f)
        val brush = Brush.radialGradient(listOf(lerp(ink, Color.White, 0.45f), ink, lerp(ink, Color.Black, 0.2f)), light, radius * 1.3f)
        drawCircle(brush, radius, center, alpha = a)
    }
}

/**
 * A snake (owner: «змейки снов»): a body that swells from its tail to its head and waves a little, scales along its
 * back, its head at the run's end with two eyes looking ahead and a tongue when there is room.
 */
private fun DrawScope.snake(m: Mark, ink: Color, a: Float) {
    val head = m.thick * 0.5f
    val wave = { t: Float -> sin(t / max(1f, m.thick * 1.2f)) * m.thick * 0.08f }
    val body = m.across(0f to lerp(ink, Color.White, 0.35f), 0.5f to ink, 1f to lerp(ink, Color.Black, 0.3f))
    val tail = m.thick * 0.2f
    val neck = max(tail, m.length - head)
    val step = max(1f, 1.2.dp.toPx())
    var t = tail
    while (t <= neck) {
        val p = if (neck > tail) (t - tail) / (neck - tail) else 1f
        drawCircle(body, m.thick * (0.18f + 0.27f * p * (2 - p)), m.at(t, m.thick / 2 + wave(t)), alpha = a)
        t += step
    }
    val scales = lerp(ink, Color.Black, 0.35f).copy(alpha = 0.55f * a)
    var s = tail + m.thick * 0.5f
    while (s < neck - m.thick * 0.3f) {
        drawCircle(scales, m.thick * 0.06f, m.at(s, m.thick / 2 + wave(s) - m.thick * 0.06f))
        s += m.thick * 0.7f
    }
    val center = m.at(m.length - head, m.thick / 2 + wave(m.length - head))
    drawCircle(body, head * 0.95f, center, alpha = a)
    if (m.length > m.thick * 1.6f) {
        val tip = m.at(m.length + m.thick * 0.12f, m.thick / 2 + wave(m.length - head))
        val fork = m.at(m.length + m.thick * 0.24f, m.thick / 2 + wave(m.length - head))
        val tongue = Color(0xFFE5484D).copy(alpha = a)
        val w = 1.dp.toPx()
        drawLine(tongue, m.at(m.length - head * 0.1f, m.thick / 2 + wave(m.length - head)), tip, w, StrokeCap.Round)
        drawLine(tongue, tip, fork + sideways(m, -m.thick * 0.08f), w, StrokeCap.Round)
        drawLine(tongue, tip, fork + sideways(m, m.thick * 0.08f), w, StrokeCap.Round)
    }
    for (side in listOf(-1f, 1f)) {
        val eye = center + forward(m, head * 0.3f) + sideways(m, side * m.thick * 0.18f)
        drawCircle(Color.White.copy(alpha = a), m.thick * 0.11f, eye)
        drawCircle(Color.Black.copy(alpha = a), m.thick * 0.055f, eye + forward(m, m.thick * 0.03f))
    }
}

private fun forward(m: Mark, by: Float) = if (m.vertical) Offset(0f, by) else Offset(by, 0f)

private fun sideways(m: Mark, by: Float) = if (m.vertical) Offset(by, 0f) else Offset(0f, by)

/** Candy stripes across a tint. */
private fun DrawScope.stripes(m: Mark, ink: Color, a: Float, round: Float) {
    val path = roundPath(m.rect, round)
    drawPath(path, ink.copy(alpha = 0.26f * a))
    val step = 7.dp.toPx()
    clipPath(path) {
        var x = -m.thick
        while (x < m.length) {
            drawLine(ink.copy(alpha = 0.55f * a), m.at(x, m.thick), m.at(x + m.thick, 0f), 2.6.dp.toPx())
            x += step
        }
    }
}

/** Washi tape: a see-through strip with torn ends and a dotted print. */
private fun DrawScope.tape(m: Mark, ink: Color, a: Float) {
    val teeth = 4
    val z = min(m.thick * 0.14f, m.length * 0.15f)
    val path = Path().apply {
        m.at(0f, 0f).let { moveTo(it.x, it.y) }
        m.at(m.length, 0f).let { lineTo(it.x, it.y) }
        for (i in 1..2 * teeth) m.at(if (i % 2 == 1) m.length - z else m.length, m.thick * i / (2 * teeth)).let { lineTo(it.x, it.y) }
        m.at(0f, m.thick).let { lineTo(it.x, it.y) }
        for (i in 1..2 * teeth) m.at(if (i % 2 == 1) z else 0f, m.thick * (1 - i.toFloat() / (2 * teeth))).let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, ink.copy(alpha = 0.4f * a))
    val step = 6.dp.toPx()
    val dot = Color.White.copy(alpha = 0.4f * a)
    clipPath(path) {
        var along = step / 2
        var row = 0
        while (along < m.length) {
            var across = if (row % 2 == 0) step / 2 else step
            while (across < m.thick) {
                drawCircle(dot, 1.dp.toPx(), m.at(along, across))
                across += step
            }
            along += step / 2
            row++
        }
    }
}

/** Dawn: from the night's deep ink at the start to the morning's warm light at the end, a sunrise glowing there. */
private fun DrawScope.dawn(m: Mark, ink: Color, a: Float) {
    val pill = m.thick / 2
    val sunrise = Color(0xFFFFC36B)
    val morning = lerp(ink, sunrise, 0.7f)
    drawRoundRect(
        m.along(0f to lerp(ink, Color.Black, 0.3f), 0.6f to ink, 1f to morning),
        m.rect.topLeft,
        m.rect.size,
        CornerRadius(pill),
        alpha = 0.82f * a,
    )
    val sun = m.at(m.length - pill, pill)
    drawCircle(Brush.radialGradient(listOf(sunrise.copy(alpha = 0.4f * a), sunrise.copy(alpha = 0f)), sun, m.thick), m.thick, sun)
}
