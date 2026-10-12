package app.benbow.core.text

/** Small DSL for samples and tests: `buildRichText { paragraph(style = H1) { text("Title") } }`. */
class RichTextBuilder(private val keys: BlockKeys) {
    private val text = StringBuilder()
    private val paragraphs = mutableListOf<Paragraph>()
    private val marks = mutableListOf<MarkSpan>()

    inner class ParagraphScope {
        fun text(value: String, vararg marks: Mark) {
            val start = text.length
            text.append(value)
            marks.forEach { this@RichTextBuilder.marks.add(MarkSpan(it, start, text.length)) }
        }
    }

    fun paragraph(
        style: BlockStyle = BlockStyle.Normal,
        list: ListKind? = null,
        level: Int = 1,
        checked: Boolean = false,
        align: Align = Align.Start,
        callout: Callout? = null,
        content: ParagraphScope.() -> Unit = {},
    ) {
        if (paragraphs.isNotEmpty()) text.append('\n')
        paragraphs.add(Paragraph(keys.next(), style, list, level, checked, align, callout))
        ParagraphScope().content()
    }

    fun build(): RichText {
        if (paragraphs.isEmpty()) paragraph()
        return RichText(text.toString(), paragraphs.toList(), normalize(marks))
    }
}

fun buildRichText(keys: BlockKeys = BlockKeys(), block: RichTextBuilder.() -> Unit): RichText =
    RichTextBuilder(keys).apply(block).build()
