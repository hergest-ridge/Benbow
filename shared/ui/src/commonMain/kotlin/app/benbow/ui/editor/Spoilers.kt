package app.benbow.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import app.benbow.core.text.Mark
import app.benbow.core.text.RichText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Spoilers (ADR-031): the text sits under shimmering dots and its glyphs aren't drawn until a tap opens every spoiler
// of the entry. The dots are one small tile of random points drawn in three drifting, pulsing layers, so a frame costs
// three clipped rectangles however long the text is, and they live in a graphics layer of their own, so the text
// itself isn't redrawn for every frame.

/** The spoilers of this text inside `[from, to)`, as ranges relative to [from]. */
fun RichText.spoilerRanges(from: Int = 0, to: Int = text.length): List<IntRange> =
    marks.filter { it.mark == Mark.Spoiler && it.end > from && it.start < to }
        .map { (max(it.start, from) - from) until (min(it.end, to) - from) }

/** True when the cursor or selection touches [range] (an edge counts), so the editor shows that spoiler clear. */
fun TextRange.touches(range: IntRange): Boolean = min <= range.last + 1 && max >= range.first

/** Where one laid-out text's spoilers are: a rectangle per line of each range, the text that didn't fit left out. */
class SpoilerLayout internal constructor(private val rects: List<List<Rect>>) {
    val path: Path = path { true }
    val bounds: Rect = rects.flatten().reduceOrNull { a, b ->
        Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    } ?: Rect.Zero

    /** The spoilers whose index passes [include], as one shape. */
    fun path(include: (Int) -> Boolean): Path = Path().apply {
        rects.forEachIndexed { i, lines ->
            if (include(i)) lines.forEach { addRoundRect(RoundRect(it, CornerRadius(CORNER))) }
        }
    }

    fun hit(position: Offset): Boolean = rects.any { lines -> lines.any { it.contains(position) } }
}

fun TextLayoutResult.spoilerLayout(ranges: List<IntRange>): SpoilerLayout {
    val shown = if (lineCount == 0) 0 else getLineEnd(lineCount - 1, visibleEnd = true)
    return SpoilerLayout(
        ranges.map { range ->
            val start = range.first.coerceAtMost(shown)
            val end = (range.last + 1).coerceAtMost(shown)
            if (end <= start) return@map emptyList()
            val first = getLineForOffset(start)
            val last = getLineForOffset(end)
            (first..last).mapNotNull { line ->
                val left = if (line == first) getHorizontalPosition(start, true) else getLineLeft(line)
                val right = if (line == last) getHorizontalPosition(end, true) else getLineRight(line)
                Rect(min(left, right), getLineTop(line), max(left, right), getLineBottom(line)).takeIf { it.width >= 1f }?.inflate(OUTSET)
            }
        },
    )
}

/**
 * One entry's spoilers on screen: closed until [open], which dissolves them in a circle growing from the tapped point
 * (texts other than the tapped one open from their first spoiler). Remembered per visit, so they close again once the
 * entry leaves the screen.
 */
@Stable
class SpoilerReveal {
    var opened by mutableStateOf(false)
        private set

    /** The dissolve has finished: nothing is drawn over the text any more and the dots stop. */
    var done by mutableStateOf(false)
        private set

    private var origin: Any? = null
    private var point = Offset.Zero
    private val progress = Animatable(0f)

    fun open(origin: Any?, point: Offset, scope: CoroutineScope) {
        if (opened) return
        this.origin = origin
        this.point = point
        opened = true
        scope.launch {
            progress.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing))
            done = true
        }
    }

    /** What still covers the text drawn by [self]: all of [area] while closed, less the growing circle while opening. */
    fun hidden(self: Any?, area: SpoilerLayout): Path? {
        if (!opened) return area.path
        val p = progress.value
        if (p >= 1f) return null
        val b = area.bounds
        val center = if (origin == self) point else b.topLeft
        val reach = maxOf(
            hypot(center.x - b.left, center.y - b.top),
            hypot(center.x - b.right, center.y - b.top),
            hypot(center.x - b.left, center.y - b.bottom),
            hypot(center.x - b.right, center.y - b.bottom),
        )
        val circle = Path().apply { addOval(Rect(center, p * reach)) }
        return Path().apply { op(area.path, circle, PathOperation.Difference) }
    }

    /** The dots fade while the circle grows. */
    fun dotsAlpha(): Float = if (opened) 1f - progress.value else 1f
}

/** Seconds for the shimmer; it ticks only while [active] (and never in UI tests, which skip infinite animations). */
@Composable
fun spoilerClock(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition("spoiler").animateFloat(
        initialValue = 0f,
        targetValue = CLOCK_SECONDS,
        animationSpec = infiniteRepeatable(tween(CLOCK_SECONDS.toInt() * 1000, easing = LinearEasing)),
        label = "spoiler",
    )
}

/** The tile of dots, made once per screen density. */
class SpoilerDots internal constructor(internal val brush: ShaderBrush, internal val tile: Float, internal val dp: Float)

private var madeDots: SpoilerDots? = null

@Composable
fun rememberSpoilerDots(): SpoilerDots {
    val density = LocalDensity.current.density
    return remember(density) { madeDots?.takeIf { it.dp == density } ?: makeDots(density).also { madeDots = it } }
}

private fun makeDots(density: Float): SpoilerDots {
    val size = (TILE_DP * density).roundToInt()
    val image = ImageBitmap(size, size)
    val canvas = Canvas(image)
    val paint = Paint()
    val random = Random(SEED)
    repeat(DOTS) {
        paint.color = Color.White.copy(alpha = 0.35f + 0.65f * random.nextFloat())
        val center = Offset(random.nextFloat() * size, random.nextFloat() * size)
        canvas.drawCircle(center, (0.55f + 0.4f * random.nextFloat()) * density, paint)
    }
    return SpoilerDots(ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)), size.toFloat(), density)
}

/** Draws the content except where [hidden] says spoilers still cover it. */
fun Modifier.spoilerMask(hidden: () -> Path?): Modifier = drawWithContent {
    val path = hidden()
    if (path == null) drawContent() else clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }
}

/** The shimmering dots over [area], in a graphics layer of their own so a frame redraws only them. */
fun Modifier.spoilerDots(dots: SpoilerDots, color: Color, time: State<Float>, alpha: () -> Float, area: () -> Path?): Modifier =
    graphicsLayer().drawWithCache {
        val tint = ColorFilter.tint(color)
        onDrawBehind {
            val path = area() ?: return@onDrawBehind
            drawDots(dots, path, tint, time.value, alpha())
        }
    }

/** Dots over the whole element (a curtain over an entry); clip it first for round corners. */
fun Modifier.spoilerSurface(dots: SpoilerDots, color: Color, time: State<Float>, alpha: () -> Float = { 1f }): Modifier =
    graphicsLayer().drawWithCache {
        val tint = ColorFilter.tint(color)
        val path = Path().apply { addRect(Rect(Offset.Zero, size)) }
        onDrawBehind { drawDots(dots, path, tint, time.value, alpha()) }
    }

private fun DrawScope.drawDots(dots: SpoilerDots, path: Path, tint: ColorFilter, time: Float, alpha: Float) {
    if (alpha <= 0f || path.isEmpty) return
    val bounds = path.getBounds()
    clipPath(path) {
        LAYERS.forEachIndexed { i, layer ->
            val dx = ((layer.x + layer.vx * time) * dots.dp).mod(dots.tile)
            val dy = ((layer.y + layer.vy * time) * dots.dp).mod(dots.tile)
            val pulse = 0.5f + 0.5f * sin(2f * PI.toFloat() * (time / layer.period + i / 3f))
            translate(dx, dy) {
                drawRect(
                    dots.brush,
                    Offset(bounds.left - dx, bounds.top - dy),
                    bounds.size,
                    alpha * (0.3f + 0.7f * pulse),
                    colorFilter = tint,
                )
            }
        }
    }
}

/** One layer of dots: where it starts in the tile (dp), its drift in dp per second, how long one pulse takes. */
private class DotLayer(val x: Float, val y: Float, val vx: Float, val vy: Float, val period: Float)

private val LAYERS = listOf(
    DotLayer(0f, 0f, 6f, -3f, 2.1f),
    DotLayer(29f, 47f, -4f, 5f, 1.7f),
    DotLayer(53f, 17f, 3f, 6f, 2.6f),
)
private const val TILE_DP = 80f
private const val DOTS = 300

/** The mask reaches a pixel past the text, so no antialiased edge of a spoiler's own tint shows around it. */
private const val OUTSET = 1f
private const val SEED = 7
private const val CORNER = 4f
private const val REVEAL_MS = 550
private const val CLOCK_SECONDS = 3600f
