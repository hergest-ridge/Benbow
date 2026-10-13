package app.benbow.core.content

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlExporterTest {
    private val html = HtmlExporter(color = { if (it == "red") "#d44c47" else null })

    @Test
    fun blocksAndMarksKeepEverything() {
        val doc = Document(
            listOf(
                TextBlock("h", style = "h1", children = listOf(Span("Утро <тихое> & ясное"))),
                TextBlock(
                    "p",
                    align = "center",
                    children = listOf(
                        Span("Было "),
                        Span("очень", listOf("strong", "em")),
                        Span(" и "),
                        Span("секрет", listOf("spoiler")),
                        Span(" и "),
                        Span("ссылка", listOf("m1")),
                        Span(" и "),
                        Span("красное", listOf("m2")),
                        Span("строка${SOFT_BREAK}вторая"),
                    ),
                    markDefs = listOf(MarkDef.Link("m1", "https://x.org/?a=1&b=2"), MarkDef.Color("m2", "red")),
                ),
                TextBlock("q", style = "blockquote", children = listOf(Span("Цитата"))),
                CodeBlock("k", "if (a < b) {}", "kotlin"),
                DividerBlock("d", "dots"),
            ),
        )
        assertEquals(
            "<h1>Утро &lt;тихое&gt; &amp; ясное</h1>\n" +
                "<p style=\"text-align:center\">Было <strong><em>очень</em></strong> и <span class=\"spoiler\">секрет</span> и " +
                "<a href=\"https://x.org/?a=1&amp;b=2\">ссылка</a> и <span class=\"color-red\" style=\"color:#d44c47\">красное</span>" +
                "строка<br>вторая</p>\n" +
                "<blockquote><p>Цитата</p></blockquote>\n" +
                "<pre><code class=\"language-kotlin\">if (a &lt; b) {}</code></pre>\n" +
                "<hr class=\"divider-dots\">\n",
            html.export(doc),
        )
    }

    @Test
    fun listsNestAndCalloutsGroup() {
        val doc = Document(
            listOf(
                TextBlock("1", listItem = "number", level = 1, children = listOf(Span("раз"))),
                TextBlock("2", listItem = "bullet", level = 2, children = listOf(Span("вложенный"))),
                TextBlock("3", listItem = "number", level = 1, children = listOf(Span("два"))),
                TextBlock("4", listItem = "check", level = 1, checked = true, children = listOf(Span("хлеб"))),
                TextBlock("c1", callout = CalloutRef("c", "blue", "💡"), children = listOf(Span("Важно"))),
                TextBlock("c2", callout = CalloutRef("c", "blue", "💡"), children = listOf(Span("Ещё"))),
                TextBlock("p", children = listOf(Span("Конец"))),
            ),
        )
        assertEquals(
            "<ol>\n<li>раз<ul>\n<li>вложенный</li></ul>\n</li>\n<li>два</li></ol>\n" +
                "<ul class=\"checklist\">\n<li><input type=\"checkbox\" disabled checked> хлеб</li></ul>\n" +
                "<div class=\"callout tone-blue\"><span class=\"callout-icon\">💡</span>\n<p>Важно</p>\n<p>Ещё</p>\n</div>\n" +
                "<p>Конец</p>\n",
            html.export(doc),
        )
    }

    @Test
    fun anyDocumentGivesBalancedMarkup() {
        val rnd = Random(11)
        repeat(300) {
            val out = html.export(randomDocument(rnd))
            for (tag in listOf("ul", "ol", "li", "div", "p", "blockquote", "strong", "em", "span", "a", "mark")) {
                val opened = Regex("<$tag[ >]").findAll(out).count()
                val closed = Regex("</$tag>").findAll(out).count()
                assertEquals(opened, closed, "<$tag> in\n$out")
            }
            assertTrue("<li<" !in out && "<li<ul" !in out, out)
        }
    }
}
