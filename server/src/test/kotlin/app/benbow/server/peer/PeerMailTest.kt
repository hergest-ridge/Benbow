package app.benbow.server.peer

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mailbox over real IMAP (GreenMail in this process): folders, APPEND, removal, IDLE, and two links talking. */
class PeerMailTest {
    private val mail = GreenMail(ServerSetupTest.IMAP.dynamicPort()).apply {
        start()
        setUser("box@benbow.test", "box", "secret")
    }
    private val settings = MailSettings("127.0.0.1", mail.imap.port, "box", "secret", secure = false)

    @AfterTest
    fun stop() = mail.stop()

    @Test
    fun foldersLettersAndRemoval() {
        ImapMailbox(settings).use { box ->
            box.ensureFolder("Benbow-test")
            box.ensureFolder("Benbow-test") // twice is fine
            box.append("Benbow-test", "id-1", "{\"v\":1}")
            box.append("Benbow-test", "id-2", "x".repeat(5_000)) // longer than a mail line may be
            val items = box.items("Benbow-test")
            assertEquals(listOf("id-1", "id-2"), items.map { it.subject })
            assertEquals("{\"v\":1}", items[0].text.trim())
            assertEquals("x".repeat(5_000), items[1].text.trim())
            assertEquals(setOf("id-1", "id-2"), box.subjects("Benbow-test"))
            box.delete("Benbow-test", listOf(items[0].uid))
            assertEquals(listOf("id-2"), box.items("Benbow-test").map { it.subject })
        }
    }

    @Test
    fun idleEndsWhenALetterArrivesAndAfterItsTime() {
        ImapMailbox(settings).use { watcher ->
            ImapMailbox(settings).use { other ->
                watcher.ensureFolder("Benbow-in")
                watcher.items("Benbow-in")
                val started = System.nanoTime()
                watcher.await("Benbow-in", timeoutMs = 1_500)
                val waited = (System.nanoTime() - started) / 1_000_000
                assertTrue(waited in 1_000..8_000, "an empty wait ends after its time ($waited ms)")

                val sender = Thread {
                    Thread.sleep(500)
                    other.append("Benbow-in", "id", "hello")
                }.apply { start() }
                val t0 = System.nanoTime()
                watcher.await("Benbow-in", timeoutMs = 30_000)
                val woke = (System.nanoTime() - t0) / 1_000_000
                sender.join()
                assertTrue(woke < 10_000, "a letter ends the wait ($woke ms)")
                assertEquals(listOf("id"), watcher.items("Benbow-in").map { it.subject })
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
    fun aRefusedLoginIsNotTriedAgain() {
        var attempts = 0
        val link = PeerLink(
            PeerDatabase.inMemory(),
            PeerIdentity.generate(),
            connect = {
                attempts++
                ImapMailbox(it.copy(secure = false))
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
    private fun link() = PeerLink(PeerDatabase.inMemory(), PeerIdentity.generate(), connect = {
        ImapMailbox(it.copy(secure = false))
    }, idleMs = 2_000)

    private fun awaitTrue(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "not within $timeoutMs ms" }
            Thread.sleep(100)
        }
    }
}
