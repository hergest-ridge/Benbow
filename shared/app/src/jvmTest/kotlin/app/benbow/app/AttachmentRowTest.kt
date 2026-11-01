package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.core.content.AttachmentBlock
import app.benbow.core.content.AttachmentRowBlock
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.benbow.core.content.toDocument
import app.benbow.core.content.toRichText
import app.benbow.core.text.Atom
import app.benbow.ui.editor.EditorState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rows of two or three pictures in the text (ADR-061): made, taken apart and drawn one height across. */
@OptIn(ExperimentalTestApi::class)
class AttachmentRowTest {
    private fun editor(vararg blocks: app.benbow.core.content.Block) = EditorState(Document(blocks.toList()).toRichText())

    private fun EditorState.atoms() = model.paragraphs.mapNotNull { it.atom }

    private fun EditorState.key(ref: String) = model.paragraphs.first { (it.atom as? Atom.Attachment)?.ref == ref }.key

    @Test
    fun picturesJoinARowOfThreeAtMostAndComeApartAgain() {
        val state = editor(
            TextBlock("t", children = listOf(Span("Поход"))),
            AttachmentBlock("p1", "a"),
            AttachmentBlock("p2", "b"),
            AttachmentBlock("p3", "c"),
            AttachmentBlock("p4", "d"),
        )
        assertFalse(state.canJoinAbove(state.key("a")), "text above it: no row to join")
        state.joinAbove(state.key("b"))
        assertEquals(listOf(Atom.AttachmentRow(listOf("a", "b")), Atom.Attachment("c"), Atom.Attachment("d")), state.atoms())
        state.joinAbove(state.key("c"))
        assertEquals(listOf(Atom.AttachmentRow(listOf("a", "b", "c")), Atom.Attachment("d")), state.atoms())
        assertFalse(state.canJoinAbove(state.key("d")), "three is a row's most")

        val row = state.model.paragraphs.first { it.atom is Atom.AttachmentRow }.key
        state.takeOutOfRow(row, "b")
        assertEquals(listOf(Atom.AttachmentRow(listOf("a", "c")), Atom.Attachment("b"), Atom.Attachment("d")), state.atoms())
        state.splitRow(row)
        assertEquals(listOf("a", "c", "b", "d").map { Atom.Attachment(it) }, state.atoms())

        // One undo step each, back to the row of three.
        state.undo()
        state.undo()
        assertEquals(listOf(Atom.AttachmentRow(listOf("a", "b", "c")), Atom.Attachment("d")), state.atoms())
        assertEquals(state.model.text, state.textFieldState.text.toString(), "the field holds the same text")

        // As a document it is a row block, and it reads back the same.
        val document = state.model.toDocument()
        assertEquals(listOf("a", "b", "c"), document.blocks.filterIsInstance<AttachmentRowBlock>().single().refs)
        assertEquals(document, DocumentCodec.decode(DocumentCodec.encode(document)))
    }

    @Test
    fun aDrawingRedrawnOrDeletedInARowKeepsTheRowRight() {
        val state = editor(AttachmentRowBlock("r", listOf("a", "drawing", "c")))
        state.replaceAttachment("drawing", "drawing2")
        assertEquals(listOf(Atom.AttachmentRow(listOf("a", "drawing2", "c"))), state.atoms())
        state.removeAttachment("drawing2")
        assertEquals(listOf(Atom.AttachmentRow(listOf("a", "c"))), state.atoms())
        state.removeAttachment("c")
        assertEquals(listOf(Atom.Attachment("a")), state.atoms(), "one left is a picture again")
    }

    @Test
    fun aSoundNeverJoinsARow() {
        val state = editor(AttachmentBlock("v", "voice"), AttachmentBlock("p", "a"))
        val picture = { atom: Atom.Attachment -> atom.ref != "voice" }
        assertFalse(state.canJoinAbove(state.key("a"), picture), "a voice note above: no row")
        state.joinAbove(state.key("a"), picture)
        assertEquals(listOf(Atom.Attachment("voice"), Atom.Attachment("a")), state.atoms())
    }

    @Test
    fun aCarriedAttachmentJoinsAPictureAtItsSide() {
        val state = editor(AttachmentBlock("p1", "a"))
        state.placeInRow(state.key("a"), "b", before = true)
        assertEquals(listOf(Atom.AttachmentRow(listOf("b", "a"))), state.atoms())
        val row = state.model.paragraphs.single().key
        state.placeInRow(row, "c", before = false)
        state.placeInRow(row, "d", before = false)
        assertEquals(listOf(Atom.AttachmentRow(listOf("b", "a", "c"))), state.atoms(), "no fourth")
        state.takeOutOfRow(row, "a")
        state.takeOutOfRow(row, "c")
        assertEquals(listOf(Atom.Attachment("b"), Atom.Attachment("c"), Atom.Attachment("a")), state.atoms(), "one left is a picture again")
    }

    @Test
    fun aRowOfPicturesShowsOneHeightAcrossAndItsBubbleTakesThemApart() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = feedTestServices()
        val entry = services.repo.createNote(services.today(), "Горы")
        val ids = listOf(1200 to 800, 600 to 900).map { (w, h) ->
            val bytes = picture(w, h)
            runBlocking { services.attach({ entry }, { bytes.inputStream() }) { MediaProbe("image/jpeg", "p.jpg", "image", w, h) }.join() }
            runBlocking { services.repo.entry(entry).first()!!.attachments.last().meta.id }
        }
        services.repo.reviseBody(entry) { body ->
            DocumentCodec.encode(Document(DocumentCodec.decode(body).blocks + AttachmentRowBlock("row", ids)))
        }
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilAtLeastOneExists(hasText("Горы"), 5_000)
        onAllNodesWithText("Горы")[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Редактировать").click()
        waitUntilAtLeastOneExists(hasTestTag("atom:row"), 5_000)
        val bounds = onNode(hasTestTag("atom:row")).fetchSemanticsNode().boundsInRoot
        // Wide 3:2 and tall 2:3 side by side at one height: about the width over (1.5 + 0.67), not each picture's own.
        val expected = (bounds.width / density.density - 4) / (1.5f + 2f / 3) + 8
        assertTrue(
            kotlin.math.abs(bounds.height / density.density - expected) < 6,
            "row ${bounds.height / density.density} dp, about $expected",
        )
        assertTrue(
            runBlocking {
                services.repo.entry(entry).first()!!
            }.let { e -> ids.all { id -> e.attachments.any { it.meta.id == id } } },
        )
        screenshot("attachment-row")
    }

    private fun picture(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(0x5A, 0x8F, 0xC8)
            fillRect(0, 0, width, height)
            color = Color(0xE8, 0xD8, 0xA0)
            fillRect(0, height * 2 / 3, width, height / 3)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }
}
