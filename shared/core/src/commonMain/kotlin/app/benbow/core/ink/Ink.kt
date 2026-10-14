package app.benbow.core.ink

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * A drawing in an entry (7.5, ADR-026): strokes on a canvas [width]×[height] logical units, kept as an attachment (an
 * SVG that any browser opens, with these strokes in its metadata for editing). Colors are palette tokens; [INK] is the
 * text color of the theme.
 */
@Serializable
data class InkDrawing(val width: Int = WIDTH, val height: Int = WIDTH * 3 / 4, val strokes: List<InkStroke> = emptyList()) {
    companion object {
        const val WIDTH = 1000
        const val INK = "ink"
    }
}

/** One stroke: [points] are x, y (logical units) and t (ms from the stroke's start), three numbers per point. */
@Serializable
data class InkStroke(val tool: InkTool, val color: String, val size: Float, val points: List<Float>) {
    val count get() = points.size / 3

    fun x(i: Int) = points[i * 3]

    fun y(i: Int) = points[i * 3 + 1]

    fun t(i: Int) = points[i * 3 + 2]
}

@Serializable
enum class InkTool { Pen, Marker }

/** Builds a stroke from input as it comes, dropping points too close to the last one (they only add noise). */
class InkStrokeBuilder(private val tool: InkTool, private val color: String, private val size: Float) {
    private val points = ArrayList<Float>()

    fun add(x: Float, y: Float, t: Float) {
        val n = points.size
        if (n >= 3 && hypot(x - points[n - 3], y - points[n - 2]) < MIN_STEP) return
        points += round1(x)
        points += round1(y)
        points += round(t)
    }

    fun build() = InkStroke(tool, color, size, points.toList())

    private companion object {
        const val MIN_STEP = 0.8f

        fun round1(v: Float) = round(v * 10) / 10
    }
}

/** Shapes to fill or stroke: shared by the app's canvas and the SVG, so both look the same. */
object InkGeometry {
    /** Markers are wide, see-through and even; pens get their width from speed (a finger has no pressure). */
    const val MARKER_ALPHA = 0.35f

    /**
     * The filled outline of a pen stroke as x, y pairs: the width narrows as the finger moves faster (down to half),
     * eased so it doesn't jitter, with round ends.
     */
    fun outline(stroke: InkStroke): FloatArray {
        val n = stroke.count
        if (n == 0) return FloatArray(0)
        val xs = smooth(FloatArray(n) { stroke.x(it) })
        val ys = smooth(FloatArray(n) { stroke.y(it) })
        val half = widths(stroke).map { it / 2 }
        if (n == 1) return circle(xs[0], ys[0], half[0], 0.0, 2 * kotlin.math.PI, CAP_STEPS * 2)
        val left = ArrayList<Float>(n * 2)
        val right = ArrayList<Float>(n * 2)
        var nx = 0f
        var ny = 0f
        for (i in 0 until n) {
            val a = max(0, i - 1)
            val b = min(n - 1, i + 1)
            var tx = xs[b] - xs[a]
            var ty = ys[b] - ys[a]
            val len = hypot(tx, ty)
            if (len > 1e-4f) {
                tx /= len
                ty /= len
                nx = -ty
                ny = tx
            }
            left += xs[i] + nx * half[i]
            left += ys[i] + ny * half[i]
            right += xs[i] - nx * half[i]
            right += ys[i] - ny * half[i]
        }
        val out = ArrayList<Float>(n * 4 + CAP_STEPS * 4)
        out += left
        // End cap: half a circle from the left edge round to the right one.
        val endAngle = kotlin.math.atan2((left[left.size - 1] - ys[n - 1]).toDouble(), (left[left.size - 2] - xs[n - 1]).toDouble())
        out += circle(xs[n - 1], ys[n - 1], half[n - 1], endAngle, endAngle - kotlin.math.PI, CAP_STEPS).toList()
        for (i in n - 1 downTo 0) {
            out += right[i * 2]
            out += right[i * 2 + 1]
        }
        val startAngle = kotlin.math.atan2((right[1] - ys[0]).toDouble(), (right[0] - xs[0]).toDouble())
        out += circle(xs[0], ys[0], half[0], startAngle, startAngle - kotlin.math.PI, CAP_STEPS).toList()
        return out.toFloatArray()
    }

    /** The marker's center line as x, y pairs (drawn with round caps and joins at the stroke's size). */
    fun centerline(stroke: InkStroke): FloatArray {
        val xs = smooth(FloatArray(stroke.count) { stroke.x(it) })
        val ys = smooth(FloatArray(stroke.count) { stroke.y(it) })
        return FloatArray(stroke.count * 2) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }
    }

    /** Whether a circle at [x], [y] of [radius] touches the stroke (the eraser removes whole strokes). */
    fun hits(stroke: InkStroke, x: Float, y: Float, radius: Float): Boolean {
        val reach = radius + stroke.size / 2
        if (stroke.count == 1) return hypot(stroke.x(0) - x, stroke.y(0) - y) <= reach
        for (i in 1 until stroke.count) {
            if (distanceToSegment(x, y, stroke.x(i - 1), stroke.y(i - 1), stroke.x(i), stroke.y(i)) <= reach) return true
        }
        return false
    }

    /** Pen widths per point: slower is fuller. */
    internal fun widths(stroke: InkStroke): FloatArray {
        val n = stroke.count
        val out = FloatArray(n)
        var w = stroke.size
        for (i in 0 until n) {
            val target = if (i == 0) {
                stroke.size
            } else {
                val d = hypot(stroke.x(i) - stroke.x(i - 1), stroke.y(i) - stroke.y(i - 1))
                val speed = d / max(1f, stroke.t(i) - stroke.t(i - 1)) // units per ms
                stroke.size * (1 - 0.5f * min(1f, speed / FAST))
            }
            w = if (i == 0) target else w * 0.75f + target * 0.25f
            out[i] = w
        }
        return out
    }

    private fun smooth(v: FloatArray): FloatArray {
        if (v.size < 3) return v
        return FloatArray(v.size) { i -> if (i == 0 || i == v.size - 1) v[i] else (v[i - 1] + 2 * v[i] + v[i + 1]) / 4 }
    }

    private fun circle(cx: Float, cy: Float, r: Float, from: Double, to: Double, steps: Int) = FloatArray(steps * 2 + 2) {
        val a = from + (to - from) * (it / 2) / steps
        if (it % 2 == 0) cx + r * cos(a).toFloat() else cy + r * sin(a).toFloat()
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (abs(len2) < 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private const val CAP_STEPS = 8

    /** Units per ms at which a pen line is at its thinnest (a quick flick across the canvas). */
    private const val FAST = 3f
}
