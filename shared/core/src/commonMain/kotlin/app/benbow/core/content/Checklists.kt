package app.benbow.core.content

import app.benbow.core.text.BlockKeys

/**
 * A note as a list (ADR-040, K4): its first plain paragraph as a title, then its boxes, pictures among them. The boxes
 * keep their order in the document whatever they show as (the ticked ones below): only `checked` changes, so ticks made
 * on two devices merge ([app.benbow.core.sync.mergeDocumentBodies]).
 */
object Checklists {
    const val CHECK = "check"

    fun isBox(block: Block): Boolean = block is TextBlock && block.listItem == CHECK

    /** A title (or none), boxes, pictures: what the board's list editor shows. */
    fun isList(document: Document): Boolean = document.blocks.any(::isBox) &&
        document.blocks.withIndex().all { (i, block) ->
            isBox(block) || block is AttachmentBlock || (i == 0 && block is TextBlock && block.listItem == null)
        }

    /** Whether [toList] keeps everything: only words and pictures. */
    fun canBeList(document: Document): Boolean =
        document.blocks.all { it is TextBlock || it is AttachmentBlock } && document.blocks.any { it is TextBlock }

    /**
     * Every line a box (Keep's «Показать флажки»): the first paragraph stays the title when more words follow it,
     * blank lines go, a line's marks stay unless it was split off a paragraph of several lines.
     */
    fun toList(document: Document, keys: BlockKeys = BlockKeys(), titled: Boolean = true): Document {
        val texts = document.blocks.filterIsInstance<TextBlock>()
        val titled = titled &&
            document.blocks.firstOrNull().let { it is TextBlock && it.listItem == null } && texts.drop(1).any { words(it).isNotBlank() }
        val blocks = ArrayList<Block>()
        document.blocks.forEachIndexed { i, block ->
            when {
                block !is TextBlock -> blocks += block

                i == 0 && titled -> blocks += block

                isBox(block) -> blocks += block

                else -> {
                    val lines = words(block).split('\n')
                    if (lines.size == 1) {
                        if (words(block).isNotBlank()) blocks += box(block.key, block.children, block.markDefs)
                    } else {
                        lines.filter { it.isNotBlank() }.forEachIndexed { j, line ->
                            blocks += box(if (j == 0) block.key else keys.next(), listOf(Span(line)), emptyList())
                        }
                    }
                }
            }
        }
        if (blocks.none(::isBox)) blocks += box(keys.next(), listOf(Span("")), emptyList())
        return Document(blocks)
    }

    /** Boxes back to lines (Keep's «Скрыть флажки»): their words stay, the ticks go. */
    fun toText(document: Document): Document = Document(
        document.blocks.map { block ->
            if (isBox(block)) (block as TextBlock).copy(listItem = null, checked = null, level = null) else block
        },
    )

    /** The box [key] ticked or not; null when there is no such box. */
    fun tick(document: Document, key: String, checked: Boolean): Document? {
        if (document.blocks.none { it.key == key && isBox(it) }) return null
        return Document(document.blocks.map { if (it.key == key && it is TextBlock) it.copy(checked = checked) else it })
    }

    fun words(block: TextBlock): String = block.children.joinToString("") { it.text }

    private fun box(key: String, children: List<Span>, marks: List<MarkDef>) =
        TextBlock(key, listItem = CHECK, level = 1, checked = false, children = children.ifEmpty { listOf(Span("")) }, markDefs = marks)
}
