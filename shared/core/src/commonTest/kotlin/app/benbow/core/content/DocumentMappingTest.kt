package app.benbow.core.content

import app.benbow.core.text.BlockKeys
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.Callout
import app.benbow.core.text.ListKind
import app.benbow.core.text.Mark
import app.benbow.core.text.RichText
import app.benbow.core.text.SizeStep
import app.benbow.core.text.applyUserEdit
import app.benbow.core.text.buildRichText
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentMappingTest {

    @Test
    fun documentsSurviveTheEditorModel() = runTest {
        checkAll(Arb.long()) { seed ->
            val doc = randomDocument(Random(seed))
            assertEquals(doc, doc.toRichText(BlockKeys(Random(seed))).toDocument())
        }
    }

    @Test
    fun editedTextSurvivesSaveAndLoad() = runTest {
        val alphabet = listOf("a", "Б", " ", "\n", "*", "-", "#")
        val marks = listOf(Mark.Bold, Mark.Italic, Mark.Color("red"), Mark.Link("u"), Mark.Size(SizeStep.Large))
        checkAll(Arb.long(), Arb.list(Arb.int(0, 1000), 1..40)) { seed, ops ->
            val rnd = Random(seed)
            val keys = BlockKeys(rnd)
            var doc = RichText.empty(keys)
            for (op in ops) {
                val len = doc.text.length
                val a = if (len == 0) 0 else rnd.nextInt(len + 1)
                val b = (a + rnd.nextInt(4)).coerceAtMost(len)
                doc = when (op % 5) {
                    0, 1 -> doc.applyUserEdit(a, a, alphabet[op % alphabet.size], doc.marksForInsertion(a, a), keys).text
                    2 -> doc.applyUserEdit(a, b, "", emptySet(), keys).text
                    3 -> if (b > a) doc.toggleMark(a, b, marks[op % marks.size]) else doc.toggleList(a, a, ListKind.Check)
                    else -> doc.toggleBlockStyle(a, b, listOf(BlockStyle.H2, BlockStyle.Quote, BlockStyle.Code)[op % 3])
                }
            }
            val saved = doc.toDocument()
            val loaded = saved.toRichText(BlockKeys(Random(seed)))
            assertEquals(saved, loaded.toDocument())
            assertEquals(doc.text, loaded.text)
            assertEquals(doc.withoutNewlineMarks().marks, loaded.marks)
        }
    }

    @Test
    fun calloutCodeAndAtomsAreKept() {
        val keys = BlockKeys(Random(1))
        val callout = Callout("c", "orange", "💡")
        val original = buildRichText(keys) {
            paragraph(callout = callout) { text("Выноска") }
            paragraph(style = BlockStyle.Code, callout = callout) { text("val x = 1") }
            paragraph(style = BlockStyle.Code, callout = callout) { text("val y = 2") }
            paragraph { text("после") }
        }
        val doc = original.toDocument()
        val code = doc.blocks[1] as CodeBlock
        assertEquals("val x = 1\nval y = 2", code.code)
        assertEquals("c", code.callout?.key)
        val divider = DocumentCodec.decode("""{"v":1,"blocks":[{"_key":"d","_type":"divider","variant":"wave"},{"_key":"u","_type":"x","y":1}]}""")
        assertEquals(divider, divider.toRichText(keys).toDocument())
    }

    /** Marks covering only '\n' have no place in the document: blocks don't include their separators. */
    private fun RichText.withoutNewlineMarks(): RichText {
        var result = this
        text.forEachIndexed { i, c -> if (c == '\n') result = result.copy(marks = result.marks.flatMap { span -> split(span, i) }) }
        return result.copy(marks = app.benbow.core.text.normalize(result.marks))
    }

    private fun split(span: app.benbow.core.text.MarkSpan, at: Int) =
        if (at < span.start || at >= span.end) {
            listOf(span)
        } else {
            listOfNotNull(span.copy(end = at).takeIf { it.end > it.start }, span.copy(start = at + 1).takeIf { it.end > it.start })
        }
}
