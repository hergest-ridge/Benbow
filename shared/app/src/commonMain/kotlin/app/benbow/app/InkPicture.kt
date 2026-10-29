package app.benbow.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import app.benbow.client.Media
import app.benbow.client.readAllText
import app.benbow.core.ink.InkDrawing
import app.benbow.core.ink.InkGeometry
import app.benbow.core.ink.InkStroke
import app.benbow.core.ink.InkSvg
import app.benbow.core.ink.InkTool
import app.benbow.core.sync.AttachmentState
import app.benbow.ui.theme.BenbowColors
import app.benbow.ui.theme.BenbowPalette
import app.benbow.ui.theme.BenbowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A palette token's color in this theme; [InkDrawing.INK] is the text color. */
internal fun inkColor(token: String, onSurface: Color, theme: BenbowColors): Color =
    if (token == InkDrawing.INK) onSurface else theme.tokens.text(token, theme.dark) ?: onSurface

/** The colors an SVG carries for viewers outside the app: the light theme's (ink is near-black). */
internal fun svgColor(token: String): String {
    val argb = if (token ==
        InkDrawing.INK
    ) {
        0xFF1F1B16.toInt()
    } else {
        (BenbowPalette.print.text(token, dark = false) ?: Color(0xFF1F1B16)).toArgb()
    }
    return "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
}

/** A stroke ready to draw: its shape in canvas units, built once. */
internal class InkShape(val stroke: InkStroke) {
    val path: Path = Path().apply {
        val xy = if (stroke.tool == InkTool.Pen) InkGeometry.outline(stroke) else InkGeometry.centerline(stroke)
        for (i in 0 until xy.size / 2) if (i == 0) moveTo(xy[0], xy[1]) else lineTo(xy[i * 2], xy[i * 2 + 1])
        if (stroke.tool == InkTool.Pen) close()
    }
}

/**
 * Shapes kept per stroke object while drawing: a finished stroke is shared by every later version of the drawing (and
 * the undo history), so ending a line builds one outline, not all of them again.
 */
internal class InkShapes {
    // By identity (a stroke object is reused as is); strokes are mostly appended, so the same index usually matches.
    private var cache: List<Pair<InkStroke, InkShape>> = emptyList()

    fun of(strokes: List<InkStroke>): List<InkShape> {
        val previous = cache
        val next = strokes.mapIndexed { i, stroke ->
            val known = previous.getOrNull(i)?.takeIf { it.first === stroke } ?: previous.firstOrNull { it.first === stroke }
            stroke to (known?.second ?: InkShape(stroke))
        }
        cache = next
        return next.map { it.second }
    }
}

/** Draws [shapes] in canvas units scaled to this size (the canvas is [canvasWidth] units across). */
internal fun DrawScope.drawInk(shapes: List<InkShape>, canvasWidth: Int, onSurface: Color, theme: BenbowColors) {
    val scale = size.width / canvasWidth
    withTransform({ scale(scale, scale, Offset.Zero) }) {
        for (shape in shapes) {
            val color = inkColor(shape.stroke.color, onSurface, theme)
            if (shape.stroke.tool == InkTool.Pen) {
                drawPath(shape.path, color)
            } else {
                drawPath(
                    shape.path,
                    color.copy(alpha = InkGeometry.MARKER_ALPHA),
                    style = Stroke(shape.stroke.size, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/** A drawing at its own shape, in the theme's colors. */
@Composable
internal fun InkCanvas(drawing: InkDrawing, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val theme = BenbowTheme.colors
    val shapes = remember(drawing) { drawing.strokes.map(::InkShape) }
    Canvas(modifier.aspectRatio(drawing.width.toFloat() / drawing.height)) { drawInk(shapes, drawing.width, colors.onSurface, theme) }
}

/** A drawing attachment, read from its file (parsed off the main thread, kept for the session). */
@Composable
internal fun DrawingPicture(media: Media, attachment: AttachmentState, modifier: Modifier = Modifier) {
    val drawing by rememberDrawing(media, attachment)
    Box(modifier, contentAlignment = Alignment.Center) {
        drawing?.let { InkCanvas(it) }
    }
}

@Composable
internal fun rememberDrawing(media: Media, attachment: AttachmentState) =
    produceState(DrawingCache[attachment.meta.sha256], attachment.meta.sha256) {
        if (value != null) return@produceState
        val sha = attachment.meta.sha256
        value = runCatching {
            withContext(Dispatchers.Default) {
                val file = media.localFile(sha) ?: media.file(sha)
                InkSvg.decode(file.readAllText())
            }
        }.getOrNull()?.also { DrawingCache[sha] = it }
    }

/** Parsed drawings by file hash (files never change, so they never go stale). Used on the main thread only. */
internal object DrawingCache {
    private val map = LinkedHashMap<String, InkDrawing>() // least recently used first: a use moves it to the end

    operator fun get(sha: String): InkDrawing? = map.remove(sha)?.also { map[sha] = it }

    operator fun set(sha: String, drawing: InkDrawing) {
        map.remove(sha)
        map[sha] = drawing
        if (map.size > 48) map.remove(map.keys.first())
    }
}
