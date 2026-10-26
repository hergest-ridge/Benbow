package app.benbow.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.benbow.ui.theme.BenbowPalette
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * A note's look in layers over its color (ADR-047): paper, a pattern, light, motion and a picture, each named by a
 * token. Everything takes its tones from the note's color and the page's ink, so a mix of layers stays in one key;
 * a pattern's strength is capped so the words read, and in the feed the whole look is muted. Paths and dots are built
 * once per size (drawWithCache); only motion redraws, and only where it is asked for.
 */

/** One source of light: where it falls from (degrees, 0 = from the right, 90 = from below), its tone, how strong (1-3). */
@Immutable
internal data class LookLight(val angle: Float, val tone: String, val strength: Int = 2)

/** Where a picture sits: in a corner, along the bottom edge, or scattered. */
internal enum class ArtPlace { Corner, Edge, Scatter }

/**
 * A note's look (ADR-047). [paper]: dots, lines, grid, parchment; [paperPhoto] a photographed paper (`paper/`, tiled);
 * [wallpaper] an old wallpaper print (`morris/`, faded behind the words); [pattern]: circles, triangles, waves,
 * terrazzo, or "scatter" of [scatter] line pictures; [tile] a designer's repeating pattern (`hero/`, tinted); [art] a
 * picture path under `files/looks/` with [artPlace], [artTint] for line art that takes the page's ink (others keep
 * their colors); [lights]; [motion]: "drift"; [scrap] a scrapbook drawn in code (tape, scraps, stamp, stitch, torn,
 * collage; `NoteScrap.kt`).
 */
@Immutable
internal data class NoteLook(
    val paper: String? = null,
    val paperPhoto: String? = null,
    val wallpaper: String? = null,
    val pattern: String? = null,
    val scatter: List<String> = emptyList(),
    val tile: String? = null,
    val art: List<String> = emptyList(),
    val artPlace: ArtPlace = ArtPlace.Corner,
    val artTint: Boolean = false,
    val lights: List<LookLight> = emptyList(),
    val motion: String? = null,
    val scrap: String? = null,
    /** How many times «Перемешать» laid the scrapbook out anew. */
    val scrapShuffle: Int = 0,
)

/** Nothing to draw over the note's color. */
internal val NoteLook.isEmpty: Boolean get() = this == NO_LOOK

private val NO_LOOK = NoteLook()

/**
 * [content] over [look] on [base]: the layers behind, the picture under the words (the spike's cards; the app's own
 * places put [noteBackdrop] on their surfaces).
 */
@Composable
internal fun NoteBackdrop(
    look: NoteLook,
    base: Color,
    ink: Color,
    seed: Int,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    moving: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.then(noteBackdrop(look, base, ink, seed, muted, moving))) {
        if (look.art.isNotEmpty()) Art(look, ink, muted, seed)
        content()
    }
}

/**
 * [look] drawn behind whatever the modifier holds, over [base]: [muted] in lists (owner: quieter there), [moving] lets
 * motion run (the open note only, never with the system's reduced motion; it redraws without recomposing), [seed] (the
 * note's id) lays patterns and the scrapbook out the same on every device. An empty look adds nothing.
 */
@Composable
internal fun noteBackdrop(look: NoteLook?, base: Color, ink: Color, seed: Int, muted: Boolean = false, moving: Boolean = false): Modifier {
    if (look == null || look.isEmpty) return Modifier
    val dark = base.luminance() < 0.5f
    val scatter = look.scatter.mapNotNull { path -> key(path) { lookPicture("tabler/$path")?.let { rememberVectorPainter(it) } } }
    val drift: State<Float>? = if (look.motion == "drift" && moving && !reducedMotion()) {
        rememberInfiniteTransition().animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(DRIFT_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
        )
    } else {
        null
    }
    val tile = look.tile?.let { name -> key(name) { lookPicture("hero/$name") } }
    val tilePainter = tile?.let { rememberVectorPainter(it) }
    val paperPhoto = look.paperPhoto?.let { lookPhoto("paper/$it.jpg") }
    val wallpaper = look.wallpaper?.let { lookPhoto("morris/$it.jpg") }
    val strength = if (muted) MUTED else 1f
    return remember(look, base, ink, seed, strength, drift, scatter, tile, tilePainter, paperPhoto, wallpaper) {
        Modifier.drawWithCache {
            val layers = Layers(look, base, ink, dark, seed, size, scatter.size, 1.dp.toPx())
            val tiles = if (tile != null && tilePainter != null) tileBrush(tile, tilePainter, layers.tileTone) else null
            val papers = paperPhoto?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) }
            val scrapbook = look.scrap?.let { Scrapbook(it, seed + look.scrapShuffle * SHUFFLE_STEP, size, base, ink, dark, 1.dp.toPx()) }
            onDrawBehind {
                drawRect(base)
                wallpaper?.let { faded(it, dark, strength) }
                // A photographed paper's grain over the note's color: multiplied in light, softly in the dark.
                papers?.let {
                    drawRect(
                        it,
                        alpha = (if (dark) 0.55f else 0.9f) * strength,
                        blendMode = if (dark) BlendMode.Softlight else BlendMode.Multiply,
                    )
                }
                if (look.motion == "drift") layers.drift(this, drift?.value ?: STILL, strength)
                layers.lights(this, strength)
                layers.paper(this, strength)
                layers.pattern(this, strength, scatter)
                tiles?.let { drawRect(it, alpha = layers.calm(layers.tileTone, 0.55f, strength).alpha) }
                scrapbook?.draw(this, strength)
            }
        }
    }
}

/** The system asks for less motion (Android's «Remove animations», the browser's reduced motion): the drift stands still. */
@Composable
internal expect fun reducedMotion(): Boolean

/** The look's paths and points for one size, built once; [pattern]'s scattered pictures come drawn from painters. */
private class Layers(
    private val look: NoteLook,
    private val base: Color,
    private val ink: Color,
    private val dark: Boolean,
    seed: Int,
    private val box: Size,
    scatterCount: Int,
    /** One dp in pixels: steps and lines keep their size on every screen. */
    private val px: Float,
) {
    private val random = Random(seed)
    private val unit = max(box.width, box.height)

    // The page's ink faintly over the note's color: lines, dots and shapes that belong to the note.
    private val faint = lerp(base, ink, if (dark) 0.16f else 0.12f)

    /** A designer's pattern in the page's ink, a little stronger than [faint] (its lines are thin). */
    val tileTone: Color = lerp(base, ink, if (dark) 0.34f else 0.26f)

    private val dots: List<Offset> = if (look.paper == "dots") grid(DOT_STEP * px) else emptyList()
    private val shapes: List<Shape> = when (look.pattern) {
        "circles" -> List(14) { Shape(random.point(), random.nextFloat() * 0.09f + 0.02f, 0f, random.nextInt(3)) }

        "triangles" -> List(18) {
            Shape(random.point(), random.nextFloat() * 0.035f + 0.015f, random.nextFloat() * 360f, random.nextInt(3))
        }

        "terrazzo" -> List(30) { Shape(random.point(), random.nextFloat() * 0.022f + 0.008f, random.nextFloat() * 360f, random.nextInt(3)) }

        "scatter" -> List(if (scatterCount > 0) 16 else 0) {
            Shape(random.point(), random.nextFloat() * 0.03f + 0.045f, random.nextFloat() * 60f - 30f, random.nextInt(scatterCount))
        }

        else -> emptyList()
    }
    private val waves: List<Path> = if (look.pattern == "waves") wavePaths() else emptyList()
    private val blots: List<Shape> = if (look.paper ==
        "parchment"
    ) {
        List(9) { Shape(random.point(), random.nextFloat() * 0.25f + 0.1f, 0f, 0) }
    } else {
        emptyList()
    }

    private fun Random.point() = Offset(nextFloat() * box.width, nextFloat() * box.height)

    private fun grid(step: Float): List<Offset> = buildList {
        var y = step / 2
        while (y < box.height) {
            var x = step / 2
            while (x < box.width) {
                add(Offset(x, y))
                x += step
            }
            y += step
        }
    }

    private fun wavePaths(): List<Path> = List(6) { i ->
        Path().apply {
            val y0 = box.height * (i + 0.5f) / 6f
            val amp = box.height * 0.018f
            moveTo(0f, y0)
            var x = 0f
            while (x <= box.width) {
                lineTo(x, y0 + amp * sin((x / box.width) * 4f * PI.toFloat() + i))
                x += 4f * px
            }
        }
    }

    fun paper(scope: DrawScope, strength: Float) = with(scope) {
        val line = faint.copy(alpha = faint.alpha * strength)
        when (look.paper) {
            "dots" -> dots.forEach { drawCircle(line, DOT_RADIUS * px, it) }

            "lines" -> {
                var y = LINE_TOP * px
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), px)
                    y += LINE_STEP * px
                }
                // A notebook's margin, in a rose that keeps to the note's key.
                val margin = (BenbowPalette.text("red", dark) ?: ink).copy(alpha = 0.28f * strength)
                drawLine(margin, Offset(MARGIN * px, 0f), Offset(MARGIN * px, size.height), px)
            }

            "grid" -> {
                var x = GRID_STEP * px
                while (x < size.width) {
                    drawLine(line.copy(alpha = line.alpha * 0.7f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += GRID_STEP * px
                }
                var y = GRID_STEP * px
                while (y < size.height) {
                    drawLine(line.copy(alpha = line.alpha * 0.7f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += GRID_STEP * px
                }
            }

            "parchment" -> {
                val warm = BenbowPalette.text("brown", dark) ?: ink
                blots.forEach { drawCircle(warm.copy(alpha = 0.05f * strength), it.size * unit, it.at) }
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, warm.copy(alpha = 0.22f * strength)),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = unit * 0.75f,
                    ),
                )
            }
        }
    }

    fun pattern(scope: DrawScope, strength: Float, painters: List<VectorPainter>) = with(scope) {
        val tones = listOf(faint, lerp(base, ink, if (dark) 0.24f else 0.18f), tone("pink"))
        when (look.pattern) {
            "circles" -> shapes.forEach {
                drawCircle(calm(tones[it.kind], 0.55f, strength), it.size * unit, it.at, style = Stroke(width = 1.5f * px))
            }

            "triangles" -> shapes.forEach {
                rotate(it.turn, it.at) {
                    val r = it.size * unit
                    val path = Path().apply {
                        moveTo(it.at.x, it.at.y - r)
                        lineTo(it.at.x + r * 0.87f, it.at.y + r * 0.5f)
                        lineTo(it.at.x - r * 0.87f, it.at.y + r * 0.5f)
                        close()
                    }
                    drawPath(path, calm(tones[it.kind], 0.6f, strength))
                }
            }

            "terrazzo" -> shapes.forEach {
                val colors = listOf(tone("orange"), tone("teal"), tone("pink"))
                rotate(it.turn, it.at) {
                    drawOval(
                        calm(colors[it.kind], 0.55f, strength),
                        it.at - Offset(it.size * unit, it.size * unit * 0.6f),
                        Size(it.size * unit * 2, it.size * unit * 1.2f),
                    )
                }
            }

            "waves" -> waves.forEach { drawPath(it, calm(tones[1], 0.6f, strength), style = Stroke(width = 1.2f * px)) }

            "scatter" -> shapes.forEach {
                val painter = painters.getOrNull(it.kind) ?: return@forEach
                val side = it.size * unit
                translate(it.at.x - side / 2, it.at.y - side / 2) {
                    rotate(it.turn, Offset(side / 2, side / 2)) {
                        with(painter) {
                            draw(Size(side, side), alpha = calm(tones[1], 0.5f, strength).alpha, colorFilter = ColorFilter.tint(tones[1]))
                        }
                    }
                }
            }
        }
    }

    /** Lights fall from the edge at their angle across the note, like a lens's glow (owner: each note its own light). */
    fun lights(scope: DrawScope, strength: Float) = with(scope) {
        look.lights.forEach { light ->
            val a = light.angle / 180f * PI.toFloat()
            val center = Offset(size.width / 2 + cos(a) * size.width * 0.6f, size.height / 2 + sin(a) * size.height * 0.6f)
            val glow = (BenbowPalette.text(light.tone, dark) ?: ink).copy(alpha = LIGHT_ALPHA[light.strength.coerceIn(1, 3) - 1] * strength)
            drawRect(
                Brush.radialGradient(listOf(glow, Color.Transparent), center = center, radius = unit * 0.85f),
                blendMode = if (dark) BlendMode.Screen else BlendMode.SrcOver,
            )
        }
    }

    /** A slow gradient drifting across the note: [phase] 0..1. */
    fun drift(scope: DrawScope, phase: Float, strength: Float) = with(scope) {
        val a = tone("purple").copy(alpha = 0.35f * strength)
        val b = tone("teal").copy(alpha = 0.3f * strength)
        val shift = size.width * (phase - 0.5f)
        drawRect(
            Brush.linearGradient(
                listOf(a, Color.Transparent, b),
                start = Offset(shift, 0f),
                end = Offset(size.width + shift, size.height),
            ),
        )
    }

    private fun tone(token: String) = BenbowPalette.text(token, dark) ?: ink

    /**
     * [color] at [alpha] over the note, held to at most [CALM] of a visible step from the note's color, then muted by
     * [strength]: a bright tone over a far color fades more than a near one, so no pattern shouts over the words (owner:
     * the busiest looked too busy for what was written on them).
     */
    fun calm(color: Color, alpha: Float, strength: Float): Color {
        val step = sqrt(((color.red - base.red).pow(2) + (color.green - base.green).pow(2) + (color.blue - base.blue).pow(2)) / 3f)
        return color.copy(alpha = min(alpha, CALM / step.coerceAtLeast(0.001f)) * strength)
    }
}

/** A shape of a pattern: where it is, how big (a share of the note's longer side), its turn and which of its kinds. */
private class Shape(val at: Offset, val size: Float, val turn: Float, val kind: Int)

/**
 * The note's picture: stickers keep their own colors, line art takes the page's ink faintly (a watermark), in a corner,
 * along the bottom edge, or scattered.
 */
@Composable
private fun BoxScope.Art(look: NoteLook, ink: Color, muted: Boolean, seed: Int) {
    val pictures = look.art.mapNotNull { lookPicture(it) }
    if (pictures.isEmpty()) return
    val tint = if (look.artTint) ColorFilter.tint(ink.copy(alpha = if (muted) 0.08f else 0.14f)) else null
    val alpha = if (look.artTint) {
        1f
    } else if (muted) {
        0.55f
    } else {
        0.92f
    }
    when (look.artPlace) {
        // Line art large, a little off the corner like a watermark; a sticker smaller, tucked into it.
        ArtPlace.Corner -> pictures.first().let { picture ->
            Picture(
                picture,
                tint,
                alpha,
                Modifier.align(Alignment.BottomEnd).offset(x = if (look.artTint) 18.dp else 4.dp, y = if (look.artTint) 14.dp else 2.dp)
                    .fillMaxWidth(if (look.artTint) 0.74f else 0.36f).aspectRatio(picture.viewportWidth / picture.viewportHeight),
            )
        }

        ArtPlace.Edge -> Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            pictures.take(4).forEachIndexed { i, picture ->
                val shift = (i * 0.24f + 0.04f)
                Picture(picture, tint, alpha, Modifier.align(BiasAlignment(shift * 2 - 1, 1f)).size(EDGE_SIZE))
            }
        }

        ArtPlace.Scatter -> {
            val random = remember(seed) { Random(seed + 7) }
            // Under the words: the lower part of the note, and its right edge.
            val spots = remember(seed, pictures.size) {
                List(7) { i ->
                    Triple(
                        if (i <
                            5
                        ) {
                            random.nextFloat() * 2 - 1
                        } else {
                            0.92f
                        },
                        if (i <
                            5
                        ) {
                            0.2f + random.nextFloat() * 0.75f
                        } else {
                            random.nextFloat() - 0.6f
                        },
                        random.nextFloat() * 50f - 25f,
                    )
                }
            }
            spots.forEachIndexed { i, (x, y, turn) ->
                Picture(
                    pictures[i % pictures.size],
                    tint,
                    alpha * 0.75f,
                    Modifier.align(BiasAlignment(x, y)).size(SCATTER_SIZE).rotate(turn),
                )
            }
        }
    }
}

@Composable
private fun Picture(picture: ImageVector, tint: ColorFilter?, alpha: Float, modifier: Modifier) {
    Image(rememberVectorPainter(picture), null, modifier.graphicsLayer { this.alpha = alpha }, colorFilter = tint)
}

private typealias BiasAlignment = androidx.compose.ui.BiasAlignment

private const val DOT_STEP = 16f
private const val DOT_RADIUS = 1f
private const val LINE_STEP = 24f
private const val LINE_TOP = 44f
private const val MARGIN = 28f
private const val GRID_STEP = 18f
private const val MUTED = 0.5f

// The most a pattern's marks may stand out from the note's color (a share of the full black-to-white step).
private const val CALM = 0.12f
private val LIGHT_ALPHA = listOf(0.18f, 0.3f, 0.45f)
private const val DRIFT_MILLIS = 9_000

// Where the drift stands when it doesn't move (lists, reduced motion).
private const val STILL = 0.35f

// A prime, so each «Перемешать» lays the scrapbook out far from the last.
private const val SHUFFLE_STEP = 7919
private val EDGE_SIZE: Dp = 40.dp
private val SCATTER_SIZE: Dp = 26.dp

/**
 * A designer's repeating pattern as a brush: its tile drawn once in [color], at one dp per unit of its own size, then
 * repeated across the note by the GPU.
 */
private fun CacheDrawScope.tileBrush(vector: ImageVector, painter: VectorPainter, color: Color): Brush {
    val px = 1.dp.toPx()
    val tile = Size(vector.viewportWidth * px, vector.viewportHeight * px)
    val bitmap = ImageBitmap(ceil(tile.width).toInt().coerceAtLeast(1), ceil(tile.height).toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(this, layoutDirection, Canvas(bitmap), tile) {
        with(painter) { draw(tile, colorFilter = ColorFilter.tint(color)) }
    }
    return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

/** An old wallpaper print covering the note, faded so the words read: multiplied into a light color, dim in the dark. */
private fun DrawScope.faded(image: ImageBitmap, dark: Boolean, strength: Float) {
    val scale = max(size.width / image.width, size.height / image.height)
    val shown = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
    drawImage(
        image,
        dstOffset = IntOffset(((size.width - shown.width) / 2).roundToInt(), ((size.height - shown.height) / 2).roundToInt()),
        dstSize = shown,
        alpha = (if (dark) 0.2f else 0.32f) * strength,
        blendMode = if (dark) BlendMode.SrcOver else BlendMode.Multiply,
    )
}
