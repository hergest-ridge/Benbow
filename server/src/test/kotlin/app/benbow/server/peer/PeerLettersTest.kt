package app.benbow.server.peer

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Cards and sealed letters (ADR-039): only the recipient reads a letter, and only its sender can have written it. */
class PeerLettersTest {
    private val anna = PeerIdentity.generate()
    private val max = PeerIdentity.generate()
    private val letter = Letter("l1", 1, Letter.NOTE, "Привет из другого дома", sentAt = 1_000)

    @Test
    fun aCardSurvivesTextAndRefusesTypos() {
        val text = anna.card.encode()
        assertTrue(text.startsWith(PeerCard.PREFIX))
        assertEquals(anna.card, PeerCard.decode(text))
        assertEquals(anna.card, PeerCard.decode(" ${text.chunked(20).joinToString("\n")} ")) // pasted with line breaks
        val typo = text.dropLast(3) + (if (text[text.length - 3] == 'A') 'B' else 'A') + text.takeLast(2)
        assertFailsWith<IllegalArgumentException> { PeerCard.decode(typo) }
        assertFailsWith<IllegalArgumentException> { PeerCard.decode(text.dropLast(4)) }
        assertFailsWith<IllegalArgumentException> { PeerCard.decode("benbow2." + text.removePrefix(PeerCard.PREFIX)) }
        assertEquals(24, anna.card.fingerprint.length) // five groups of four
        assertNotEquals(anna.card.fingerprint, max.card.fingerprint)
    }

    @Test
    fun theIdentityIsCreatedOnceAndKept() {
        val dir = Files.createTempDirectory("benbow-peer")
        val first = PeerIdentity.ensure(dir)
        assertTrue(dir.resolve(PeerIdentity.FILE).exists())
        assertEquals(first.card, PeerIdentity.ensure(dir).card)
    }

    @Test
    fun onlyTheRecipientOpensALetter() {
        val sealed = Letters.seal(anna, max.card, letter)
        assertTrue("Привет" !in sealed && "\"kind\"" !in sealed && "\"seq\"" !in sealed, "a carrier sees no text and no numbers")
        assertEquals(Opened.Ok(anna.card, letter), Letters.open(max, sealed) { if (it == anna.card.id) anna.card else null })
        val stranger = PeerIdentity.generate()
        assertIs<Opened.Refused>(Letters.open(stranger, sealed) { anna.card })
        assertEquals(Opened.Unknown(anna.card.id), Letters.open(max, sealed) { null })
    }

    @Test
    fun aForgedOrDamagedLetterIsRefused() {
        val sealed = Letters.seal(anna, max.card, letter)
        val envelope = Json.decodeFromString<Envelope>(sealed)
        val json = Json { encodeDefaults = true }
        // Someone with Max's public card writes "from Anna": the signature can't be Anna's.
        val impostor = PeerIdentity.generate()
        val forged = json.decodeFromString<Envelope>(Letters.seal(impostor, max.card, letter)).copy(from = anna.card.id)
        assertIs<Opened.Refused>(Letters.open(max, json.encodeToString(forged)) { anna.card })
        // One byte of the ciphertext changed in the mailbox.
        val bytes = b64d(envelope.sealed).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertIs<Opened.Refused>(Letters.open(max, json.encodeToString(envelope.copy(sealed = b64(bytes)))) { anna.card })
        // A letter Anna sealed for Max, re-addressed to someone else.
        assertIs<Opened.Refused>(Letters.open(max, json.encodeToString(envelope.copy(to = "someone"))) { anna.card })
        assertIs<Opened.Refused>(Letters.open(max, "not json") { anna.card })
    }
}
