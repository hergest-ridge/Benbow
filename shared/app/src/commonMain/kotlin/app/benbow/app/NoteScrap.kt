package app.benbow.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.benbow.ui.theme.BenbowPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * The scrapbook layer of a note's look (ADR-047, 7.20 L0 spike): washi tape, torn paper scraps, a stamp with its
 * postmark, stitches and a torn-off edge, all drawn in code. The note's seed lays them out, so each note has its own
 * layout, the same on every device, with no picture files to ship or credit. Tones come from the palette, softened
 * toward the note's color, so tape and paper stay in its key. [TapedPicture] holds a picture the way a scrapbook does.
 */

/**
 * A scrapbook [kind] for one size, laid out once (drawWithCache) and drawn behind the words: "tape" on two corners,
 * "scraps" of torn paper, a "stamp", "stitch" around the edge, a "torn" top, or a "collage" of them.
 */
internal class Scrapbook(
    kind: String,
    seed: Int,
    private val box: Size,
    private val base: Color,
    private val ink: Color,
    private val dark: Boolean,
    /** One dp in pixels: tape and paper keep their size on every screen. */
    private val px: Float,
) {
    private val random = Random(seed)
    private val tapes = mutableListOf<Tape>()
    private val scraps = mutableListOf<Scrap>()
    private var stamp: Stamp? = null
    private var stitch: PathEffect? = null
    private var torn: Torn? = null
    private val thread = tone(THREADS[random.nextInt(THREADS.size)])

    // Torn paper shows its white core along the tear.
    private val rim = if (dark) lerp(base, Color.White, 0.22f).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.85f)

    init {
        when (kind) {
            "tape" -> CORNER_PAIRS[random.nextInt(CORNER_PAIRS.size)].forEach { tapes += cornerTape(it) }

            "scraps" -> {
                scraps += bigScrap()
                val strip = scrap(
                    Offset(box.width * 0.8f, box.height * 0.14f),
                    random.sign() * (7f + random.nextFloat() * 6f),
                    box.width * 0.46f,
                    28 * px,
                    KRAFT,
                    tornBottom = true,
                )
                scraps += strip
                tapes += random.tape(
                    strip.at + rotated(Offset(-strip.w / 2 + 8 * px, 0f), strip.angle),
                    strip.angle + 78f,
                    34 * px,
                    14 * px,
                    base,
                    dark,
                    px,
                )
            }

            "stamp" -> stamp = stamp()

            "stitch" -> stitch = dashes()

            "torn" -> {
                torn = torn()
                tapes += cornerTape(TOP)
            }

            "collage" -> {
                stitch = dashes()
                val big = bigScrap()
                scraps += big
                tapes += random.tape(
                    big.at + rotated(Offset(-big.w / 2 + 6 * px, -big.h / 2 + 4 * px), big.angle),
                    big.angle - 40f + random.nextFloat() * 10f,
                    56 * px,
                    17 * px,
                    base,
                    dark,
                    px,
                )
                stamp = stamp()
            }
        }
    }

    fun draw(scope: DrawScope, strength: Float) = with(scope) {
        torn?.let {
            drawPath(it.under, lerp(base, Color.Black, if (dark) 0.45f else 0.12f).copy(alpha = strength))
            translate(0f, px) { drawPath(it.rim, rim.copy(alpha = rim.alpha * strength), style = tear(2.4f)) }
        }
        stitch?.let { dashes ->
            val inset = 7 * px
            drawRoundRect(
                thread.copy(alpha = 0.55f * strength),
                Offset(inset, inset),
                Size(size.width - 2 * inset, size.height - 2 * inset),
                CornerRadius(7 * px),
                style = Stroke(1.5f * px, cap = StrokeCap.Round, pathEffect = dashes),
            )
        }
        scraps.forEach { drawScrap(it, strength) }
        stamp?.let { drawStamp(it, strength) }
        tapes.forEach { drawTape(it, dark, strength) }
    }

    private fun tone(token: String) = BenbowPalette.text(token, dark) ?: ink

    private fun tear(width: Float) = Stroke(width * px, cap = StrokeCap.Round, join = StrokeJoin.Round)

    private fun dashes() = PathEffect.dashPathEffect(floatArrayOf(5 * px, 3.5f * px))

    /** Tape across a corner (0 top left, clockwise to 3) or over the top edge ([TOP]), half of it off the note. */
    private fun cornerTape(corner: Int): Tape {
        val inset = 16 * px
        val length = (62 + random.nextFloat() * 16) * px
        val thickness = (17 + random.nextFloat() * 5) * px
        val tilt = random.nextFloat() * 12f - 6f
        val (at, angle) = when (corner) {
            0 -> Offset(inset, inset) to -45f + tilt
            1 -> Offset(box.width - inset, inset) to 45f + tilt
            2 -> Offset(box.width - inset, box.height - inset) to -45f + tilt
            3 -> Offset(inset, box.height - inset) to 45f + tilt
            else -> Offset(box.width * (0.35f + random.nextFloat() * 0.3f), 3 * px) to tilt * 0.8f
        }
        return random.tape(at, angle, length, thickness, base, dark, px)
    }

    /** A large scrap under the lower words: graph, lined or cream paper, its top torn. */
    private fun bigScrap(): Scrap = scrap(
        Offset(box.width * 0.56f, box.height * 0.8f),
        random.sign() * (1.5f + random.nextFloat() * 3f),
        box.width * 0.8f,
        box.height * 0.42f,
        listOf(GRAPH, LINED, CREAM)[random.nextInt(3)],
        tornBottom = false,
    )

    private fun scrap(at: Offset, angle: Float, w: Float, h: Float, kind: Int, tornBottom: Boolean): Scrap {
        val top = random.tornEdge(-w / 2, w / 2, -h / 2, px)
        val bottom = if (tornBottom) {
            random.tornEdge(-w / 2, w / 2, h / 2, px).asReversed()
        } else {
            listOf(
                Offset(w / 2, h / 2),
                Offset(
                    -w / 2,
                    h / 2,
                ),
            )
        }
        val outline = Path().apply {
            moveTo(top.first().x, top.first().y)
            (top.drop(1) + bottom).forEach { lineTo(it.x, it.y) }
            close()
        }
        val fibers = if (kind == KRAFT) {
            List(28) {
                val a = Offset((random.nextFloat() - 0.5f) * w, (random.nextFloat() - 0.5f) * h)
                a to a + Offset((random.nextFloat() - 0.5f) * 10 * px, (random.nextFloat() - 0.5f) * 3 * px)
            }
        } else {
            emptyList()
        }
        return Scrap(at, angle, w, h, kind, outline, listOfNotNull(polyline(top), if (tornBottom) polyline(bottom) else null), fibers)
    }

    private fun stamp(): Stamp {
        val w = 40 * px
        val h = 48 * px
        val at = Offset(box.width - 30 * px, 34 * px)
        // Beside the stamp at the title's height, so it crosses as few words as it can.
        val mark = at + Offset(-23 * px, -4 * px)
        // A postmark's lines run on to the right, over the stamp.
        val waves = List(4) { i ->
            Path().apply {
                val y0 = mark.y - 6 * px + i * 4 * px
                val x0 = mark.x + 15 * px
                moveTo(x0, y0)
                var x = 0f
                while (x <= 38 * px) {
                    lineTo(x0 + x, y0 + sin(x / (6 * px)) * 1.3f * px)
                    x += 2 * px
                }
            }
        }
        return Stamp(
            at,
            random.sign() * (3f + random.nextFloat() * 5f),
            w,
            h,
            stampOutline(w, h),
            tone(STAMP_TONES[random.nextInt(STAMP_TONES.size)]),
            mark,
            random.nextFloat() * 24f - 12f,
            waves,
        )
    }

    /** A stamp's paper: a rectangle with its perforation bitten out along every edge. */
    private fun stampOutline(w: Float, h: Float): Path {
        val paper = Path().apply { addRect(Rect(-w / 2, -h / 2, w / 2, h / 2)) }
        val holes = Path()
        val step = 6 * px
        val across = (w / step).roundToInt()
        for (i in 0..across) {
            val x = -w / 2 + w * i / across
            holes.addOval(Rect(Offset(x, -h / 2), 2 * px))
            holes.addOval(Rect(Offset(x, h / 2), 2 * px))
        }
        val down = (h / step).roundToInt()
        for (i in 0..down) {
            val y = -h / 2 + h * i / down
            holes.addOval(Rect(Offset(-w / 2, y), 2 * px))
            holes.addOval(Rect(Offset(w / 2, y), 2 * px))
        }
        return Path.combine(PathOperation.Difference, paper, holes)
    }

    /** The note's top torn off: the page underneath shows above a ragged line. */
    private fun torn(): Torn {
        val edge = random.tornEdge(0f, box.width, 8 * px, px)
        val under = Path().apply {
            moveTo(0f, 0f)
            lineTo(box.width, 0f)
            edge.asReversed().forEach { lineTo(it.x, it.y) }
            close()
        }
        return Torn(under, polyline(edge))
    }

    private fun DrawScope.drawScrap(scrap: Scrap, strength: Float) = translate(scrap.at.x, scrap.at.y) {
        rotate(scrap.angle, Offset.Zero) {
            translate(0.8f * px, 1.4f * px) { drawPath(scrap.outline, Color.Black.copy(alpha = (if (dark) 0.35f else 0.12f) * strength)) }
            drawPath(scrap.outline, paperOf(scrap.kind).copy(alpha = 0.96f * strength))
            clipPath(scrap.outline) { scrapLines(scrap, strength) }
            scrap.rims.forEach { drawPath(it, rim.copy(alpha = rim.alpha * strength), style = tear(2f)) }
        }
    }

    private fun DrawScope.scrapLines(scrap: Scrap, strength: Float) {
        val left = -scrap.w / 2
        val right = scrap.w / 2
        val top = -scrap.h / 2 - 4 * px
        val bottom = scrap.h / 2
        val blue = lerp(Color(0xFF5E8FC0), base, if (dark) 0.45f else 0f)
        when (scrap.kind) {
            GRAPH -> {
                val line = blue.copy(alpha = 0.35f * strength)
                var x = left + 6 * px
                while (x < right) {
                    drawLine(line, Offset(x, top), Offset(x, bottom), 0.8f * px)
                    x += 7 * px
                }
                var y = top + 6 * px
                while (y < bottom) {
                    drawLine(line, Offset(left, y), Offset(right, y), 0.8f * px)
                    y += 7 * px
                }
            }

            LINED -> {
                var y = top + 14 * px
                while (y < bottom) {
                    drawLine(blue.copy(alpha = 0.4f * strength), Offset(left, y), Offset(right, y), 0.9f * px)
                    y += 9 * px
                }
                val margin = lerp(Color(0xFFD9534F), base, if (dark) 0.4f else 0f).copy(alpha = 0.45f * strength)
                drawLine(margin, Offset(left + 12 * px, top), Offset(left + 12 * px, bottom), px)
            }

            KRAFT -> {
                val fiber = lerp(Color(0xFF8B6A43), base, if (dark) 0.5f else 0f).copy(alpha = 0.28f * strength)
                scrap.fibers.forEach { (a, b) -> drawLine(fiber, a, b, 0.8f * px) }
            }
        }
    }

    private fun paperOf(kind: Int): Color = if (dark) lerp(PAPERS[kind], base, 0.78f) else lerp(PAPERS[kind], base, 0.12f)

    private fun DrawScope.drawStamp(stamp: Stamp, strength: Float) {
        translate(stamp.at.x, stamp.at.y) {
            rotate(stamp.angle, Offset.Zero) {
                translate(0.6f * px, 1.2f * px) {
                    drawPath(stamp.outline, Color.Black.copy(alpha = (if (dark) 0.35f else 0.14f) * strength))
                }
                drawPath(stamp.outline, (if (dark) lerp(Color(0xFFF2EEE6), base, 0.5f) else Color(0xFFFFFEF9)).copy(alpha = strength))
                val inner = Rect(-stamp.w / 2 + 4 * px, -stamp.h / 2 + 4 * px, stamp.w / 2 - 4 * px, stamp.h / 2 - 4 * px)
                clipRect(inner.left, inner.top, inner.right, inner.bottom) { stampPicture(inner, stamp.tone, strength) }
            }
        }
        val mark = ink.copy(alpha = (if (dark) 0.4f else 0.38f) * strength)
        rotate(stamp.markAngle, stamp.mark) {
            drawCircle(mark, 13 * px, stamp.mark, style = Stroke(1.3f * px))
            drawCircle(mark, 9.5f * px, stamp.mark, style = Stroke(0.8f * px))
            drawLine(mark, stamp.mark + Offset(-5 * px, -1.6f * px), stamp.mark + Offset(5 * px, -1.6f * px), px)
            drawLine(mark, stamp.mark + Offset(-4 * px, 1.6f * px), stamp.mark + Offset(4 * px, 1.6f * px), px)
            stamp.waves.forEach { drawPath(it, mark, style = Stroke(1.1f * px)) }
        }
    }

    /** A little landscape in the stamp's tone: sky, sun, hills, and the bars of its value. */
    private fun DrawScope.stampPicture(inner: Rect, tone: Color, strength: Float) {
        fun soft(amount: Float) = if (dark) lerp(tone, base, amount * 0.6f) else lerp(tone, Color.White, amount)
        drawRect(
            Brush.verticalGradient(listOf(soft(0.65f), soft(0.3f)), inner.top, inner.bottom),
            inner.topLeft,
            inner.size,
            alpha = strength,
        )
        drawCircle(
            if (dark) Color(0xFFE8DDB0) else Color(0xFFFFF4C8),
            inner.width * 0.15f,
            Offset(inner.right - inner.width * 0.3f, inner.top + inner.height * 0.28f),
            alpha = strength,
        )
        val hills = Path().apply {
            moveTo(inner.left, inner.bottom)
            lineTo(inner.left + inner.width * 0.38f, inner.top + inner.height * 0.5f)
            lineTo(inner.left + inner.width * 0.62f, inner.top + inner.height * 0.72f)
            lineTo(inner.left + inner.width * 0.8f, inner.top + inner.height * 0.56f)
            lineTo(inner.right, inner.top + inner.height * 0.7f)
            lineTo(inner.right, inner.bottom)
            close()
        }
        drawPath(hills, lerp(tone, Color.Black, 0.2f).copy(alpha = (if (dark) 0.7f else 0.85f) * strength))
        drawRect(Color.White.copy(alpha = 0.85f * strength), Offset(inner.left + 3 * px, inner.top + 3 * px), Size(9 * px, 2.4f * px))
        drawRect(Color.White.copy(alpha = 0.7f * strength), Offset(inner.left + 3 * px, inner.top + 7 * px), Size(6 * px, 1.6f * px))
    }
}

/**
 * A picture held the way a scrapbook holds one ([style]): "tape" on its two top corners, a "strip" of tape over the top,
 * a "polaroid" frame with a strip, or the album's photo "corners". [seed] turns it a little and picks the tape.
 */
@Composable
internal fun TapedPicture(path: String, style: String, seed: Int, base: Color, modifier: Modifier = Modifier) {
    val image = lookPhoto(path)
    val dark = base.luminance() < 0.5f
    val turn = remember(seed, style) {
        val random = Random(seed)
        when (style) {
            "corners" -> 0f
            "polaroid" -> random.nextFloat() * 6f - 3f
            else -> random.nextFloat() * 4f - 2f
        }
    }
    Box(
        modifier.rotate(turn).drawWithCache {
            val px = 1.dp.toPx()
            val random = Random(seed + 1)
            val tapes = when (style) {
                "tape" -> listOf(
                    random.tape(Offset(2 * px, 2 * px), -42f + random.nextFloat() * 8f, 50 * px, 16 * px, base, dark, px),
                    random.tape(Offset(size.width - 2 * px, 2 * px), 42f - random.nextFloat() * 8f, 50 * px, 16 * px, base, dark, px),
                )

                "strip", "polaroid" -> listOf(
                    random.tape(
                        Offset(size.width * (0.4f + random.nextFloat() * 0.2f), 0f),
                        random.nextFloat() * 10f - 5f,
                        64 * px,
                        18 * px,
                        base,
                        dark,
                        px,
                    ),
                )

                else -> emptyList()
            }
            val corners = if (style == "corners") photoCorners(size, px) else emptyList()
            val pocket = if (dark) Color(0xFF15120F).copy(alpha = 0.92f) else Color(0xFF2B2622).copy(alpha = 0.9f)
            onDrawWithContent {
                // A soft shadow under the print, then the print, then what holds it.
                for (i in 1..3) drawRect(Color.Black.copy(alpha = if (dark) 0.12f else 0.045f), Offset(0f, i * 0.9f * px), size)
                drawContent()
                corners.forEach {
                    drawPath(it, pocket)
                    drawPath(it, Color.White.copy(alpha = 0.16f), style = Stroke(0.8f * px))
                }
                tapes.forEach { drawTape(it, dark, 1f) }
            }
        }.then(
            if (style == "polaroid") {
                Modifier.background(
                    if (dark) Color(0xFFDCD8D0) else Color(0xFFFDFCF8),
                ).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 20.dp)
            } else {
                Modifier
            },
        ),
    ) {
        val ratio = image?.let { it.width.toFloat() / it.height } ?: 1.3f
        if (image != null) {
            Image(image, null, Modifier.fillMaxWidth().aspectRatio(ratio), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(ratio))
        }
    }
}

/** A piece of washi tape: [outline] around its center with hand-torn ends, turned by [angle] degrees. */
private class Tape(
    val at: Offset,
    val angle: Float,
    val outline: Path,
    val length: Float,
    val thickness: Float,
    val color: Color,
    val accent: Color,
    val style: Int,
)

/** A scrap of paper around its center: its outline, the torn edges' rims, and a kraft paper's fibers. */
private class Scrap(
    val at: Offset,
    val angle: Float,
    val w: Float,
    val h: Float,
    val kind: Int,
    val outline: Path,
    val rims: List<Path>,
    val fibers: List<Pair<Offset, Offset>>,
)

private class Stamp(
    val at: Offset,
    val angle: Float,
    val w: Float,
    val h: Float,
    val outline: Path,
    val tone: Color,
    val mark: Offset,
    val markAngle: Float,
    val waves: List<Path>,
)

private class Torn(val under: Path, val rim: Path)

/** Tape in one of the palette's tones, pale over a light note and deep over a dark one, with one of four prints. */
private fun Random.tape(at: Offset, angle: Float, length: Float, thickness: Float, base: Color, dark: Boolean, px: Float): Tape {
    val tone = BenbowPalette.text(TAPE_TONES[nextInt(TAPE_TONES.size)], dark) ?: Color.Gray
    val color = if (dark) lerp(tone, base, 0.45f) else lerp(tone, Color.White, 0.55f)
    val accent = when {
        dark -> lerp(tone, Color.White, 0.25f)
        nextBoolean() -> Color.White
        else -> lerp(tone, Color.White, 0.1f)
    }
    return Tape(at, angle, tapeOutline(length, thickness, px), length, thickness, color, accent, nextInt(4))
}

/** A strip with both ends torn by hand: small teeth in and out. */
private fun Random.tapeOutline(length: Float, thickness: Float, px: Float): Path = Path().apply {
    val l = length / 2
    val t = thickness / 2
    val teeth = (thickness / (2.4f * px)).toInt().coerceAtLeast(3)
    moveTo(-l, -t)
    lineTo(l, -t)
    for (i in 1..teeth) lineTo(l + tooth(i, px), -t + thickness * i / teeth)
    lineTo(-l, t)
    for (i in 1..teeth) lineTo(-l + tooth(i, px), t - thickness * i / teeth)
    close()
}

private fun Random.tooth(i: Int, px: Float) = (if (i % 2 == 0) 1f else -1f) * (0.5f + nextFloat()) * 1.3f * px

private fun DrawScope.drawTape(tape: Tape, dark: Boolean, strength: Float) {
    val px = 1.dp.toPx()
    translate(tape.at.x, tape.at.y) {
        rotate(tape.angle, Offset.Zero) {
            translate(0f, px) { drawPath(tape.outline, Color.Black.copy(alpha = (if (dark) 0.3f else 0.1f) * strength)) }
            drawPath(tape.outline, tape.color.copy(alpha = 0.86f * strength))
            clipPath(tape.outline) { tapePrint(tape, strength, px) }
        }
    }
}

/** The tape's print: stripes, dots, a fine grid, or two lines along its edges. */
private fun DrawScope.tapePrint(tape: Tape, strength: Float, px: Float) {
    val l = tape.length / 2 + 3 * px
    val t = tape.thickness / 2
    val accent = tape.accent.copy(alpha = 0.6f * strength)
    when (tape.style) {
        0 -> {
            var x = -l - tape.thickness
            while (x < l) {
                drawLine(accent, Offset(x, t), Offset(x + tape.thickness, -t), 2.6f * px)
                x += 7 * px
            }
        }

        1 -> {
            var y = -t + 3.5f * px
            var row = 0
            while (y < t) {
                var x = -l + if (row % 2 == 0) 0f else 3.5f * px
                while (x < l) {
                    drawCircle(accent, 1.5f * px, Offset(x, y))
                    x += 7 * px
                }
                y += 6 * px
                row++
            }
        }

        2 -> {
            val line = accent.copy(alpha = accent.alpha * 0.7f)
            var x = -l
            while (x < l) {
                drawLine(line, Offset(x, -t), Offset(x, t), 0.8f * px)
                x += 5 * px
            }
            var y = -t + 2.5f * px
            while (y < t) {
                drawLine(line, Offset(-l, y), Offset(l, y), 0.8f * px)
                y += 5 * px
            }
        }

        else -> {
            drawLine(accent, Offset(-l, -t + 3 * px), Offset(l, -t + 3 * px), px)
            drawLine(accent, Offset(-l, t - 3 * px), Offset(l, t - 3 * px), px)
        }
    }
}

/** A torn line from [x0] to [x1] around [y]: a slow wave with small jags, the way paper tears. */
private fun Random.tornEdge(x0: Float, x1: Float, y: Float, px: Float): List<Offset> {
    val phase = nextFloat() * 6f
    val period = (40 + nextFloat() * 40) * px
    return buildList {
        var x = x0
        while (true) {
            add(Offset(x, y + sin(x / period * 2 * PI.toFloat() + phase) * 1.8f * px + (nextFloat() - 0.5f) * 2.2f * px))
            if (x >= x1) break
            x = min(x + (2f + nextFloat() * 2f) * px, x1)
        }
    }
}

/** The album's black paper corners a print slips into. */
private fun photoCorners(box: Size, px: Float): List<Path> {
    val leg = 15 * px
    val out = 2 * px
    fun corner(x: Float, y: Float, dx: Float, dy: Float) = Path().apply {
        moveTo(x - dx * out, y - dy * out)
        lineTo(x + dx * leg, y - dy * out)
        lineTo(x - dx * out, y + dy * leg)
        close()
    }
    return listOf(
        corner(0f, 0f, 1f, 1f),
        corner(box.width, 0f, -1f, 1f),
        corner(box.width, box.height, -1f, -1f),
        corner(0f, box.height, 1f, -1f),
    )
}

private fun polyline(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
}

private fun rotated(v: Offset, degrees: Float): Offset {
    val a = degrees / 180f * PI.toFloat()
    return Offset(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
}

private fun Random.sign() = if (nextBoolean()) 1f else -1f

private const val GRAPH = 0
private const val LINED = 1
private const val KRAFT = 2
private const val CREAM = 3
private val PAPERS = listOf(Color(0xFFFAFAF5), Color(0xFFFFFDF4), Color(0xFFD8BB90), Color(0xFFF3E7CF))
private const val TOP = 4
private val CORNER_PAIRS = listOf(listOf(0, 1), listOf(0, 2), listOf(TOP, 2), listOf(1, 3))
private val TAPE_TONES = listOf("pink", "yellow", "teal", "blue", "orange", "purple", "green")
private val STAMP_TONES = listOf("teal", "blue", "red", "green", "purple")
private val THREADS = listOf("pink", "red", "teal", "blue")
