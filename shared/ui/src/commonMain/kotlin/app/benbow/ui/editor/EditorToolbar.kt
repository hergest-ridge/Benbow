package app.benbow.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.benbow.core.text.Align
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.ListKind
import app.benbow.core.text.Mark
import app.benbow.core.text.SizeStep
import app.benbow.ui.Hinted
import app.benbow.ui.resources.Res
import app.benbow.ui.resources.tool_align_center
import app.benbow.ui.resources.tool_align_end
import app.benbow.ui.resources.tool_align_start
import app.benbow.ui.resources.tool_bold
import app.benbow.ui.resources.tool_bullets
import app.benbow.ui.resources.tool_callout
import app.benbow.ui.resources.tool_checklist
import app.benbow.ui.resources.tool_code
import app.benbow.ui.resources.tool_code_block
import app.benbow.ui.resources.tool_color
import app.benbow.ui.resources.tool_h1
import app.benbow.ui.resources.tool_h2
import app.benbow.ui.resources.tool_h3
import app.benbow.ui.resources.tool_highlight
import app.benbow.ui.resources.tool_indent_less
import app.benbow.ui.resources.tool_indent_more
import app.benbow.ui.resources.tool_italic
import app.benbow.ui.resources.tool_link
import app.benbow.ui.resources.tool_numbers
import app.benbow.ui.resources.tool_paragraph_panel
import app.benbow.ui.resources.tool_quote
import app.benbow.ui.resources.tool_redo
import app.benbow.ui.resources.tool_size
import app.benbow.ui.resources.tool_spoiler
import app.benbow.ui.resources.tool_strike
import app.benbow.ui.resources.tool_text_panel
import app.benbow.ui.resources.tool_underline
import app.benbow.ui.resources.tool_undo
import app.benbow.ui.sideScroll
import app.benbow.ui.theme.BenbowPalette
import org.jetbrains.compose.resources.stringResource

private enum class Panel { Text, Paragraph }

private enum class Picker { Color, Highlight, Callout }

/** A row above the bottom one comes out from under it and goes back in: it grows from its own bottom edge. */
private val comeOut = expandVertically(expandFrom = Alignment.Bottom) + fadeIn()
private val goIn = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()

/**
 * Formatting bar shown above the keyboard. Buttons never take focus, so the selection survives taps. [onChecklist]
 * takes the checklist button's place where a checklist is something else (a note on the board turns into its list).
 *
 * Readable on a phone (owner): the bottom row holds what is used most (undo, bold, italic, lists) and fits a phone's
 * width; «Аа» opens the rest of the text's look above it (underline, strike, code, spoiler, link, color, size), «H» the
 * paragraph's (headings, quote, code block, callout, alignment). Every tool says its name (and shortcut) in a hint, on
 * hover or a long press; a panel's button shows a dot while something of it applies where the cursor is.
 */
@Composable
fun EditorToolbar(state: EditorState, modifier: Modifier = Modifier, onChecklist: (() -> Unit)? = null) {
    var panel by remember { mutableStateOf<Panel?>(null) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    val marks = state.activeMarks
    val paragraph = state.currentParagraph
    val open = { which: Panel ->
        panel = if (panel == which) null else which
        picker = null
    }
    val pick = { which: Picker -> picker = if (picker == which) null else which }
    // The rows above come out from under the bottom one, which never moves (owner): each grows from its own bottom edge,
    // and the bar grows upward with it. What was open stays drawn while it goes.
    var lastPicker by remember { mutableStateOf(Picker.Color) }
    var lastPanel by remember { mutableStateOf(Panel.Text) }
    picker?.let { lastPicker = it }
    panel?.let { lastPanel = it }
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column {
            AnimatedVisibility(picker != null, enter = comeOut, exit = goIn) {
                PaletteRow(lastPicker) { token ->
                    when (lastPicker) {
                        Picker.Color -> state.toggleMark(Mark.Color(token))
                        Picker.Highlight -> state.toggleMark(Mark.Highlight(token))
                        Picker.Callout -> state.toggleCallout(token, "💡")
                    }
                    picker = null
                }
            }
            AnimatedVisibility(panel != null, enter = comeOut, exit = goIn) {
                Column {
                    when (lastPanel) {
                        Panel.Text -> ToolRow { TextTools(state, marks, picker, pick) }
                        Panel.Paragraph -> ToolRow { ParagraphTools(state, picker, pick) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
            ToolRow {
                Tool(EditorIcons.Undo, stringResource(Res.string.tool_undo), "Ctrl+Z", enabled = state.canUndo) { state.undo() }
                Tool(EditorIcons.Redo, stringResource(Res.string.tool_redo), "Ctrl+Y", enabled = state.canRedo) { state.redo() }
                Divider()
                Tool("B", stringResource(Res.string.tool_bold), "Ctrl+B", Mark.Bold in marks, SpanStyle(fontWeight = FontWeight.Bold)) {
                    state.toggleMark(Mark.Bold)
                }
                Tool("I", stringResource(Res.string.tool_italic), "Ctrl+I", Mark.Italic in marks, SpanStyle(fontStyle = FontStyle.Italic)) {
                    state.toggleMark(Mark.Italic)
                }
                Divider()
                PanelTool("Аа", stringResource(Res.string.tool_text_panel), panel == Panel.Text, marks.any(::inTextPanel)) {
                    open(Panel.Text)
                }
                val styled = paragraph.style != BlockStyle.Normal || paragraph.callout != null || paragraph.align != Align.Start
                PanelTool("H", stringResource(Res.string.tool_paragraph_panel), panel == Panel.Paragraph, styled) { open(Panel.Paragraph) }
                Divider()
                Tool(EditorIcons.Bullets, stringResource(Res.string.tool_bullets), active = paragraph.list == ListKind.Bullet) {
                    state.toggleList(ListKind.Bullet)
                }
                Tool(EditorIcons.Numbers, stringResource(Res.string.tool_numbers), active = paragraph.list == ListKind.Number) {
                    state.toggleList(ListKind.Number)
                }
                Tool(EditorIcons.Checklist, stringResource(Res.string.tool_checklist), active = paragraph.list == ListKind.Check) {
                    onChecklist?.invoke() ?: state.toggleList(ListKind.Check)
                }
                // Indents mean something only in a list: there they come in, elsewhere they take no room.
                if (paragraph.list != null) {
                    Tool(EditorIcons.IndentLess, stringResource(Res.string.tool_indent_less), "Shift+Tab") { state.indent(-1) }
                    Tool(EditorIcons.IndentMore, stringResource(Res.string.tool_indent_more), "Tab") { state.indent(1) }
                }
            }
        }
    }
}

/** «Аа»: the rest of the words' look. */
@Composable
private fun RowScope.TextTools(state: EditorState, marks: Set<Mark>, picker: Picker?, pick: (Picker) -> Unit) {
    Tool(
        "U",
        stringResource(Res.string.tool_underline),
        "Ctrl+U",
        Mark.Underline in marks,
        SpanStyle(textDecoration = TextDecoration.Underline),
    ) {
        state.toggleMark(Mark.Underline)
    }
    Tool("S", stringResource(Res.string.tool_strike), null, Mark.Strike in marks, SpanStyle(textDecoration = TextDecoration.LineThrough)) {
        state.toggleMark(Mark.Strike)
    }
    Divider()
    Tool(EditorIcons.Code, stringResource(Res.string.tool_code), "Ctrl+E", active = Mark.Code in marks) { state.toggleMark(Mark.Code) }
    Tool(EditorIcons.Spoiler, stringResource(Res.string.tool_spoiler), active = Mark.Spoiler in marks) { state.toggleMark(Mark.Spoiler) }
    Tool(
        EditorIcons.Link,
        stringResource(Res.string.tool_link),
        "Ctrl+K",
        active = marks.any {
            it.group == "link"
        },
    ) { state.requestLink() }
    Divider()
    ColorTool(stringResource(Res.string.tool_color), marks.filterIsInstance<Mark.Color>().firstOrNull()?.token, picker == Picker.Color) {
        pick(Picker.Color)
    }
    Tool(
        EditorIcons.Highlight,
        stringResource(Res.string.tool_highlight),
        active =
        marks.any { it is Mark.Highlight } || picker == Picker.Highlight,
    ) {
        pick(Picker.Highlight)
    }
    Tool(EditorIcons.Size, stringResource(Res.string.tool_size), active = marks.any { it is Mark.Size }) { state.cycleSize(marks) }
}

/** «H»: the paragraph's kind and alignment. */
@Composable
private fun RowScope.ParagraphTools(state: EditorState, picker: Picker?, pick: (Picker) -> Unit) {
    val paragraph = state.currentParagraph
    Tool("H1", stringResource(Res.string.tool_h1), null, paragraph.style == BlockStyle.H1, SpanStyle(fontWeight = FontWeight.Bold)) {
        state.toggleBlockStyle(BlockStyle.H1)
    }
    Tool("H2", stringResource(Res.string.tool_h2), null, paragraph.style == BlockStyle.H2, SpanStyle(fontWeight = FontWeight.SemiBold)) {
        state.toggleBlockStyle(BlockStyle.H2)
    }
    Tool("H3", stringResource(Res.string.tool_h3), null, paragraph.style == BlockStyle.H3, SpanStyle(fontWeight = FontWeight.Medium)) {
        state.toggleBlockStyle(BlockStyle.H3)
    }
    Tool(EditorIcons.Quote, stringResource(Res.string.tool_quote), active = paragraph.style == BlockStyle.Quote) {
        state.toggleBlockStyle(BlockStyle.Quote)
    }
    Tool("{ }", stringResource(Res.string.tool_code_block), null, paragraph.style == BlockStyle.Code) {
        state.toggleBlockStyle(BlockStyle.Code)
    }
    Tool("💡", stringResource(Res.string.tool_callout), null, paragraph.callout != null || picker == Picker.Callout) {
        if (paragraph.callout != null) state.removeCallout() else pick(Picker.Callout)
    }
    Divider()
    Tool(EditorIcons.AlignStart, stringResource(Res.string.tool_align_start), active = paragraph.align == Align.Start) {
        state.setAlign(Align.Start)
    }
    Tool(EditorIcons.AlignCenter, stringResource(Res.string.tool_align_center), active = paragraph.align == Align.Center) {
        state.setAlign(Align.Center)
    }
    Tool(EditorIcons.AlignEnd, stringResource(Res.string.tool_align_end), active = paragraph.align == Align.End) {
        state.setAlign(Align.End)
    }
}

/** What «Аа» holds, for its dot. */
private fun inTextPanel(mark: Mark): Boolean = mark == Mark.Underline || mark == Mark.Strike || mark == Mark.Code ||
    mark == Mark.Spoiler || mark.group == "link" || mark is Mark.Color || mark is Mark.Highlight || mark is Mark.Size

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

/**
 * A row of tools that scrolls when it is wider than the screen; its ends fade where more of it waits (owner: hidden
 * tools were easy to miss).
 */
@Composable
private fun ToolRow(content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().fadedEnds(scroll).sideScroll(scroll).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun Modifier.fadedEnds(scroll: ScrollState): Modifier = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = 32.dp.toPx() / size.width
        if (scroll.canScrollBackward) {
            drawRect(Brush.horizontalGradient(0f to Color.Transparent, fade to Color.Black), blendMode = BlendMode.DstIn)
        }
        if (scroll.canScrollForward) {
            drawRect(Brush.horizontalGradient(1f - fade to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
    }

/** A tool drawn as letters in their own look (B bold, U underlined): [name] in its hint and for screen readers. */
@Composable
private fun Tool(
    label: String,
    name: String,
    keys: String?,
    active: Boolean = false,
    look: SpanStyle = SpanStyle(),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ToolBox(name, keys, active, enabled, onClick) { color ->
        Text(text = label, style = MaterialTheme.typography.titleSmall.merge(look), color = color)
    }
}

/** A tool drawn as a picture (symbols such as arrows would need a font the web build doesn't have). */
@Composable
private fun Tool(
    icon: ImageVector,
    name: String,
    keys: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ToolBox(name, keys, active, enabled, onClick) { color -> Icon(icon, null, Modifier.size(22.dp), tint = color) }
}

/** «Аа» and «H»: lit while their row is out, a dot under them while something of theirs applies here. */
@Composable
private fun PanelTool(label: String, name: String, open: Boolean, marked: Boolean, onClick: () -> Unit) {
    ToolBox(name, null, open, true, onClick) { color ->
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = color)
            if (marked && !open) {
                Box(Modifier.padding(top = 26.dp).size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    }
}

/** Text color: an "A" over a bar in the color the words have here (none: the page's). */
@Composable
private fun ColorTool(name: String, token: String?, active: Boolean, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    ToolBox(name, null, active, true, onClick) { color ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("A", style = MaterialTheme.typography.titleSmall, color = color)
            Box(
                Modifier.width(14.dp).height(3.dp).background(
                    token?.let {
                        BenbowPalette.text(it, dark)
                    } ?: color,
                    RoundedCornerShape(1.dp),
                ),
            )
        }
    }
}

@Composable
private fun ToolBox(
    name: String,
    keys: String?,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Hinted(name, keys) {
        Box(
            modifier = Modifier
                .padding(horizontal = 1.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (active) colors.secondaryContainer else Color.Transparent)
                .focusProperties { canFocus = false }
                .hand(enabled).clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            content(
                when {
                    !enabled -> colors.onSurface.copy(alpha = 0.38f)
                    active -> colors.onSecondaryContainer
                    else -> colors.onSurface
                },
            )
        }
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.padding(horizontal = 3.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun PaletteRow(picker: Picker, onPick: (String) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Row(
        modifier = Modifier.sideScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
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
                    .hand().clickable { onPick(token) },
            )
        }
    }
}

/** The pointing hand over what can be clicked (desktop and the browser). */
private fun Modifier.hand(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(PointerIcon.Hand) else this
