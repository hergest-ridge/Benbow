package app.benbow.core.ink

import kotlinx.serialization.json.Json

/**
 * A drawing as a file: a plain SVG (paths in real colors, so it opens anywhere without Benbow) that carries the strokes
 * themselves in `<metadata>`, which is what the app reads back to edit and to draw in the theme's colors.
 */
object InkSvg {
    const val MIME = "image/svg+xml"

    private val json = Json { ignoreUnknownKeys = true }
    private const val OPEN = "<benbow-ink xmlns=\"https://benbow.app/ns/ink\" version=\"1\"><![CDATA["
    private const val CLOSE = "]]></benbow-ink>"

    /** [color] turns a palette token into "#rrggbb" for viewers outside the app (the light theme's colors). */
    fun encode(drawing: InkDrawing, color: (token: String) -> String): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${drawing.width} ${drawing.height}" """)
        append("""width="${drawing.width}" height="${drawing.height}">""").append('\n')
        append(
            "<metadata>",
        ).append(OPEN).append(json.encodeToString(InkDrawing.serializer(), drawing)).append(CLOSE).append("</metadata>\n")
        for (stroke in drawing.strokes) {
            when (stroke.tool) {
                InkTool.Pen -> append("""<path fill="${color(stroke.color)}" d="${path(InkGeometry.outline(stroke), close = true)}"/>""")

                InkTool.Marker -> append(
                    """<path fill="none" stroke="${color(stroke.color)}" stroke-opacity="${InkGeometry.MARKER_ALPHA}" """ +
                        """stroke-width="${num(stroke.size)}" stroke-linecap="round" stroke-linejoin="round" """ +
                        """d="${path(InkGeometry.centerline(stroke), close = false)}"/>""",
                )
            }
            append('\n')
        }
        append("</svg>\n")
    }

    /** The strokes back from a Benbow SVG; null for any other file (or a damaged one). */
    fun decode(svg: String): InkDrawing? {
        val start = svg.indexOf(OPEN).takeIf { it >= 0 } ?: return null
        val end = svg.indexOf(CLOSE, start).takeIf { it >= 0 } ?: return null
        return runCatching { json.decodeFromString(InkDrawing.serializer(), svg.substring(start + OPEN.length, end)) }.getOrNull()
    }

    private fun path(xy: FloatArray, close: Boolean): String = buildString {
        for (i in 0 until xy.size / 2) {
            append(if (i == 0) 'M' else 'L').append(num(xy[i * 2])).append(',').append(num(xy[i * 2 + 1]))
        }
        if (close && xy.isNotEmpty()) append('Z')
    }

    /** One decimal is plenty at 1000 units across; no exponents, no trailing ".0". */
    private fun num(v: Float): String {
        val tenths = kotlin.math.round(v * 10).toLong()
        val sign = if (tenths < 0) "-" else ""
        val a = kotlin.math.abs(tenths)
        return if (a % 10 == 0L) "$sign${a / 10}" else "$sign${a / 10}.${a % 10}"
    }
}
