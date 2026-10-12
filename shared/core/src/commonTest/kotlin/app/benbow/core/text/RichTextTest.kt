package app.benbow.core.text

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichTextTest {
    private val keys = BlockKeys(Random(42))

    private fun RichText.type(at: Int, s: String, end: Int = at): EditResult = applyUserEdit(at, end, s, marksForInsertion(at, end), keys)

    @Test
    fun typingInsideAndAtEndOfBoldExtendsIt() {
        val doc = buildRichText(keys) {
            paragraph {
                text("a")
                text("bold", Mark.Bold)
                text("z")
            }
        }
        val inside = doc.type(3, "X").text
        assertEquals(listOf(MarkSpan(Mark.Bold, 1, 6)), inside.marks)
        val atEnd = doc.type(5, "Y").text
        assertEquals(listOf(MarkSpan(Mark.Bold, 1, 6)), atEnd.marks)
        val atStart = doc.type(1, "W").text
        assertEquals(listOf(MarkSpan(Mark.Bold, 2, 6)), atStart.marks)
    }

    @Test
    fun linkDoesNotGrowWhenTypingAtItsEnd() {
        val doc = buildRichText(keys) { paragraph { text("site", Mark.Link("https://x")) } }
        assertEquals(listOf(MarkSpan(Mark.Link("https://x"), 0, 4)), doc.type(4, "!").text.marks)
    }

    @Test
    fun enterContinuesListAndEnterOnEmptyItemLeavesIt() {
        val doc = buildRichText(keys) { paragraph(list = ListKind.Check, checked = true) { text("milk") } }
        val split = doc.type(4, "\n")
        assertEquals("milk\n", split.text.text)
        val second = split.text.paragraphs[1]
        assertEquals(ListKind.Check, second.list)
        assertEquals(false, second.checked)
        assertTrue(second.key != split.text.paragraphs[0].key)

        val exited = split.text.type(5, "\n")
        assertEquals("milk\n", exited.text.text)
        assertNull(exited.text.paragraphs[1].list)
    }

    @Test
    fun backspaceAtListStartRemovesListInsteadOfMerging() {
        val doc = buildRichText(keys) {
            paragraph { text("intro") }
            paragraph(list = ListKind.Bullet) { text("item") }
        }
        val result = doc.applyUserEdit(5, 6, "", emptySet(), keys)
        assertEquals("intro\nitem", result.text.text)
        assertNull(result.text.paragraphs[1].list)
        assertEquals(6, result.selectionStart)
    }

    @Test
    fun enterAtStartOfHeadingKeepsHeadingOnItsText() {
        val doc = buildRichText(keys) { paragraph(style = BlockStyle.H1) { text("Title") } }
        val result = doc.type(0, "\n").text
        assertEquals(BlockStyle.Normal, result.paragraphs[0].style)
        assertEquals(BlockStyle.H1, result.paragraphs[1].style)
        assertEquals(doc.paragraphs[0].key, result.paragraphs[1].key)
    }

    @Test
    fun blockShortcutsTurnIntoFormatting() {
        val cases = mapOf("#" to BlockStyle.H1, "##" to BlockStyle.H2, ">" to BlockStyle.Quote)
        for ((prefix, style) in cases) {
            val doc = RichText(prefix, listOf(Paragraph("k")))
            val result = doc.type(prefix.length, " ")
            assertEquals("", result.text.text)
            assertEquals(style, result.text.paragraphs[0].style)
        }
        val list = RichText("[]", listOf(Paragraph("k"))).type(2, " ").text
        assertEquals(ListKind.Check, list.paragraphs[0].list)
    }

    @Test
    fun inlineShortcutsApplyMarksAndStopThem() {
        val doc = RichText("say **hi*", listOf(Paragraph("k")))
        val result = doc.type(9, "*")
        assertEquals("say hi", result.text.text)
        assertEquals(listOf(MarkSpan(Mark.Bold, 4, 6)), result.text.marks)
        assertEquals(emptySet(), result.typingMarks)

        val italic = RichText("an *idea", listOf(Paragraph("k"))).type(8, "*").text
        assertEquals(listOf(MarkSpan(Mark.Italic, 3, 7)), italic.marks)
    }

    @Test
    fun colorsOfTheSameGroupReplaceEachOther() {
        val doc = buildRichText(keys) { paragraph { text("abcdef", Mark.Color("red")) } }
        val result = doc.toggleMark(2, 4, Mark.Color("blue"))
        assertEquals(
            listOf(MarkSpan(Mark.Color("red"), 0, 2), MarkSpan(Mark.Color("blue"), 2, 4), MarkSpan(Mark.Color("red"), 4, 6)),
            result.marks,
        )
        assertEquals(doc.marks, result.toggleMark(2, 4, Mark.Color("red")).marks)
    }

    @Test
    fun randomEditsKeepInvariants() = runTest {
        val alphabet = listOf("a", "b", " ", "\n", "*", "#", "-", "ю")
        val marks = listOf(Mark.Bold, Mark.Italic, Mark.Color("red"), Mark.Color("blue"), Mark.Link("u"))
        checkAll(Arb.long(), Arb.list(Arb.int(0, 1000), 1..60)) { seed, ops ->
            val rnd = Random(seed)
            val k = BlockKeys(rnd)
            var doc = RichText.empty(k)
            for (op in ops) {
                val len = doc.text.length
                val a = if (len == 0) 0 else rnd.nextInt(len + 1)
                val b = (a + rnd.nextInt(3)).coerceAtMost(len)
                doc = when (op % 4) {
                    0, 1 -> doc.applyUserEdit(a, a, alphabet[op % alphabet.size], doc.marksForInsertion(a, a), k).text
                    2 -> doc.applyUserEdit(a, b, "", emptySet(), k).text
                    else -> if (b > a) doc.toggleMark(a, b, marks[op % marks.size]) else doc.toggleList(a, a, ListKind.Bullet)
                }
                assertInvariants(doc)
            }
        }
    }

    private fun assertInvariants(doc: RichText) {
        assertEquals(doc.text.count { it == '\n' } + 1, doc.paragraphs.size)
        assertEquals(doc.paragraphs.size, doc.paragraphs.map { it.key }.toSet().size, "keys must be unique")
        doc.marks.forEach { assertTrue(it.start in 0 until it.end && it.end <= doc.text.length, "bad span $it") }
        assertEquals(normalize(doc.marks), doc.marks, "marks must stay normalized")
        doc.marks.filter { it.mark.group != null }.groupBy { it.mark.group }.values.forEach { group ->
            val sorted = group.sortedBy { it.start }
            sorted.zipWithNext().forEach { (x, y) -> assertTrue(x.end <= y.start, "group overlap $x $y") }
        }
    }
}
