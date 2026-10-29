package app.benbow.server.peer

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Folder
import jakarta.mail.Session
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mailbox over real IMAP (GreenMail in this process): what older servers read, IDLE, and two links talking. */
class PeerMailTest {
    private val mail = GreenMail(ServerSetupTest.IMAP.dynamicPort()).apply {
        start()
        setUser("box@benbow.test", "box", "secret")
    }
    private val settings = MailSettings("127.0.0.1", mail.imap.port, "box", "secret", secure = false)

    @AfterTest
    fun stop() = mail.stop()

    @Test
    fun lettersStayPlainTextInTheirOldFolderAndPartsGoAsAttachments() {
        val letters = Address("a1b2c3d4e5f6a7b8", Box.LETTERS)
        val parts = Address("a1b2c3d4e5f6a7b8", Box.PARTS)
        ImapCarrier(settings).use { box ->
            box.drop(letters, "id-1", "{\"v\":1}".encodeToByteArray())
            box.drop(parts, "f.0", ByteArray(300_000) { (it % 251).toByte() })
        }
        // What a server from before ADR-055 sees: the letters folder of the same name, the letter as plain text.
        val store = Session.getInstance(Properties()).getStore("imap").apply { connect("127.0.0.1", mail.imap.port, "box", "secret") }
        try {
            val folder = store.getFolder("Benbow-a1b2c3d4e5f6").apply { open(Folder.READ_ONLY) }
            val letter = folder.messages.single()
            assertEquals("id-1", letter.subject)
            assertTrue(letter.isMimeType("text/plain"))
            assertEquals("{\"v\":1}", (letter.content as String).trim())
            folder.close(false)
            val part = store.getFolder("Benbow-a1b2c3d4e5f6-parts").apply { open(Folder.READ_ONLY) }.messages.single()
            assertTrue(part.isMimeType("application/octet-stream"))
        } finally {
            store.close()
        }
    }

    @Test
    fun idleEndsWhenALetterArrivesAndAfterItsTime() {
        val inbox = Address("f00dfeedbeef0000", Box.LETTERS)
        ImapCarrier(settings).use { watcher ->
            ImapCarrier(settings).use { other ->
                watcher.waiting(inbox)
                val started = System.nanoTime()
                watcher.await(inbox, timeoutMs = 1_500)
                val waited = (System.nanoTime() - started) / 1_000_000
                assertTrue(waited in 1_000..8_000, "an empty wait ends after its time ($waited ms)")

                val sender = Thread {
                    Thread.sleep(500)
                    other.drop(inbox, "id", "hello".encodeToByteArray())
                }.apply { start() }
                val t0 = System.nanoTime()
                watcher.await(inbox, timeoutMs = 30_000)
                val woke = (System.nanoTime() - t0) / 1_000_000
                sender.join()
                assertTrue(woke < 10_000, "a letter ends the wait ($woke ms)")
                assertEquals(listOf("id"), watcher.waiting(inbox).map { it.name })
            }
        }
    }

    @Test
    fun twoServersExchangeNotesThroughOneMailbox() {
        val anna = link()
        val max = link()
        try {
            val now = System.currentTimeMillis()
            anna.db.addContact("Макс", max.identity.card, now)
            max.db.addContact("Аня", anna.identity.card, now)
            for (link in listOf(anna, max)) {
                link.db.saveMailSettings(settings)
                link.start()
            }
            awaitTrue { anna.status is PeerLink.Status.Online && max.status is PeerLink.Status.Online }
            anna.sendNote(max.identity.card.id, "Привет, Макс")
            max.sendNote(anna.identity.card.id, "Привет, Аня")
            awaitTrue {
                max.db.notes(anna.identity.card.id, 10).any { !it.outgoing && it.text == "Привет, Макс" } &&
                    anna.db.notes(max.identity.card.id, 10).any { !it.outgoing && it.text == "Привет, Аня" }
            }
            awaitTrue {
                anna.db.notes(max.identity.card.id, 10).single { it.outgoing }.ackedAt != null &&
                    max.db.notes(anna.identity.card.id, 10).single { it.outgoing }.ackedAt != null
            }
        } finally {
            anna.close()
            max.close()
        }
    }

    @Test
    fun twoServersSendAFileInPartsThroughOneMailbox() {
        val dir = Files.createTempDirectory("benbow-mail-files")
        val bytes = Random(9).nextBytes(9 * 1024 * 1024) // two parts: 8 MB and the rest
        val anna = link(dir.resolve("anna"))
        val max = link(dir.resolve("max"))
        try {
            anna.db.addContact("Макс", max.identity.card, 0)
            max.db.addContact("Аня", anna.identity.card, 0)
            for (link in listOf(anna, max)) {
                link.db.saveMailSettings(settings)
                link.start()
            }
            anna.sendFile(max.identity.card.id, "video.bin", bytes.inputStream())
            awaitTrue(60_000) { max.db.filesIn(anna.identity.card.id, 10).singleOrNull()?.receivedAt != null }
            assertContentEquals(bytes, Path.of(max.db.filesIn(anna.identity.card.id, 10).single().path!!).readBytes())
            awaitTrue(30_000) { anna.db.filesOut(max.identity.card.id, 10).single().removedAt != null }
            assertEquals(2, anna.db.filesOut(max.identity.card.id, 10).single().parts!!.size)
            val parts = mail.managers.imapHostManager.getFolder(
                mail.managers.userManager.getUser("box"),
                "Benbow-${max.identity.card.id.take(12)}-parts",
            )
            assertEquals(0, parts.messageCount, "the parts left the mailbox")
        } finally {
            anna.close()
            max.close()
        }
    }

    @Test
    fun aRefusedLoginIsNotTriedAgain() {
        var attempts = 0
        val link = PeerLink(
            PeerDatabase.inMemory(),
            PeerIdentity.generate(),
            connect = {
                attempts++
                ImapCarrier(it.copy(secure = false))
            },
        )
        try {
            link.db.saveMailSettings(settings.copy(password = "wrong"))
            link.start()
            awaitTrue { link.status is PeerLink.Status.Failed }
            Thread.sleep(6_000) // past the first pause before a retry
            assertEquals(1, attempts, "a wrong password could get the mailbox locked")
            assertEquals(null, (link.status as PeerLink.Status.Failed).retryAt)
        } finally {
            link.close()
        }
    }

    /** The stored settings say TLS, as the admin page always does; the test server speaks plain IMAP. */
    private fun link(peerDir: Path? = null) = PeerLink(
        PeerDatabase.inMemory(),
        PeerIdentity.generate(),
        connect = { ImapCarrier(it.copy(secure = false)) },
        idleMs = 2_000,
        peerDir = peerDir,
        sweepMs = 300,
    )

    private fun awaitTrue(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "not within $timeoutMs ms" }
            Thread.sleep(100)
        }
    }
}
