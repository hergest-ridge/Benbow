package app.benbow.ui.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.benbow.core.text.Align
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.ListKind
import app.benbow.core.text.Mark
import app.benbow.core.text.Paragraph
import app.benbow.core.text.SizeStep
import app.benbow.ui.theme.BenbowPalette

/** Maps the semantic model to Compose text styles. All visuals of formatting live here. */
@Immutable
data class EditorStyles(val colors: ColorScheme, val dark: Boolean, val baseSize: TextUnit = 17.sp) {

    fun calloutIndent(p: Paragraph): Float = if (p.callout != null) 40f else 0f

    private fun blockIndent(p: Paragraph): Float = when (p.style) {
        BlockStyle.Quote -> 18f
        BlockStyle.Code -> 12f
        else -> 0f
    }

    /** Left text indent in sp; list markers are drawn in the space just before it. */
    fun indent(p: Paragraph): TextUnit = (calloutIndent(p) + blockIndent(p) + if (p.list != null) 28f * p.level else 0f).sp

    private fun headingFactor(p: Paragraph): Float = when (p.style) {
        BlockStyle.H1 -> 1.6f
        BlockStyle.H2 -> 1.35f
        BlockStyle.H3 -> 1.15f
        else -> 1f
    }

    fun sizeFactor(step: SizeStep): Float = when (step) {
        SizeStep.Small -> 0.85f
        SizeStep.Large -> 1.25f
        SizeStep.XLarge -> 1.5f
    }

    /** [maxSizeFactor] is the largest size mark in the paragraph, so big text never overlaps lines. */
    fun paragraphStyle(p: Paragraph, maxSizeFactor: Float): ParagraphStyle {
        val indent = indent(p)
        return ParagraphStyle(
            textAlign = when (p.align) {
                Align.Start -> TextAlign.Start
                Align.Center -> TextAlign.Center
                Align.End -> TextAlign.End
            },
            lineHeight = (baseSize.value * headingFactor(p) * maxOf(1f, maxSizeFactor) * LINE_HEIGHT).sp,
            textIndent = if (indent.value > 0f) TextIndent(indent, indent) else null,
        )
    }

    fun blockSpan(p: Paragraph): SpanStyle? = when {
        p.isHeading -> SpanStyle(
            fontSize = headingFactor(p).em,
            fontWeight = if (p.style == BlockStyle.H1) FontWeight.Bold else FontWeight.SemiBold,
        )

        p.style == BlockStyle.Code -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.92f.em)

        p.style == BlockStyle.Quote -> SpanStyle(color = colors.onSurfaceVariant)

        p.list == ListKind.Check && p.checked -> SpanStyle(
            color = colors.onSurfaceVariant,
            textDecoration = TextDecoration.LineThrough,
        )

        else -> null
    }

    fun runStyle(marks: Set<Mark>): SpanStyle {
        var weight: FontWeight? = null
        var fontStyle: FontStyle? = null
        var family: FontFamily? = null
        var color = Color.Unspecified
        var background = Color.Unspecified
        var size = TextUnit.Unspecified
        val decorations = mutableListOf<TextDecoration>()
        for (mark in marks) {
            when (mark) {
                Mark.Bold -> weight = FontWeight.Bold

                Mark.Italic -> fontStyle = FontStyle.Italic

                Mark.Underline -> decorations += TextDecoration.Underline

                Mark.Strike -> decorations += TextDecoration.LineThrough

                Mark.Code -> {
                    family = FontFamily.Monospace
                    if (background == Color.Unspecified) background = colors.surfaceVariant
                }

                Mark.Spoiler -> background = colors.onSurface.copy(alpha = 0.16f)

                is Mark.Color -> color = BenbowPalette.text(mark.token, dark) ?: Color.Unspecified

                is Mark.Highlight -> background = BenbowPalette.highlight(mark.token, dark) ?: Color.Unspecified

                is Mark.Size -> size = sizeFactor(mark.step).em

                is Mark.Link, is Mark.EntryLink -> {
                    if (color == Color.Unspecified) color = colors.primary
                    decorations += TextDecoration.Underline
                }
            }
        }
        return SpanStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontStyle = fontStyle,
            fontFamily = family,
            background = background,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        )
    }

    fun calloutBackground(tone: String): Color = BenbowPalette.tint(tone, dark) ?: colors.surfaceVariant

    private companion object {
        const val LINE_HEIGHT = 1.45f
    }
}
