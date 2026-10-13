package app.benbow.core.content

import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.EntryState
import app.benbow.core.sync.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryExportTest {
    private fun attachment(id: String, name: String, position: Double) =
        AttachmentState(AttachmentMeta(id, "sha-$id", 10, "image/jpeg", "image", name), position, hlc = Hlc.ZERO)

    @Test
    fun aPictureInTheTextLinksInPlaceAndIsNotListedTwice() {
        val entry = EntryState(
            id = "entry-1",
            kind = "note",
            day = "2026-10-14",
            createdAt = 0,
            attachments = mapOf("a1" to attachment("a1", "IMG 1.jpg", 1.0), "a2" to attachment("a2", "IMG_2.jpg", 2.0)),
        )
        val body = DocumentCodec.encode(
            Document(
                listOf(
                    TextBlock("p1", children = listOf(Span("До"))),
                    AttachmentBlock("b1", "a1"),
                    TextBlock("p2", children = listOf(Span("После"))),
                ),
            ),
        )
        val markdown = EntryExport.markdown(entry, body, missing = "missing") { "../media/${it.meta.name}" }
        val text = markdown.substringAfter("---\n\n")
        assertTrue(text.startsWith("До\n\n![](<../media/IMG 1.jpg>)\n\nПосле"), text)
        assertEquals(1, Regex("IMG 1.jpg").findAll(markdown).count(), "the placed picture isn't repeated in the list")
        assertTrue("![IMG_2.jpg](<../media/IMG_2.jpg>)" in markdown, "the rest are listed after the text")
    }
}
