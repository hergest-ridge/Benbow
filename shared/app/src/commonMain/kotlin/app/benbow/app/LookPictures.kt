package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import org.jetbrains.compose.resources.decodeToImageBitmap

/*
 * Pictures for a note's look (ADR-047): plain SVGs of paths from the free sets (Tabler Icons, Openclipart, Hero
 * Patterns) read into ImageVectors, so they draw like the app's icons on every platform, keep their own colors
 * or take a tint, and need no SVG library. Groups may move or scale; anything fancier (text, images, filters) is
 * left out, and a file of it is not one of ours.
 */

/** The picture at [path] under `files/looks/` (e.g. "openclipart/swallow-silhouette"), once read; null while it loads. */
@Composable
internal fun lookPicture(path: String): ImageVector? {
    // produceState keeps its value when the path changes: the new path's picture is read whatever is shown.
    val vector by produceState(LookPictures.cached(path), path) {
        value = LookPictures.cached(path) ?: LookPictures.load(path)
    }
    return vector
}

/** A photo under `files/looks/` (a paper, a picture; e.g. "paper/paper001.jpg"), decoded once; null while it loads. */
@Composable
internal fun lookPhoto(path: String): ImageBitmap? {
    // As above: one paper after another kept the first (owner).
    val bitmap by produceState(LookPictures.photo(path), path) {
        value = LookPictures.photo(path) ?: LookPictures.loadPhoto(path)
    }
    return bitmap
}

internal object LookPictures {
    private val cache = HashMap<String, ImageVector>()
    private val photos = HashMap<String, ImageBitmap>()

    fun photo(path: String): ImageBitmap? = photos[path]

    suspend fun loadPhoto(path: String): ImageBitmap? = photos[path] ?: runCatching {
        Res.readBytes("files/looks/$path").decodeToImageBitmap()
    }.getOrNull()?.also { photos[path] = it }

    fun cached(path: String): ImageVector? = cache[path]

    suspend fun load(path: String): ImageVector? = cache[path] ?: runCatching {
        parse(path, Res.readBytes("files/looks/$path.svg").decodeToString())
    }.getOrNull()?.also { cache[path] = it }

    /** [svg] as an ImageVector: its paths and circles with their fills and strokes, inside its groups' moves. */
    fun parse(name: String, svg: String): ImageVector? {
        val text = svg.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val root = Regex("<svg\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: return null
        val box = attr(root, "viewBox")?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }
            ?: listOf(0f, 0f, attr(root, "width")?.toFloatOrNull() ?: 24f, attr(root, "height")?.toFloatOrNull() ?: 24f)
        val (x, y, w, h) = box
        if (w <= 0f || h <= 0f) return null
        val builder = ImageVector.Builder(name, 24.dp, (24 * h / w).dp, w, h)
        builder.addGroup(translationX = -x, translationY = -y)
        val inherited = ArrayDeque<Paint>().apply { addLast(Paint.of(root, Paint())) }
        var drawn = 0
        for (tag in Regex("<(/?)(g|path|circle)\\b([^>]*?)(/?)>", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val (closing, kind, attrs, selfClosing) = tag.destructured
            when {
                kind == "g" && closing == "/" -> if (inherited.size > 1) {
                    inherited.removeLast()
                    builder.clearGroup()
                }

                kind == "g" -> if (selfClosing != "/") {
                    val (dx, dy, sx, sy) = transform(attr(attrs, "transform"))
                    builder.addGroup(translationX = dx, translationY = dy, scaleX = sx, scaleY = sy)
                    inherited.addLast(Paint.of(attrs, inherited.last()))
                }

                else -> {
                    val paint = Paint.of(attrs, inherited.last())
                    val nodes = if (kind == "path") {
                        attr(attrs, "d")?.let { runCatching { addPathNodes(it.replace(Regex("\\s+"), " ")) }.getOrNull() }
                    } else {
                        circle(attrs)
                    }
                    if (nodes.isNullOrEmpty()) continue
                    val (dx, dy, sx, sy) = transform(attr(attrs, "transform"))
                    val moved = dx != 0f || dy != 0f || sx != 1f || sy != 1f
                    if (moved) builder.addGroup(translationX = dx, translationY = dy, scaleX = sx, scaleY = sy)
                    builder.addPath(
                        nodes,
                        fill = paint.fill?.let(::SolidColor),
                        stroke = paint.stroke?.let(::SolidColor),
                        strokeLineWidth = paint.strokeWidth,
                        strokeLineCap = if (paint.round) StrokeCap.Round else StrokeCap.Butt,
                        strokeLineJoin = if (paint.round) StrokeJoin.Round else StrokeJoin.Miter,
                        fillAlpha = paint.alpha,
                        strokeAlpha = paint.alpha,
                        pathFillType = if (paint.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    )
                    if (moved) builder.clearGroup()
                    drawn++
                }
            }
        }
        return builder.build().takeIf { drawn > 0 }
    }

    /** How a shape is painted, from its attributes and `style`, falling back to its group's (SVG's default: black fill). */
    private data class Paint(
        val fill: Color? = Color.Black,
        val stroke: Color? = null,
        val strokeWidth: Float = 1f,
        val round: Boolean = false,
        val evenOdd: Boolean = false,
        val alpha: Float = 1f,
    ) {
        companion object {
            fun of(attrs: String, parent: Paint): Paint {
                val style = attr(attrs, "style").orEmpty().split(';').mapNotNull {
                    val (k, v) = it.split(':', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                    k.trim() to v.trim()
                }.toMap()
                fun get(name: String) = style[name] ?: attr(attrs, name)
                return Paint(
                    fill = get("fill")?.let(::color) ?: parent.fill.takeIf { get("fill") == null },
                    stroke = get("stroke")?.let(::color) ?: parent.stroke.takeIf { get("stroke") == null },
                    strokeWidth = get("stroke-width")?.toFloatOrNull() ?: parent.strokeWidth,
                    round = get("stroke-linecap")?.let { it == "round" } ?: parent.round,
                    evenOdd = get("fill-rule")?.let { it == "evenodd" } ?: parent.evenOdd,
                    alpha = parent.alpha * (get("opacity")?.toFloatOrNull() ?: 1f) * (get("fill-opacity")?.toFloatOrNull() ?: 1f),
                )
            }
        }
    }

    private fun attr(tag: String, name: String): String? = Regex("(?:^|\\s)$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun color(value: String): Color? {
        val v = value.trim().lowercase()
        return when {
            v == "none" || v == "transparent" -> null

            v == "currentcolor" || v == "black" -> Color.Black

            v == "white" -> Color.White

            v.startsWith("#") && v.length == 7 -> v.drop(1).toLongOrNull(16)?.let { Color(0xFF000000 or it) }

            v.startsWith("#") && v.length == 4 -> v.drop(1).map {
                "$it$it"
            }.joinToString("").toLongOrNull(16)?.let { Color(0xFF000000 or it) }

            else -> Color.Black
        }
    }

    /** A `translate(...)`, `scale(...)` or plain `matrix(a 0 0 d e f)`: its move and scale; anything else stays put. */
    private fun transform(value: String?): List<Float> {
        if (value == null) return listOf(0f, 0f, 1f, 1f)
        val numbers = { s: String -> Regex("-?[0-9.]+(?:e-?[0-9]+)?").findAll(s).map { it.value.toFloat() }.toList() }
        var dx = 0f
        var dy = 0f
        var sx = 1f
        var sy = 1f
        Regex("(translate|scale|matrix)\\(([^)]*)\\)").findAll(value).forEach { m ->
            val n = numbers(m.groupValues[2])
            when (m.groupValues[1]) {
                "translate" -> {
                    dx += (n.getOrNull(0) ?: 0f) * sx
                    dy += (n.getOrNull(1) ?: 0f) * sy
                }

                "scale" -> {
                    sx *= n.getOrNull(0) ?: 1f
                    sy *= n.getOrNull(1) ?: n.getOrNull(0) ?: 1f
                }

                "matrix" -> if (n.size == 6 && n[1] == 0f && n[2] == 0f) {
                    dx += n[4] * sx
                    dy += n[5] * sy
                    sx *= n[0]
                    sy *= n[3]
                }
            }
        }
        return listOf(dx, dy, sx, sy)
    }

    private fun circle(attrs: String): List<PathNode>? {
        val cx = attr(attrs, "cx")?.toFloatOrNull() ?: 0f
        val cy = attr(attrs, "cy")?.toFloatOrNull() ?: 0f
        val r = attr(attrs, "r")?.toFloatOrNull()?.takeIf { it > 0f } ?: return null
        return listOf(
            PathNode.MoveTo(cx - r, cy),
            PathNode.ArcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, arcStartX = cx + r, arcStartY = cy),
            PathNode.ArcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, arcStartX = cx - r, arcStartY = cy),
            PathNode.Close,
        )
    }
}
