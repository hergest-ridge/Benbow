package app.benbow.core.content

import app.benbow.core.sync.mergeDocumentBodies
import app.benbow.core.text.BlockKeys
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A note as a list (ADR-040, K4): words to boxes and back, ticks, and ticks from two devices merging. */
class ChecklistsTest {
    private fun line(key: String, text: String) = TextBlock(key, children = listOf(Span(text)))

    private fun box(key: String, text: String, checked: Boolean = false) =
        TextBlock(key, listItem = "check", checked = checked, children = listOf(Span(text)))

    private val keys = BlockKeys(Random(1))

    @Test
    fun wordsBecomeBoxesUnderTheirTitleAndBack() {
        val text = Document(listOf(line("t", "Продукты"), line("a", "молоко\nхлеб"), line("b", ""), line("c", "сыр")))
        assertFalse(Checklists.isList(text))
        assertTrue(Checklists.canBeList(text))
        val list = Checklists.toList(text, keys)
        assertTrue(Checklists.isList(list))
        assertEquals(listOf("Продукты", "молоко", "хлеб", "сыр"), list.blocks.map { Checklists.words(it as TextBlock) })
        assertEquals(listOf(false, true, true, true), list.blocks.map(Checklists::isBox))
        assertEquals("a", list.blocks[1].key, "the first line keeps its paragraph's key")
        assertEquals("c", list.blocks[3].key)

        val back = Checklists.toText(Checklists.tick(list, "c", true)!!)
        assertEquals(listOf("Продукты", "молоко", "хлеб", "сыр"), back.blocks.map { Checklists.words(it as TextBlock) })
        assertTrue(back.blocks.none(Checklists::isBox))
    }

    @Test
    fun aLoneLineIsABoxNotATitleAndAnEmptyNoteGetsOne() {
        assertEquals(listOf(true), Checklists.toList(Document(listOf(line("a", "купить ниток"))), keys).blocks.map(Checklists::isBox))
        assertEquals(1, Checklists.toList(Document(listOf(line("a", ""))), keys).blocks.count(Checklists::isBox))
        assertFalse(Checklists.canBeList(Document(listOf(line("a", "x"), LinkCardBlock("l", "https://example.org")))))
    }

    @Test
    fun ticksOnNeighbouringBoxesFromTwoDevicesMerge() {
        val base = Document(listOf(line("t", "Продукты"), box("a", "молоко"), box("b", "хлеб"), box("c", "сыр")))
        val ours = Checklists.tick(base, "a", true)!!
        val theirs = Checklists.tick(base, "b", true)!!
        val merged =
            assertNotNull(mergeDocumentBodies(DocumentCodec.encode(base), DocumentCodec.encode(ours), DocumentCodec.encode(theirs)))
        assertEquals(listOf(null, true, true, false), DocumentCodec.decode(merged).blocks.map { (it as TextBlock).checked })
    }

    @Test
    fun aTickHereAndAWordThereOnTheSameBoxMerge() {
        val base = Document(listOf(box("a", "молоко"), box("b", "хлеб")))
        val ticked = Checklists.tick(base, "a", true)!!
        val worded = Document(listOf(box("a", "молоко 2,5%"), box("b", "хлеб")))
        val merged = DocumentCodec.decode(
            assertNotNull(mergeDocumentBodies(DocumentCodec.encode(base), DocumentCodec.encode(ticked), DocumentCodec.encode(worded))),
        )
        val first = merged.blocks[0] as TextBlock
        assertEquals("молоко 2,5%" to true, Checklists.words(first) to first.checked)
        // The same words changed two ways is still a conflict: both versions are kept for the owner to pick.
        val other = Document(listOf(box("a", "молоко 3,2%"), box("b", "хлеб")))
        assertNull(mergeDocumentBodies(DocumentCodec.encode(base), DocumentCodec.encode(worded), DocumentCodec.encode(other)))
    }
}
