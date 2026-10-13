package app.benbow.core.text

import app.benbow.core.content.AttachmentBlock
import app.benbow.core.content.toDocument
import app.benbow.core.content.toRichText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AtomEditsTest {
    private val keys = BlockKeys(Random(1))
    private val photo = Atom.Attachment("att-1")

    /** Strings are text paragraphs, atoms are atom paragraphs. */
    private fun doc(vararg lines: Any): RichText = RichText(
        lines.joinToString("\n") { if (it is Atom) ATOM_CHAR.toString() else it as String },
        lines.map { Paragraph(keys.next(), atom = it as? Atom) },
    )

    /** What the user sees: text lines, and "[pic]" for a picture. */
    private fun RichText.lines(): List<String> = paragraphs.indices.map { i ->
        if (paragraphs[i].atom != null) "[pic]".also { assertEquals(ATOM_CHAR.toString(), paragraphText(i)) } else paragraphText(i)
    }

    private fun RichText.type(at: Int, s: String) = applyUserEdit(at, at, s, emptySet(), keys)

    private fun RichText.delete(start: Int, end: Int = start + 1) = applyUserEdit(start, end, "", emptySet(), keys)

    @Test
    fun aPictureSplitsTheLineAndLeavesOneToTypeOn() {
        val middle = doc("Утро у моря").insertAtom(5, photo, keys)
        assertEquals(listOf("Утро ", "[pic]", "у моря"), middle.text.lines())
        assertEquals(8, middle.selectionStart, "the cursor waits on the line after")

        val atEnd = doc("Утро").insertAtom(4, photo, keys)
        assertEquals(listOf("Утро", "[pic]", ""), atEnd.text.lines())

        val empty = doc("Первая", "").insertAtom(7, photo, keys)
        assertEquals(listOf("Первая", "[pic]", ""), empty.text.lines())
        assertEquals(9, empty.selectionStart)
    }

    @Test
    fun typingNextToAPictureGoesOnItsOwnLine() {
        val base = doc("А", photo, "Б") // "А\n￼\nБ": the picture's character is at 2
        assertEquals(listOf("А", "[pic]", "x", "Б"), base.type(3, "x").text.lines(), "after it")
        assertEquals(listOf("А", "x", "[pic]", "Б"), base.type(2, "x").text.lines(), "before it")
        assertEquals(listOf("А", "[pic]", "", "Б"), base.type(3, "\n").text.lines(), "Enter after it")
    }

    @Test
    fun backspaceAndDeleteTakeThePictureWhole() {
        val base = doc("А", photo, "Б")
        val afterIt = base.delete(3) // Backspace at the start of "Б": the break after the picture
        assertEquals(listOf("А", "Б"), afterIt.text.lines())
        assertEquals(2, afterIt.selectionStart)
        assertEquals(listOf("А", "Б"), base.delete(2).text.lines(), "its own character")
        assertEquals(listOf("А", "[pic]", "Б"), base.delete(1).text.lines(), "the break before it keeps it")
        assertEquals(listOf("[pic]", "Б"), doc("", photo, "Б").delete(0).text.lines(), "an empty line above goes")
    }

    @Test
    fun selectionsAndPastesNeverLeaveHalfAPicture() {
        val base = doc("Начало", photo, "Конец")
        assertEquals(listOf("Начц"), base.applyUserEdit(3, 13, "", emptySet(), keys).text.lines())
        val pasted = doc("ab").applyUserEdit(1, 1, "x${ATOM_CHAR}y", emptySet(), keys).text
        assertEquals(listOf("axyb"), pasted.lines(), "a stray object character is dropped")
    }

    @Test
    fun picturesSurviveTheDocumentRoundTrip() {
        val text = doc("Утро", "").insertAtom(5, Atom.Attachment("att-9", caption = "Закат"), keys).text
        val back = text.toDocument().toRichText()
        assertEquals(listOf("Утро", "[pic]", ""), back.lines())
        assertIs<AttachmentBlock>(text.toDocument().blocks[1])
        assertTrue(back.atoms().single().second == Atom.Attachment("att-9", caption = "Закат"))
    }
}
