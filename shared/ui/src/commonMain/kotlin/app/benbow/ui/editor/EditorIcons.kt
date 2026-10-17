package app.benbow.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The toolbar's pictures (Material Icons paths, Apache 2.0). Drawn, not typed: symbols such as ↶ or ▰ are missing from
 * the web build's fonts, and Compose for Web would fetch a font from fonts.gstatic.com for them (ADR-030).
 */
internal object EditorIcons {
    val Undo = icon(
        "Undo",
        "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 " +
            "6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
    )
    val Redo = icon(
        "Redo",
        "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 " +
            "7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z",
    )

    /** Material "border_color": a marker over a line, for the highlight. */
    val Highlight = icon(
        "Highlight",
        "M22,24H2v-4h20V24zM13.06,5.19l3.75,3.75L7.75,18H4v-3.75L13.06,5.19zM17.88,7.87l-3.75,-3.75l1.83,-1.83c0.39,-0.39 " +
            "1.02,-0.39 1.41,0l2.34,2.34c0.39,0.39 0.39,1.02 0,1.41L17.88,7.87z",
    )
    val Checklist = icon(
        "Checklist",
        "M19,5v14H5V5h14m0,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z",
    )
    val AlignStart = icon("AlignStart", "M15,15H3v2h12v-2zM15,7H3v2h12V7zM3,13h18v-2H3v2zM3,21h18v-2H3v2zM3,3v2h18V3H3z")
    val AlignCenter = icon("AlignCenter", "M7,15v2h10v-2H7zM3,21h18v-2H3v2zM3,13h18v-2H3v2zM7,7v2h10V7H7zM3,3v2h18V3H3z")
    val AlignEnd = icon("AlignEnd", "M3,21h18v-2H3v2zM9,17h12v-2H9v2zM3,13h18v-2H3v2zM9,9h12V7H9v2zM3,3v2h18V3H3z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}
