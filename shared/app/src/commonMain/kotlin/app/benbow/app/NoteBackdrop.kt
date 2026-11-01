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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.benbow.ui.theme.BenbowTheme
import app.benbow.ui.theme.SwatchColors
import app.benbow.ui.theme.TokenColors
import app.benbow.ui.theme.isDarkColor
import app.benbow.ui.theme.lookBudget
import app.benbow.ui.theme.screen
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
internal data class LookLight(val angle: Float, val tone: String, val strength: Int = 2, val color: Color? = null)

/** Where a picture sits: in a corner, along the bottom edge, or scattered. */
internal enum class ArtPlace { Corner, Edge, Scatter }

/**
 * A note's look (ADR-047). [paper]: dots, lines, grid, parchment; [paperPhoto] a photographed paper (`paper/`, tiled);
 * [pattern]: circles, triangles, waves,
 * terrazzo, or "scatter" of [scatter] line pictures; [tile] a designer's repeating pattern (`hero/`, tinted); [art] a
 * picture path under `files/looks/` with [artPlace], [artTint] for line art that takes the page's ink (others keep
 * their colors); [lights]; [motion]: "drift"; [scrap] a scrapbook drawn in code (tape, scraps, stamp, stitch, torn,
 * collage; `NoteScrap.kt`).
 */
@Immutable
internal data class NoteLook(
    val paper: String? = null,
    val paperPhoto: String? = null,
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
    /** Light from the note's pictures ([PictureLights]): how ("vibrant", "palette", "cover"); [pictureLook] makes it lights or [cover]. */
    val lightFrom: String? = null,
    /** A tiny copy of the note's picture, drawn large behind the words: a blur of it («Размытая обложка»). */
    val cover: ImageBitmap? = null,
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
internal fun noteBackdrop(
    look: NoteLook?,
    base: Color,
    ink: Color,
    seed: Int,
    muted: Boolean = false,
    moving: Boolean = false,
    /**
     * Where its light and motion are laid out, read as it draws: the pane around it in a wide window (PaneGlow, which
     * also runs the motion, so both move as one); null, itself.
     */
    lightFrame: (() -> LightFrame?)? = null,
    /** Where its words are on the page and how they scroll (null: a card, laid out on itself). */
    paperScroll: PaperScroll? = null,
): Modifier {
    if (look == null || look.isEmpty) return Modifier
    val dark = base.isDarkColor()
    val tokens = BenbowTheme.colors.tokens
    // A black note in a light theme, drawn outside its dark twin (the open note's page): its words are light, so its
    // look's ink is, and its budget is held to them, not to the theme's dark words that never stand on it.
    val night = dark && !BenbowTheme.colors.dark
    val note = NoteInk(
        base,
        if (night) SwatchColors.BLACK_INK else ink,
        if (night) SwatchColors.BLACK_QUIET else MaterialTheme.colorScheme.onSurfaceVariant,
        dark,
        tokens,
    )
    val scatter = look.scatter.mapNotNull { path -> key(path) { lookPicture("tabler/$path")?.let { rememberVectorPainter(it) } } }
    val drift: State<Float>? = if (look.motion == "drift" && moving && lightFrame == null && !reducedMotion()) {
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
    val strength = if (muted) MUTED else 1f
    val keys =
        arrayOf(look, base, ink, tokens, seed, strength, drift, scatter, tile, tilePainter, paperPhoto, lightFrame, paperScroll)
    return remember(*keys) {
        Modifier.drawWithCache {
            val layers = Layers(look, note, seed, size, scatter.size, 1.dp.toPx())
            val tiles = if (tile != null && tilePainter != null) tileBrush(tile, tilePainter, layers.tileTone) else null
            val papers = paperPhoto?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) }
            // The whole sheet the words are on, from where they start (under the page's bar): the scrapbook's top pieces
            // at its top, clear of the bar's buttons, the bottom ones at its end.
            val top = paperScroll?.top?.invoke() ?: 0f
            val sheet = Size(size.width, max(size.height, paperScroll?.length?.invoke() ?: 0f) - top)
            val scrapbook = look.scrap?.let {
                Scrapbook(it, seed + look.scrapShuffle * SHUFFLE_STEP, sheet, base, ink, dark, tokens, 1.dp.toPx())
            }
            onDrawBehind {
                // Paper, patterns and the scrapbook go up with the words (owner: «эффект бумаги»); light and motion
                // stay, as light does.
                val shift = paperScroll?.offset?.invoke() ?: 0f
                drawRect(base)
                // A photographed paper's grain over the note's color: multiplied in light, softly in the dark.
                papers?.let {
                    translate(0f, -shift) {
                        drawRect(
                            it,
                            topLeft = Offset(0f, shift),
                            size = size,
                            alpha = (if (dark) 0.55f else 0.9f) * strength,
                            blendMode = if (dark) BlendMode.Softlight else BlendMode.Multiply,
                        )
                    }
                }
                val frame = lightFrame?.invoke()
                // The note's picture blurred behind the words: a tiny copy drawn large, as faint as the words allow.
                look.cover?.let { cover ->
                    drawImage(
                        cover,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        alpha = note.budget(base, COVER_ALPHA * strength).coerceAtMost(COVER_ALPHA * strength),
                        filterQuality = FilterQuality.Low,
                    )
                }
                if (look.motion == "drift") drawDrift(frame?.phase ?: drift?.value ?: STILL, note, strength, frame)
                drawLights(look.lights, note, strength, frame)
                layers.paper(this, strength, shift)
                layers.pattern(this, strength, scatter, shift)
                tiles?.let {
                    translate(0f, -shift) {
                        drawRect(it, topLeft = Offset(0f, shift), size = size, alpha = layers.calm(layers.tileTone, 0.55f, strength).alpha)
                    }
                }
                scrapbook?.let { translate(0f, top - shift) { it.draw(this, strength) } }
            }
        }
    }
}

/**
 * Where a note's words are on its page, read as it draws: [top] where they start (under the page's bar), [offset] how
 * far they have scrolled (0 with «Статичный фон»), [length] how tall the whole sheet is from the page's top (where they
 * start, their height and how far they can scroll; 0 when it stays).
 */
internal class PaperScroll(val offset: () -> Float, val length: () -> Float, val top: () -> Float = { 0f })

/** The system asks for less motion (Android's «Remove animations», the browser's reduced motion): the drift stands still. */
@Composable
internal expect fun reducedMotion(): Boolean

/** The look's paths and points for one size, built once; [pattern]'s scattered pictures come drawn from painters. */
private class Layers(
    private val look: NoteLook,
    private val note: NoteInk,
    seed: Int,
    private val box: Size,
    scatterCount: Int,
    /** One dp in pixels: steps and lines keep their size on every screen. */
    private val px: Float,
) {
    private val base = note.base
    private val ink = note.main
    private val dark = note.dark
    private val tokens = note.tokens
    private val random = Random(seed)
    private val unit = max(box.width, box.height)

    // The page's ink faintly over the note's color: lines, dots and shapes that belong to the note.
    private val faint = lerp(base, ink, if (dark) 0.16f else 0.12f)

    /** A designer's pattern in the page's ink, a little stronger than [faint] (its lines are thin). */
    val tileTone: Color = lerp(base, ink, if (dark) 0.34f else 0.26f)

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

    /** The paper's marks, gone up by [shift] with the words: only the rows in view are drawn. */
    fun paper(scope: DrawScope, strength: Float, shift: Float = 0f) = with(scope) {
        val line = faint.copy(alpha = faint.alpha * strength)
        when (look.paper) {
            "dots" -> {
                val step = DOT_STEP * px
                var y = step / 2 - shift % step
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) {
                        drawCircle(line, DOT_RADIUS * px, Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }

            "lines" -> {
                val step = LINE_STEP * px
                // The first line a little way down the sheet, then one every step.
                var y = LINE_TOP * px - shift
                if (y < 0f) y += ceil(-y / step) * step
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), px)
                    y += step
                }
                // A notebook's margin, in a rose that keeps to the note's key.
                val margin = (tokens.mark("red", dark) ?: ink).copy(alpha = 0.28f * strength)
                drawLine(margin, Offset(MARGIN * px, 0f), Offset(MARGIN * px, size.height), px)
            }

            "grid" -> {
                val step = GRID_STEP * px
                var x = step
                while (x < size.width) {
                    drawLine(line.copy(alpha = line.alpha * 0.7f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = step - shift % step
                while (y < size.height) {
                    drawLine(line.copy(alpha = line.alpha * 0.7f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
            }

            "parchment" -> {
                val warm = tokens.mark("brown", dark) ?: ink
                banded(shift) { blots.forEach { drawCircle(warm.copy(alpha = 0.05f * strength), it.size * unit, it.at) } }
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

    /**
     * [draw] laid out over one screen of the note, gone up by [shift] and repeated below as it goes: random shapes for
     * a sheet of any length, a screen's worth at a time.
     */
    private fun DrawScope.banded(shift: Float, draw: DrawScope.() -> Unit) {
        val band = box.height
        if (shift == 0f || band <= 0f) return draw()
        val into = shift % band
        translate(0f, -into, draw)
        translate(0f, band - into, draw)
    }

    fun pattern(scope: DrawScope, strength: Float, painters: List<VectorPainter>, shift: Float = 0f) = with(scope) {
        banded(shift) { shapes(strength, painters) }
    }

    private fun DrawScope.shapes(strength: Float, painters: List<VectorPainter>) {
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

    private fun tone(token: String) = tokens.mark(token, dark) ?: ink

    /**
     * [color] at [alpha] over the note, held to at most [CALM] of a visible step from the note's color, then muted by
     * [strength]: a bright tone over a far color fades more than a near one, so no pattern shouts over the words (owner:
     * the busiest looked too busy for what was written on them).
     */
    private val calmed = HashMap<Triple<Color, Float, Float>, Color>()

    fun calm(color: Color, alpha: Float, strength: Float): Color = calmed.getOrPut(Triple(color, alpha, strength)) {
        val step = sqrt(((color.red - base.red).pow(2) + (color.green - base.green).pow(2) + (color.blue - base.blue).pow(2)) / 3f)
        color.copy(alpha = note.budget(color, min(alpha, CALM / step.coerceAtLeast(0.001f)) * strength))
    }
}

/**
 * A note's page and the words on it, which its look must leave readable (ADR-057): every layer is held to [lookBudget]
 * over [base], so the words keep 7:1 and the secondary ones 4.5:1 however strong the look.
 */
internal data class NoteInk(val base: Color, val main: Color, val secondary: Color, val dark: Boolean, val tokens: TokenColors) {
    /** [wanted] alpha of [layer], or less where the words would stop reading; [screened] for a dark note's lights. */
    fun budget(layer: Color, wanted: Float, screened: Boolean = false): Float = lookBudget(wanted, main, secondary) { alpha ->
        if (screened) screen(layer, alpha, base) else layer.copy(alpha = alpha).compositeOver(base)
    }
}

/** Where lights are laid out: across [area] (the pane in a wide window), the note's own top left at [at] within it. */
internal class LightFrame(val area: Size, val at: Offset, val phase: Float? = null)

/**
 * Lights fall from the edge at their angle across the note, like a lens's glow (owner: each note its own light). With a
 * [frame] they fall across the pane around the note instead, drawn here where they cross the note: the pane draws
 * the same over its margins (PaneGlow), so the light runs on unbroken over the note's edges.
 */
internal fun DrawScope.drawLights(lights: List<LookLight>, note: NoteInk, strength: Float, frame: LightFrame? = null) {
    val area = frame?.area?.takeIf { it.width > 0f && it.height > 0f }
    val box = area ?: size
    val at = frame?.takeIf { area != null }?.at ?: Offset.Zero
    val unit = max(box.width, box.height)
    lights.forEach { light ->
        val a = light.angle / 180f * PI.toFloat()
        val center = Offset(box.width / 2 + cos(a) * box.width * 0.6f, box.height / 2 + sin(a) * box.height * 0.6f) - at
        val tone = light.color ?: note.tokens.mark(light.tone, note.dark) ?: note.main
        val glow = tone.copy(alpha = note.budget(tone, LIGHT_ALPHA[light.strength.coerceIn(1, 3) - 1] * strength, screened = note.dark))
        drawRect(
            Brush.radialGradient(listOf(glow, Color.Transparent), center = center, radius = unit * 0.85f),
            blendMode = if (note.dark) BlendMode.Screen else BlendMode.SrcOver,
        )
    }
}

/**
 * A slow gradient drifting across the note, [phase] 0..1; with a [frame], across the pane around it, at the pane's phase
 * (PaneGlow), so the note and its margins move as one.
 */
internal fun DrawScope.drawDrift(phase: Float, note: NoteInk, strength: Float, frame: LightFrame? = null) {
    val area = frame?.area?.takeIf { it.width > 0f && it.height > 0f }
    val box = area ?: size
    val at = frame?.takeIf { area != null }?.at ?: Offset.Zero
    val a = (note.tokens.mark("purple", note.dark) ?: note.main).let { it.copy(alpha = note.budget(it, 0.35f * strength)) }
    val b = (note.tokens.mark("teal", note.dark) ?: note.main).let { it.copy(alpha = note.budget(it, 0.3f * strength)) }
    val shift = box.width * (phase - 0.5f)
    drawRect(
        Brush.linearGradient(
            listOf(a, Color.Transparent, b),
            start = Offset(shift, 0f) - at,
            end =
            Offset(box.width + shift, box.height) - at,
        ),
    )
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
internal const val DRIFT_MILLIS = 9_000

// Where the drift stands when it doesn't move (lists, reduced motion).
internal const val STILL = 0.35f

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

/** How strongly a note's picture shows blurred behind its words, at most. */
private const val COVER_ALPHA = 0.32f
