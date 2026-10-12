package app.benbow.core.content

import kotlin.test.Test
import kotlin.test.assertEquals

class ExportersTest {
    private val doc = Document(
        listOf(
            TextBlock("h", style = "h1", children = listOf(Span("Утро #1"))),
            TextBlock(
                "p",
                children = listOf(
                    Span("Было "),
                    Span("очень", listOf("strong")),
                    Span(" тихо", listOf("em", "strong")),
                    Span(", а это "),
                    Span("секрет", listOf("spoiler")),
                    Span(" и "),
                    Span("ссылка", listOf("m1")),
                    Span(" и "),
                    Span("красное", listOf("m2")),
                ),
                markDefs = listOf(MarkDef.Link("m1", "https://x.org"), MarkDef.Color("m2", "red")),
            ),
            TextBlock("l1", listItem = "check", level = 1, checked = true, children = listOf(Span("хлеб"))),
            TextBlock("l2", listItem = "bullet", level = 2, children = listOf(Span("*звёздочка*"))),
            TextBlock("c1", callout = CalloutRef("c", "blue", "💡"), children = listOf(Span("Важно"))),
            TextBlock("c2", callout = CalloutRef("c", "blue", "💡"), children = listOf(Span("Ещё"))),
            CodeBlock("k", "val x = 1", "kotlin"),
            DividerBlock("d"),
        ),
    )

    @Test
    fun richMarkdown() {
        val md = MarkdownExporter(MarkdownFlavor.Rich, colorHex = { if (it == "red") "#d44c47" else null }).export(doc)
        assertEquals(
            """
            # Утро #1

            Было **очень *тихо***, а это <span class="spoiler">секрет</span> и [ссылка](https://x.org) и <span style="color:#d44c47">красное</span>

            - [x] хлеб
              - \*звёздочка\*

            > [!note] 💡
            > Важно
            >
            > Ещё

            ```kotlin
            val x = 1
            ```

            ---
            """.trimIndent(),
            md,
        )
    }

    @Test
    fun cleanMarkdownDropsWhatCommonMarkCannotSay() {
        val md = MarkdownExporter(MarkdownFlavor.Clean).export(doc)
        assertEquals(true, "секрет" in md && "<span" !in md && "[!note]" !in md && "> Важно" in md)
    }

    @Test
    fun plainTextForPreviewsHidesSpoilers() {
        val preview = doc.toPlainText(includeSpoilers = false)
        assertEquals(false, "секрет" in preview)
        assertEquals(true, "секрет" in doc.toPlainText())
        assertEquals("Утро #1", doc.toPlainText().lines().first())
    }
}
