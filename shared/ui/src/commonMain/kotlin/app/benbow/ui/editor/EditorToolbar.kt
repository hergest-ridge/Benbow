package app.benbow.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.benbow.core.text.Align
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.ListKind
import app.benbow.core.text.Mark
import app.benbow.core.text.SizeStep
import app.benbow.ui.theme.BenbowPalette

private enum class Picker { Color, Highlight, Callout }

/** Formatting bar shown above the keyboard. Buttons never take focus, so the selection survives taps. */
@Composable
fun EditorToolbar(state: EditorState, modifier: Modifier = Modifier) {
    var picker by remember { mutableStateOf<Picker?>(null) }
    val marks = state.activeMarks
    val paragraph = state.currentParagraph
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column {
            picker?.let { current ->
                PaletteRow(current) { token ->
                    when (current) {
                        Picker.Color -> state.toggleMark(Mark.Color(token))
                        Picker.Highlight -> state.toggleMark(Mark.Highlight(token))
                        Picker.Callout -> state.toggleCallout(token, "💡")
                    }
                    picker = null
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tool("↶", enabled = state.canUndo) { state.undo() }
                Tool("↷", enabled = state.canRedo) { state.redo() }
                Divider()
                Tool("B", active = Mark.Bold in marks, bold = true) { state.toggleMark(Mark.Bold) }
                Tool("I", active = Mark.Italic in marks) { state.toggleMark(Mark.Italic) }
                Tool("U", active = Mark.Underline in marks) { state.toggleMark(Mark.Underline) }
                Tool("S", active = Mark.Strike in marks) { state.toggleMark(Mark.Strike) }
                Tool("</>", active = Mark.Code in marks) { state.toggleMark(Mark.Code) }
                Tool("||", active = Mark.Spoiler in marks) { state.toggleMark(Mark.Spoiler) }
                Tool("A", active = marks.any { it is Mark.Color }) { picker = if (picker == Picker.Color) null else Picker.Color }
                Tool("▰", active = marks.any { it is Mark.Highlight }) {
                    picker = if (picker ==
                        Picker.Highlight
                    ) {
                        null
                    } else {
                        Picker.Highlight
                    }
                }
                Tool("A+", active = marks.any { it is Mark.Size }) { state.cycleSize(marks) }
                Divider()
                Tool("H1", active = paragraph.style == BlockStyle.H1) { state.toggleBlockStyle(BlockStyle.H1) }
                Tool("H2", active = paragraph.style == BlockStyle.H2) { state.toggleBlockStyle(BlockStyle.H2) }
                Tool("H3", active = paragraph.style == BlockStyle.H3) { state.toggleBlockStyle(BlockStyle.H3) }
                Tool("“", active = paragraph.style == BlockStyle.Quote) { state.toggleBlockStyle(BlockStyle.Quote) }
                Tool("{ }", active = paragraph.style == BlockStyle.Code) { state.toggleBlockStyle(BlockStyle.Code) }
                Tool("💡", active = paragraph.callout != null) {
                    if (paragraph.callout != null) state.removeCallout() else picker = Picker.Callout
                }
                Divider()
                Tool("•", active = paragraph.list == ListKind.Bullet) { state.toggleList(ListKind.Bullet) }
                Tool("1.", active = paragraph.list == ListKind.Number) { state.toggleList(ListKind.Number) }
                Tool("☐", active = paragraph.list == ListKind.Check) { state.toggleList(ListKind.Check) }
                Tool("«", enabled = paragraph.list != null) { state.indent(-1) }
                Tool("»", enabled = paragraph.list != null) { state.indent(1) }
                Divider()
                Tool("⇤", active = paragraph.align == Align.Start) { state.setAlign(Align.Start) }
                Tool("↔", active = paragraph.align == Align.Center) { state.setAlign(Align.Center) }
                Tool("⇥", active = paragraph.align == Align.End) { state.setAlign(Align.End) }
            }
        }
    }
}

private fun EditorState.cycleSize(marks: Set<Mark>) {
    val current = marks.filterIsInstance<Mark.Size>().firstOrNull()
    val next = when (current?.step) {
        null -> SizeStep.Large
        SizeStep.Large -> SizeStep.XLarge
        SizeStep.XLarge -> SizeStep.Small
        SizeStep.Small -> null
    }
    if (next == null) toggleMark(current!!) else toggleMark(Mark.Size(next))
}

@Composable
private fun Tool(label: String, active: Boolean = false, enabled: Boolean = true, bold: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) colors.secondaryContainer else Color.Transparent)
            .focusProperties { canFocus = false }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (bold) FontWeight.Bold else null,
            color = when {
                !enabled -> colors.onSurface.copy(alpha = 0.38f)
                active -> colors.onSecondaryContainer
                else -> colors.onSurface
            },
        )
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun PaletteRow(picker: Picker, onPick: (String) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BenbowPalette.tokens.forEach { token ->
            val color = when (picker) {
                Picker.Color -> BenbowPalette.text(token, dark)
                Picker.Highlight -> BenbowPalette.highlight(token, dark)
                Picker.Callout -> BenbowPalette.tint(token, dark)
            } ?: Color.Gray
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(color)
                    .focusProperties { canFocus = false }
                    .clickable { onPick(token) },
            )
        }
    }
}
