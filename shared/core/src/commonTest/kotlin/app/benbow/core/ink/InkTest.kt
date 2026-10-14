package app.benbow.core.ink

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InkTest {
    private fun line(tool: InkTool = InkTool.Pen, size: Float = 8f, msPerStep: Float = 16f) = InkStrokeBuilder(tool, "blue", size).apply {
        for (i in 0..20) add(100f + i * 10f, 200f, i * msPerStep)
    }.build()

    @Test
    fun aDrawingSurvivesItsOwnSvg() {
        val drawing = InkDrawing(strokes = listOf(line(), line(InkTool.Marker, size = 40f)))
        val svg = InkSvg.encode(drawing) { "#336699" }
        assertEquals(drawing, InkSvg.decode(svg))
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1000 750\""), svg.take(80))
        assertEquals(2, Regex("<path ").findAll(svg).count(), "one path per stroke, readable without Benbow")
        assertTrue("stroke-opacity=\"0.35\"" in svg, "the marker is see-through")
        assertNull(InkSvg.decode("""<svg xmlns="http://www.w3.org/2000/svg"><path d="M0,0"/></svg>"""), "not ours")
    }

    @Test
    fun aPenLineIsAsWideAsItsSizeAndThinnerWhenFast() {
        val slow = InkGeometry.outline(line(msPerStep = 30f))
        val ys = slow.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(8f, ys.max() - ys.min(), 0.3f, "a slow horizontal line spans its full width")
        val fast = InkGeometry.widths(line(msPerStep = 2f))
        assertTrue(fast.last() < 0.7f * 8f, "a flick is thinner: ${fast.last()}")
    }

    @Test
    fun aDotIsARoundBlot() {
        val dot = InkStrokeBuilder(InkTool.Pen, InkDrawing.INK, 10f).apply { add(50f, 50f, 0f) }.build()
        val outline = InkGeometry.outline(dot)
        for (i in 0 until outline.size / 2) {
            val r = kotlin.math.hypot(outline[i * 2] - 50f, outline[i * 2 + 1] - 50f)
            assertTrue(abs(r - 5f) < 0.01f, "radius $r")
        }
    }

    @Test
    fun theEraserTouchesWhatItCrosses() {
        val stroke = line()
        assertTrue(InkGeometry.hits(stroke, 150f, 205f, 3f), "near the line")
        assertFalse(InkGeometry.hits(stroke, 150f, 230f, 3f), "well below it")
    }

    @Test
    fun pointsTooCloseTogetherAreDropped() {
        val stroke = InkStrokeBuilder(InkTool.Pen, InkDrawing.INK, 8f).apply {
            add(0f, 0f, 0f)
            add(0.2f, 0.1f, 8f)
            add(5.04f, 0f, 16f)
        }.build()
        assertEquals(2, stroke.count)
        assertEquals(5f, stroke.x(1), "rounded to tenths")
    }
}
