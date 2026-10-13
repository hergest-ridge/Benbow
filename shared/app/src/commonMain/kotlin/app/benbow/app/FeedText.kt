package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benbow.app.resources.Res
import app.benbow.app.resources.show_more
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.SOFT_BREAK
import app.benbow.core.content.toRichText
import app.benbow.core.text.Atom
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.ListKind
import app.benbow.core.text.Paragraph
import app.benbow.core.text.RichText
import app.benbow.ui.editor.EditorStyles
import app.benbow.ui.theme.BenbowPalette
import org.jetbrains.compose.resources.stringResource

/** About this much text shows before "Показать ещё" (blocks count a little extra for their spacing). */
private const val BUDGET = 480
private const val BLOCK_COST = 40

/**
 * A post's text in the feed: headings, quotes, lists, checklists, callouts and code keep their shape and inline marks
 * keep the editor's look, but compactly (no alignment or size steps; pictures live in the carousel). A long post stops
 * after a budget with "Показать ещё", which opens the rest in place.
 */
@Composable
internal fun FeedText(body: String, expanded: Boolean, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val rich = remember(body) { runCatching { DocumentCodec.decode(body).toRichText() }.getOrNull() } ?: return
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val styles = remember(colors) { EditorStyles(colors, dark) }
    val blocks = remember(rich) { blocksOf(rich) }
    if (blocks.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var budget = BUDGET
        var cut = false
        for ((index, block) in blocks.withIndex()) {
            if (!expanded && budget <= 0) {
                cut = true
                break
            }
            val lines = if (expanded || block.length <= budget) Int.MAX_VALUE else 4
            if (lines != Int.MAX_VALUE) cut = cut || index < blocks.lastIndex || block.length > budget
            FeedBlock(rich, block, styles, dark, lines)
            budget -= block.length + BLOCK_COST
        }
        if (cut && !expanded) {
            Text(
                stringResource(Res.string.show_more),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier.clickable(onClick = onExpand).padding(vertical = 2.dp),
            )
        }
    }
}

/** Paragraphs drawn together: a code block or a callout is one box; anything else is one paragraph. */
private class Block(val paragraphs: List<Int>, val length: Int)

private fun blocksOf(rich: RichText): List<Block> {
    val result = ArrayList<Block>()
    var i = 0
    val last = rich.paragraphs.lastIndex
    while (i <= last) {
        val p = rich.paragraphs[i]
        val atom = p.atom
        val callout = p.callout
        when {
            atom is Atom.Attachment || atom is Atom.Unknown -> i++

            callout != null || p.style == BlockStyle.Code || p.style == BlockStyle.Quote -> {
                val group = (i..last).takeWhile { j ->
                    val q = rich.paragraphs[j]
                    if (callout != null) q.callout?.key == callout.key else q.style == p.style && q.callout == null
                }
                result += Block(group, group.sumOf { rich.paragraphText(it).length })
                i += group.size
            }

            else -> {
                result += Block(listOf(i), rich.paragraphText(i).length)
                i++
            }
        }
    }
    // Empty lines only between text: never at the ends, never two in a row.
    return result.filterIndexed { k, b ->
        val empty = b.length == 0 && rich.paragraphs[b.paragraphs.first()].atom == null
        !empty || (k > 0 && k < result.lastIndex && result[k - 1].length > 0)
    }.dropWhile { it.length == 0 && rich.paragraphs[it.paragraphs.first()].atom == null }
        .dropLastWhile { it.length == 0 && rich.paragraphs[it.paragraphs.first()].atom == null }
}

@Composable
private fun FeedBlock(rich: RichText, block: Block, styles: EditorStyles, dark: Boolean, maxLines: Int) {
    val colors = MaterialTheme.colorScheme
    val first = rich.paragraphs[block.paragraphs.first()]
    val callout = first.callout
    val body = MaterialTheme.typography.bodyLarge
    when {
        first.atom is Atom.Divider -> HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.outlineVariant)

        callout != null -> Surface(
            color = BenbowPalette.tint(callout.tone, dark) ?: colors.surfaceContainerHigh,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(callout.icon, style = body)
                Column { block.paragraphs.forEach { Line(rich, it, styles, body, maxLines) } }
            }
        }

        first.style == BlockStyle.Code -> Surface(
            color = colors.surfaceContainerHigh,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                val code = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                block.paragraphs.forEach { Line(rich, it, styles, code, maxLines) }
            }
        }

        first.style == BlockStyle.Quote -> Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.primary, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 10.dp)) {
                block.paragraphs.forEach { Line(rich, it, styles, body.copy(color = colors.onSurfaceVariant), maxLines) }
            }
        }

        first.list != null -> ListItem(rich, block.paragraphs.first(), first, styles, body, maxLines)

        first.isHeading -> {
            // Always a step above the 16 sp text, but well below the editor's sizes.
            val heading = when (first.style) {
                BlockStyle.H1 -> body.copy(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
                BlockStyle.H2 -> body.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                else -> body.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
            }
            Line(rich, block.paragraphs.first(), styles, heading, maxLines, Modifier.padding(top = 2.dp))
        }

        else -> Line(rich, block.paragraphs.first(), styles, body, maxLines)
    }
}

@Composable
private fun ListItem(rich: RichText, index: Int, p: Paragraph, styles: EditorStyles, style: TextStyle, maxLines: Int) {
    val done = p.list == ListKind.Check && p.checked
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(start = (16 * (p.level - 1)).dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.widthIn(min = 24.dp)) {
            when (p.list) {
                ListKind.Check -> Icon(
                    if (p.checked) Icons.CheckBox else Icons.CheckBoxBlank,
                    null,
                    tint = if (p.checked) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp).size(18.dp),
                )

                ListKind.Number -> Text("${numberOf(rich, index)}.", style = style, color = colors.onSurfaceVariant)

                else -> Text("•", style = style, color = colors.onSurfaceVariant)
            }
        }
        val text = if (done) style.copy(color = colors.onSurfaceVariant, textDecoration = TextDecoration.LineThrough) else style
        Line(rich, index, styles, text, maxLines)
    }
}

/** 1, 2, 3 … within a run of numbered items at the same level. */
private fun numberOf(rich: RichText, index: Int): Int {
    val p = rich.paragraphs[index]
    var n = 1
    var j = index - 1
    while (j >= 0) {
        val q = rich.paragraphs[j]
        if (q.list == null || q.level < p.level) break
        if (q.level == p.level) {
            if (q.list != ListKind.Number) break
            n++
        }
        j--
    }
    return n
}

@Composable
private fun Line(rich: RichText, index: Int, styles: EditorStyles, style: TextStyle, maxLines: Int, modifier: Modifier = Modifier) {
    val text = remember(rich, index, styles) { annotated(rich, index, styles) }
    if (text.isEmpty()) {
        Box(modifier.height(6.dp).fillMaxWidth())
        return
    }
    Text(text, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** One paragraph with its marks in the editor's styles; soft line breaks become real ones. */
private fun annotated(rich: RichText, index: Int, styles: EditorStyles): AnnotatedString {
    val start = rich.paragraphStarts[index]
    val end = rich.paragraphEnd(index)
    return buildAnnotatedString {
        append(rich.text.substring(start, end).replace(SOFT_BREAK, '\n'))
        rich.markRuns().filter { it.end > start && it.start < end && it.marks.isNotEmpty() }.forEach { run ->
            addStyle(styles.runStyle(run.marks), maxOf(run.start, start) - start, minOf(run.end, end) - start)
        }
    }
}
