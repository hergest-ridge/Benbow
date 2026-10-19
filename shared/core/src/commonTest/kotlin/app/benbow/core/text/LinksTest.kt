package app.benbow.core.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinksTest {
    private val keys = BlockKeys(Random(7))

    private fun RichText.type(at: Int, s: String, end: Int = at, overSelection: Boolean = false): EditResult =
        applyUserEdit(at, end, s, marksForInsertion(at, end), keys, overSelection)

    private fun text(value: String) = buildRichText(keys) { paragraph { text(value) } }

    @Test
    fun addressesAreRecognizedAndCleaned() {
        assertEquals("https://habr.com/ru/articles/1/", Links.parse("  https://habr.com/ru/articles/1/  "))
        assertEquals("https://www.ya.ru", Links.parse("www.ya.ru"))
        assertEquals("https://x.org/a?id=3#top", Links.parse("https://x.org/a?utm_source=tg&id=3&fbclid=abc#top"))
        assertEquals("https://youtu.be/abc", Links.parse("https://youtu.be/abc?si=XYZ"))
        assertEquals("https://example.com/?si=1", Links.parse("https://example.com/?si=1"))
        assertNull(Links.parse("habr.com"))
        assertNull(Links.parse("see https://x.org"))
        assertNull(Links.parse("https://"))
        assertNull(Links.parse("javascript:alert(1)"))
    }

    @Test
    fun aLinkReadsShort() {
        assertEquals("habr.com/ru/articles/845662", Links.display("https://habr.com/ru/articles/845662/"))
        assertEquals("ru.wikipedia.org/wiki/Маяк", Links.display("https://ru.wikipedia.org/wiki/%D0%9C%D0%B0%D1%8F%D0%BA"))
        assertEquals("me@x.org", Links.display("mailto:me@x.org"))
        val long = Links.display("https://example.com/a/very/long/path/that/goes/on/and/on/forever")
        assertTrue(long.endsWith("…") && long.length <= 40, long)
        assertEquals("habr.com", Links.host("https://www.Habr.com:443/ru?x=1"))
    }

    @Test
    fun onlyKnownSchemesOpen() {
        assertTrue(Links.isOpenable("https://x.org"))
        assertTrue(Links.isOpenable("mailto:me@x.org"))
        assertTrue(Links.isOpenable("tel:+7 900 000-00-00"))
        assertFalse(Links.isOpenable("javascript:alert(1)"))
        assertFalse(Links.isOpenable("file:///etc/passwd"))
        assertFalse(Links.isOpenable("intent://x#Intent;end"))
        assertEquals("https://habr.com/ru", Links.fromField("habr.com/ru"))
        assertNull(Links.fromField("just words"))
    }

    @Test
    fun sentencePunctuationStaysOutOfAnAddress() {
        assertEquals(Triple(4, 22, "https://x.org/a(b)"), Links.find("see https://x.org/a(b).").single())
        assertEquals("https://x.org/a", Links.find("(https://x.org/a)").single().third)
    }

    @Test
    fun anAddressPastedInASentenceReadsShort() {
        val result = text("see ").type(4, "https://habr.com/ru/articles/1/?utm_source=x")
        assertEquals("see habr.com/ru/articles/1", result.text.text)
        assertEquals(listOf(MarkSpan(Mark.Link("https://habr.com/ru/articles/1/"), 4, 26)), result.text.marks)
        assertEquals(26, result.selectionStart)
        // Typing on after it doesn't continue the link.
        val after = result.text.applyUserEdit(26, 26, "!", result.typingMarks ?: emptySet(), keys)
        assertEquals(26, after.text.marks.single().end)
    }

    @Test
    fun anAddressPastedOverSelectedWordsLinksThem() {
        val result = text("read this article").type(5, "https://x.org/a", end = 17, overSelection = true)
        assertEquals("read this article", result.text.text)
        assertEquals(listOf(MarkSpan(Mark.Link("https://x.org/a"), 5, 17)), result.text.marks)
    }

    @Test
    fun aKeyboardReplacingItsWordIsNeverReadAsLinking() {
        // Composing "https://" into "https://h": no selection, so the typed letter must land.
        val result = text("https://").type(0, "https://h", end = 8)
        assertEquals("https://h", result.text.text)
    }

    @Test
    fun anAddressPastedOnAnEmptyLineBecomesACard() {
        val doc = buildRichText(keys) {
            paragraph { text("Утро") }
            paragraph {}
        }
        val result = doc.type(5, "https://x.org/a")
        val card = result.text.paragraphs[1].atom as Atom.LinkCard
        assertEquals("https://x.org/a", card.href)
        assertEquals("Утро\n${ATOM_CHAR}\n", result.text.text)
        assertEquals(7, result.selectionStart) // on the line after the card
        // In a list item it stays a link in the text.
        val list = buildRichText(keys) { paragraph(list = ListKind.Bullet) {} }.type(0, "https://x.org/a")
        assertEquals("x.org/a", list.text.text)
    }

    @Test
    fun anAddressTypedAndFollowedByASpaceIsLinked() {
        val doc = text("go www.ya.ru.")
        val result = doc.type(13, " ")
        assertEquals("go www.ya.ru. ", result.text.text)
        assertEquals(listOf(MarkSpan(Mark.Link("https://www.ya.ru"), 3, 12)), result.text.marks)
        // A keyboard committing the composed word with its space links it too.
        val committed = text("go https://x.org").type(3, "https://x.org ", end = 16)
        assertEquals(listOf(MarkSpan(Mark.Link("https://x.org"), 3, 16)), committed.text.marks)
        // Code is left alone.
        val code = buildRichText(keys) { paragraph(style = BlockStyle.Code) { text("https://x.org") } }.type(13, " ")
        assertTrue(code.text.marks.isEmpty())
    }

    @Test
    fun pastedTextKeepsItsAddressesLinked() {
        val result = text("").type(0, "two links: https://a.org and www.b.org, done")
        assertEquals(listOf("https://a.org", "https://www.b.org"), result.text.marks.map { (it.mark as Mark.Link).href })
    }

    @Test
    fun markdownLinkTurnsIntoLinkedWords() {
        val doc = text("see [the docs](https://x.org/d")
        val result = doc.type(doc.text.length, ")")
        assertEquals("see the docs", result.text.text)
        assertEquals(listOf(MarkSpan(Mark.Link("https://x.org/d"), 4, 12)), result.text.marks)
    }

    @Test
    fun aCardGoesDownToALinkInTheText() {
        val doc = buildRichText(keys) { paragraph { text("a") } }.insertAtom(1, Atom.LinkCard("https://x.org", title = "Маяки"), keys).text
        val index = doc.paragraphs.indexOfFirst { it.atom != null }
        val down = doc.cardToText(index, keys)!!
        assertEquals("a\nМаяки\n", down.text.text)
        assertEquals(listOf(MarkSpan(Mark.Link("https://x.org"), 2, 7)), down.text.marks)
        assertNull(down.text.paragraphs[index].atom)
        val words = down.text.unlink(2, 7)
        assertTrue(words.marks.isEmpty())
    }

    @Test
    fun setLinkReplacesWordsAndLinkAtFindsIt() {
        val doc = text("hello world")
        val linked = doc.setLink(6, 11, Mark.Link("https://x.org"), null, keys).text
        assertEquals(Mark.Link("https://x.org"), linked.linkAt(8)?.mark)
        assertEquals(Mark.Link("https://x.org"), linked.linkAt(11)?.mark)
        assertNull(linked.linkAt(3))
        val renamed = linked.setLink(6, 11, Mark.EntryLink("e1"), "мир", keys).text
        assertEquals("hello мир", renamed.text)
        assertEquals(listOf(MarkSpan(Mark.EntryLink("e1"), 6, 9)), renamed.marks)
    }
}
