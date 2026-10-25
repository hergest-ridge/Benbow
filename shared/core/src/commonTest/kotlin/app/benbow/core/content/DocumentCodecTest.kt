package app.benbow.core.content

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocumentCodecTest {
    private val canonical = """
        {"v":1,"blocks":[
        {"_key":"k3VbQ1aZ","_type":"block","style":"h1","children":[{"_type":"span","text":"Утро","marks":[]}],"markDefs":[]},
        {"_key":"a2","_type":"block","style":"normal","align":"center","children":[{"_type":"span","text":"Было ","marks":[]},{"_type":"span","text":"очень","marks":["strong","m1"]},{"_type":"span","text":" тихо.","marks":[]}],"markDefs":[{"_key":"m1","_type":"color","token":"rose"}]},
        {"_key":"a3","_type":"block","style":"normal","listItem":"check","level":1,"checked":true,"children":[{"_type":"span","text":"купить хлеб","marks":[]}],"markDefs":[]},
        {"_key":"p0Lm77Qx","_type":"divider"}
        ]}
    """.trimIndent()

    @Test
    fun canonicalDocumentRoundTripsByteForByte() {
        assertEquals(canonical, DocumentCodec.encode(DocumentCodec.decode(canonical)))
    }

    @Test
    fun unknownContentIsKeptVerbatim() {
        val json = """{"v":1,"blocks":[
            {"_key":"x1","_type":"poll","question":"?","options":[1,2.50,"z"]},
            {"_key":"x2","_type":"block","style":"h4","children":[],"markDefs":[]},
            {"_key":"x3","_type":"block","style":"normal","future":true,"children":[],"markDefs":[]}
            ]}"""
        val doc = DocumentCodec.decode(json)
        doc.blocks.forEach { assertIs<UnknownBlock>(it) }
        val encoded = DocumentCodec.encode(doc)
        assertTrue("""{"_key":"x1","_type":"poll","question":"?","options":[1,2.50,"z"]}""" in encoded)
        assertTrue(""""style":"h4"""" in encoded && """"future":true""" in encoded)
        assertEquals(encoded, DocumentCodec.encode(DocumentCodec.decode(encoded)))
    }

    @Test
    fun linkCardsRoundTripAndBadOnesStayVerbatim() {
        val json = """{"v":1,"blocks":[
            {"_key":"c1","_type":"linkCard","href":"https://x.org/a","title":"Маяки","description":"о линзах","site":"Хабр","image":"${"b".repeat(
            64,
        )}","view":"large","fetched":"2026-10-19"},
            {"_key":"c2","_type":"linkCard","href":"https://x.org/b"},
            {"_key":"c3","_type":"linkCard","href":"https://x.org/c","image":"not-a-hash"},
            {"_key":"c4","_type":"linkCard","href":"https://x.org/d","view":"huge"}
            ]}"""
        val doc = DocumentCodec.decode(json)
        assertEquals(
            LinkCardBlock("c1", "https://x.org/a", "Маяки", "о линзах", "Хабр", "b".repeat(64), "large", "2026-10-19"),
            doc.blocks[0],
        )
        assertEquals(LinkCardBlock("c2", "https://x.org/b"), doc.blocks[1])
        assertIs<UnknownBlock>(doc.blocks[2])
        assertIs<UnknownBlock>(doc.blocks[3])
        val encoded = DocumentCodec.encode(doc)
        assertTrue("""{"_key":"c2","_type":"linkCard","href":"https://x.org/b"}""" in encoded)
        assertEquals(encoded, DocumentCodec.encode(DocumentCodec.decode(encoded)))
        // A link that is a picture: the picture whole, with its size; a size of nothing is no card of ours.
        val picture = """{"_key":"p1","_type":"linkCard","href":"https://x.org/c.gif","image":"${"c".repeat(64)}","width":480,""" +
            """"height":270,"view":"picture","fetched":"2026-10-25"}"""
        val pictures = DocumentCodec.decode(
            """{"v":1,"blocks":[$picture,{"_key":"p2","_type":"linkCard","href":"https://x.org","width":0}]}""",
        )
        assertEquals(
            LinkCardBlock(
                "p1",
                "https://x.org/c.gif",
                image = "c".repeat(64),
                view = "picture",
                fetched = "2026-10-25",
                width = 480,
                height = 270,
            ),
            pictures.blocks[0],
        )
        assertIs<UnknownBlock>(pictures.blocks[1])
        assertTrue(picture in DocumentCodec.encode(pictures), "written back as it came")
        // A row is the default look and isn't written.
        val row = DocumentCodec.decode(
            """{"v":1,"blocks":[{"_key":"c5","_type":"linkCard","href":"https://x.org","view":"row","title":""}]}""",
        )
        assertEquals(
            """{"v":1,"blocks":[
{"_key":"c5","_type":"linkCard","href":"https://x.org"}
]}""",
            DocumentCodec.encode(row),
        )
    }

    @Test
    fun newerFormatIsReadOnly() {
        val newer = DocumentCodec.decode("""{"v":2,"blocks":[]}""")
        assertFalse(newer.isEditable)
        assertFailsWith<IllegalArgumentException> { DocumentCodec.encode(newer) }
        assertFalse(DocumentCodec.decode("""{"v":1,"blocks":[],"meta":{}}""").isEditable)
    }

    @Test
    fun brokenStructureFailsLoudly() {
        assertFailsWith<DocumentFormatException> { DocumentCodec.decode("not json") }
        assertFailsWith<DocumentFormatException> { DocumentCodec.decode("""{"v":1,"blocks":[{"_type":"block"}]}""") }
    }

    @Test
    fun canonicalizationMergesSpansAndRenumbersMarks() {
        val messy = TextBlock(
            key = "k",
            align = "start",
            children = listOf(Span("a", listOf("x9", "strong")), Span("b", listOf("strong", "x9")), Span(""), Span("c")),
            markDefs = listOf(MarkDef.Color("x9", "red"), MarkDef.Link("unused", "u")),
        )
        val block = DocumentCodec.canonicalize(Document(listOf(messy))).blocks.single() as TextBlock
        assertEquals(listOf(Span("ab", listOf("strong", "m1")), Span("c")), block.children)
        assertEquals(listOf(MarkDef.Color("m1", "red")), block.markDefs)
        assertEquals(null, block.align)
    }

    @Test
    fun strayNewlinesBecomeSoftBreaks() {
        val doc = DocumentCodec.decode(
            """{"v":1,"blocks":[{"_key":"k","_type":"block","style":"normal","children":[{"_type":"span","text":"a\nb","marks":[]}],"markDefs":[]}]}""",
        )
        assertEquals("a${SOFT_BREAK}b", (doc.blocks.single() as TextBlock).children.single().text)
    }

    @Test
    fun randomDocumentsRoundTrip() = runTest {
        checkAll(Arb.long()) { seed ->
            val doc = randomDocument(Random(seed))
            val encoded = DocumentCodec.encode(doc)
            assertEquals(doc, DocumentCodec.decode(encoded))
            assertEquals(encoded, DocumentCodec.encode(DocumentCodec.decode(encoded)))
            assertEquals(doc, DocumentCodec.canonicalize(doc))
            assertEquals(encoded.count { it == '\n' }, doc.blocks.size + 1, "one block per line")
        }
    }
}
