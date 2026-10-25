package app.benbow.client

import app.benbow.core.content.Block
import app.benbow.core.content.CodeBlock
import app.benbow.core.content.Document
import app.benbow.core.content.LinkCardBlock
import app.benbow.core.content.TextBlock
import app.benbow.core.text.Links
import kotlinx.serialization.Serializable

/**
 * What a note's card on the board shows (ADR-040), made on every write beside the title and preview, so the board
 * never parses a document. Keep-like: the title is the note's title (its first block when it is a heading, the
 * editor's «Название»), then the lines as written, a checklist's open boxes in their order, the ticked ones counted.
 * With «Отмеченные — вниз» off the card shows [inPlace] instead: the ticked boxes where they stand, as on paper.
 */
@Serializable
data class CardPreview(
    val title: String = "",
    val lines: List<CardLine> = emptyList(),
    /** Ticked boxes, left out of [lines] («Отмечено: 3»). */
    val ticked: Int = 0,
    /** There is more than [lines] holds: the card ends in an ellipsis. */
    val more: Boolean = false,
    /** The lines with the ticked boxes in their places; empty while nothing is ticked (then they are [lines]). */
    val inPlace: List<CardLine> = emptyList(),
    /** There is more than [inPlace] holds. */
    val inPlaceMore: Boolean = false,
)

/**
 * A line of a card: plain, a box (with its block's [key], so the card can tick it; [checked] only in
 * [CardPreview.inPlace]), or a list item with its [bullet] ("•", "2."); a [heading] reads bold.
 */
@Serializable
data class CardLine(
    val text: String,
    val box: Boolean = false,
    val bullet: String? = null,
    val heading: Boolean = false,
    val key: String? = null,
    val checked: Boolean = false,
)

/**
 * The card of [document]: at most [CARD_LINES] lines of up to [CARD_LINE] characters, [CARD_CHARS] in all, spoilers
 * left out (ADR-031). Enough for Keep's card of some 26 rows of text; the card itself cuts what doesn't fit.
 */
internal fun cardOf(document: Document): CardPreview {
    val blocks = document.blocks.filter { it is TextBlock || it is LinkCardBlock || it is CodeBlock }
    val first = blocks.firstOrNull() as? TextBlock
    val titled = first != null && first.listItem == null && first.style.startsWith("h")
    val body = if (titled) blocks.drop(1) else blocks
    val ticked = body.count(::isTicked)
    val (lines, more) = cardLines(body, withTicked = false)
    val (inPlace, inPlaceMore) = if (ticked > 0) cardLines(body, withTicked = true) else emptyList<CardLine>() to false
    val title = if (titled) text(first!!).replace('\n', ' ').trim().take(CARD_LINE) else ""
    return CardPreview(title, lines, ticked, more, inPlace, inPlaceMore)
}

/** The card's lines of [blocks] and whether there is more: the ticked boxes left out, or [withTicked] in their places. */
private fun cardLines(blocks: List<Block>, withTicked: Boolean): Pair<List<CardLine>, Boolean> {
    val lines = ArrayList<CardLine>()
    var more = false
    var number = 0
    var chars = 0
    for (block in blocks) {
        val item = (block as? TextBlock)?.listItem
        number = if (item == "number") number + 1 else 0
        val checked = isTicked(block)
        if (checked && !withTicked) continue
        val bullet = when (item) {
            "bullet" -> "•"
            "number" -> "$number."
            else -> null
        }
        val heading = (block as? TextBlock)?.style?.startsWith("h") == true
        text(block).split('\n').forEachIndexed { i, line ->
            val box = item == "check" && i == 0
            if (lines.size == CARD_LINES || chars >= CARD_CHARS) {
                more = more || line.isNotBlank()
                return@forEachIndexed
            }
            val room = minOf(CARD_LINE, CARD_CHARS - chars)
            more = more || line.length > room
            chars += minOf(line.length, room)
            lines += CardLine(
                line.take(room).trimEnd(),
                box = box,
                bullet = bullet.takeIf { i == 0 },
                heading = heading,
                key = block.key.takeIf { box },
                checked = checked && box,
            )
        }
    }
    // Blank lines read as the gaps they are, but never at the ends or two in a row.
    val tidy = lines.filterIndexed { i, line -> line.text.isNotBlank() || line.box || (i > 0 && lines[i - 1].text.isNotBlank()) }
        .dropWhile { it.text.isBlank() && !it.box }.dropLastWhile { it.text.isBlank() && !it.box }
    return tidy to more
}

private fun isTicked(block: Block): Boolean = block is TextBlock && block.listItem == "check" && block.checked == true

private fun text(block: Any): String = when (block) {
    is TextBlock -> block.children.filter { SPOILER !in it.marks }.joinToString("") { it.text }
    is LinkCardBlock -> block.title ?: Links.display(block.href)
    is CodeBlock -> block.code
    else -> ""
}

private const val SPOILER = "spoiler"
private const val CARD_LINES = 30
private const val CARD_LINE = 600
private const val CARD_CHARS = 1500
