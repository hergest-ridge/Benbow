package app.benbow.ui.editor

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.isSpecified

/**
 * A note's own font (ADR-047, `look_font`): its [family] for the words and headings, set [scale] times the app's size
 * so a small or narrow hand reads as large as the app's own font does.
 */
@Immutable
class NoteFont(val family: FontFamily, val scale: Float = 1f)

/** The open note's font, read by the editor, the note's title and its card; null is the app's own font. */
val LocalNoteFont = staticCompositionLocalOf<NoteFont?> { null }

/** [style] in [font], if the note has one. */
fun TextStyle.inFont(font: NoteFont?): TextStyle = if (font == null) {
    this
} else {
    copy(
        fontFamily = font.family,
        fontSize = if (fontSize.isSpecified) fontSize * font.scale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * font.scale else lineHeight,
    )
}
