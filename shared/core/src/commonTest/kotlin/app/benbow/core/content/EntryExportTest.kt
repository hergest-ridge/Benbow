package app.benbow.core.content

import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.EntryState
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.LwwValue
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

    @Test
    fun tagsListByTheRegistrysNameElseTheirOwnNeverById() {
        val entry = EntryState(
            id = "entry-1",
            kind = "note",
            day = "2026-10-14",
            createdAt = 0,
            fields = mapOf(
                "tag:море" to LwwValue("Море", Hlc.ZERO),
                "tag:0192-кофе" to LwwValue("Кофе", Hlc.ZERO),
                "tag:старое" to LwwValue(null, Hlc.ZERO),
            ),
        )
        val names = TagNames.of(mapOf("name:море" to "Океан", "cat:море" to "места"))
        assertEquals(mapOf("море" to "Океан"), names)
        val markdown = EntryExport.markdown(entry, null, missing = "missing", tagNames = names) { null }
        assertTrue("tags: [\"Кофе\", \"Океан\"]" in markdown, markdown)
        val sidecar = EntryExport.sidecar(entry, null, names)
        assertTrue("\"id\": \"0192-кофе\"" in sidecar && "\"name\": \"Океан\"" in sidecar, sidecar)
    }

    @Test
    fun aVoiceNotesTranscriptFollowsItsLinkAsAQuote() {
        val voice =
            AttachmentState(
                AttachmentMeta("v1", "sha-v1", 10, "audio/mp4", "voice", "voice-1.m4a"),
                1.0,
                caption = "Утро у моря",
                hlc = Hlc.ZERO,
            )
        val entry = EntryState(
            id = "entry-2",
            kind = "note",
            day = "2026-10-14",
            createdAt = 0,
            fields = mapOf("transcript:v1" to LwwValue("Шли вдоль берега.\nВетер был холодный.", Hlc.ZERO)),
            attachments = mapOf("v1" to voice),
        )
        val body = DocumentCodec.encode(Document(listOf(TextBlock("p1", children = listOf(Span("Прогулка"))))))
        val markdown = EntryExport.markdown(entry, body, missing = "missing") { "../media/${it.meta.name}" }
        assertTrue("[Утро у моря](<../media/voice-1.m4a>)\n> Шли вдоль берега.\n> Ветер был холодный." in markdown, markdown)
        assertTrue("transcript:" !in markdown.substringBefore("---\n\n"), "not a front-matter key")
    }
}
