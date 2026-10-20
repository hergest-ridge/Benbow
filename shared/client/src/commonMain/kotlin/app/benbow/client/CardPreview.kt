package app.benbow.client

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
 */
@Serializable
data class CardPreview(
    val title: String = "",
    val lines: List<CardLine> = emptyList(),
    /** Ticked boxes, left out of [lines] («Отмечено: 3»). */
    val ticked: Int = 0,
    /** There is more than [lines] holds: the card ends in an ellipsis. */
    val more: Boolean = false,
)

/**
 * A line of a card: plain, an open box (with its block's [key], so the card can tick it), or a list item with its
 * [bullet] ("•", "2."); a [heading] reads bold.
 */
@Serializable
data class CardLine(
    val text: String,
    val box: Boolean = false,
    val bullet: String? = null,
    val heading: Boolean = false,
    val key: String? = null,
)

/**
 * The card of [document]: at most [CARD_LINES] lines of up to [CARD_LINE] characters, [CARD_CHARS] in all, spoilers
 * left out (ADR-031). Enough for Keep's card of some 26 rows of text; the card itself cuts what doesn't fit.
 */
internal fun cardOf(document: Document): CardPreview {
    val blocks = document.blocks.filter { it is TextBlock || it is LinkCardBlock || it is CodeBlock }
    val first = blocks.firstOrNull() as? TextBlock
    val titled = first != null && first.listItem == null && first.style.startsWith("h")
    val lines = ArrayList<CardLine>()
    var ticked = 0
    var more = false
    var number = 0
    var chars = 0
    for (block in if (titled) blocks.drop(1) else blocks) {
        val item = (block as? TextBlock)?.listItem
        number = if (item == "number") number + 1 else 0
        if (item == "check" && block is TextBlock && block.checked == true) {
            ticked++
            continue
        }
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
            )
        }
    }
    // Blank lines read as the gaps they are, but never at the ends or two in a row.
    val tidy = lines.filterIndexed { i, line -> line.text.isNotBlank() || line.box || (i > 0 && lines[i - 1].text.isNotBlank()) }
        .dropWhile { it.text.isBlank() && !it.box }.dropLastWhile { it.text.isBlank() && !it.box }
    return CardPreview(if (titled) text(first!!).replace('\n', ' ').trim().take(CARD_LINE) else "", tidy, ticked, more)
}

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
