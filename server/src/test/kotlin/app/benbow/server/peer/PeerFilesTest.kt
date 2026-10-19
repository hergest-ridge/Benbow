package app.benbow.server.peer

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Files through the shared Drive folder (ADR-039): sealed bodies, a body still syncing, two servers end to end. */
class PeerFilesTest {
    private val dir = Files.createTempDirectory("benbow-files")
    private val bytes = Random(7).nextBytes(3 * 1024 * 1024 + 123) // more than one 1 MB segment

    @Test
    fun aSealedBodyOpensOnlyWholeWithItsKeyAndId() {
        val plain = dir.resolve("plain.bin").also { it.writeBytes(bytes) }
        val sealed = dir.resolve("sealed.bin")
        val key = FileSeal.newKey()
        val measured = FileSeal.seal(plain, sealed, key, "id-1")
        assertEquals(bytes.size.toLong(), measured.size)
        assertEquals(sha256(bytes).hex(), measured.sha256)
        val opened = dir.resolve("opened.bin")
        assertEquals(measured, FileSeal.open(sealed, opened, key, "id-1"))
        assertContentEquals(bytes, opened.readBytes())

        assertFailsWith<Exception> { FileSeal.open(sealed, dir.resolve("x1"), FileSeal.newKey(), "id-1") }
        assertFailsWith<Exception> { FileSeal.open(sealed, dir.resolve("x2"), key, "id-2") } // passed off as another file
        val cut = dir.resolve("cut.bin").also { it.writeBytes(sealed.readBytes().copyOf(sealed.fileSize().toInt() / 2)) }
        assertFailsWith<Exception> { FileSeal.open(cut, dir.resolve("x3"), key, "id-1") }
    }

    @Test
    fun aBodyStillSyncingWaitsAndIsTakenOnceWhole() {
        val anna = PeerIdentity.generate()
        val max = PeerIdentity.generate()
        val annaDb = PeerDatabase.inMemory().apply { addContact("Макс", max.card, 0) }
        val maxDb = PeerDatabase.inMemory().apply { addContact("Аня", anna.card, 0) }
        val folder = DriveFolder(dir.resolve("drive"))
        val annaFiles = FileCarrier(annaDb, anna, dir.resolve("anna"), clock = { 1 })
        val maxFiles = FileCarrier(maxDb, max, dir.resolve("max"), clock = { 2 })

        val file = annaFiles.send(folder, annaDb.contacts().single(), "фото: море?.jpg", bytes.inputStream())
        maxDb.announceFile(anna.card.id, file, 0)
        val body = folder.bodyFor(max.card, file.id)
        val whole = body.readBytes()
        body.writeBytes(whole.copyOf(whole.size / 3)) // Drive has brought a third of it

        assertTrue(maxFiles.sweep(folder).isEmpty())
        assertNotNull(maxDb.pendingIn().single().lastError)

        body.writeBytes(whole)
        assertEquals(listOf(anna.card.id to file.id), maxFiles.sweep(folder))
        val got = maxDb.filesIn(anna.card.id, 10).single()
        assertNull(got.lastError)
        val path = java.nio.file.Path.of(got.path!!)
        assertEquals("фото_ море_.jpg", path.name) // a name safe for any disk
        assertContentEquals(bytes, path.readBytes())
        assertTrue(!body.exists(), "the receiver clears the folder")
        assertTrue(maxFiles.sweep(folder).isEmpty(), "taken once")
        assertTrue(dir.resolve("max/tmp").listDirectoryEntries().isEmpty(), "no plain leftovers")
    }

    @Test
    fun twoServersSendAFileThroughTheFolderAndClearIt() {
        val box = MemoryMailbox()
        val folder = dir.resolve("drive").also { Files.createDirectories(it) }
        fun link(name: String) = PeerLink(
            PeerDatabase.inMemory(),
            PeerIdentity.generate(),
            connect = { box },
            idleMs = 200,
            peerDir = dir.resolve(name),
            sweepMs = 200,
        )
        val anna = link("anna")
        val max = link("max")
        try {
            for ((me, other) in listOf(anna to max, max to anna)) {
                me.db.addContact("друг", other.identity.card, 0)
                me.db.saveMailSettings(MailSettings("memory", 1, "box", "secret"))
                me.db.saveDriveFolder(folder.toString())
                me.start()
            }
            anna.sendFile(max.identity.card.id, "notes.txt", bytes.inputStream())
            awaitTrue { max.db.filesIn(anna.identity.card.id, 10).singleOrNull()?.receivedAt != null }
            awaitTrue { anna.db.filesOut(max.identity.card.id, 10).single().removedAt != null }
            val got = java.nio.file.Path.of(max.db.filesIn(anna.identity.card.id, 10).single().path!!)
            assertContentEquals(bytes, got.readBytes())
            assertTrue(folder.resolve("Benbow").toFile().walk().none { it.isFile }, "nothing left in the Drive folder")
        } finally {
            anna.close()
            max.close()
        }
    }

    @Test
    fun aVersionOneStoreIsCopiedThenGainsTheFileTables() {
        val db = dir.resolve("db").also { Files.createDirectories(it) }.resolve(PeerDatabase.FILE)
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE contacts (id TEXT PRIMARY KEY, name TEXT NOT NULL, card TEXT NOT NULL, added_at INTEGER NOT NULL, removed_at INTEGER)",
                )
                st.execute(
                    "CREATE TABLE outbox (contact TEXT NOT NULL, seq INTEGER NOT NULL, letter_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, text TEXT, created_at INTEGER NOT NULL, sent_at INTEGER, acked_at INTEGER, PRIMARY KEY (contact, seq))",
                )
                st.execute(
                    "CREATE TABLE inbox (contact TEXT NOT NULL, seq INTEGER NOT NULL, letter_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, text TEXT, sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY (contact, seq))",
                )
                st.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                st.execute("PRAGMA user_version = 1")
            }
        }
        val friend = PeerIdentity.generate().card
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.prepareStatement("INSERT INTO contacts(id, name, card, added_at) VALUES (?, 'Аня', ?, 1)").use {
                it.setString(1, friend.id)
                it.setString(2, friend.encode())
                it.executeUpdate()
            }
        }
        PeerDatabase.open(dir).use { opened ->
            assertEquals(listOf("Аня"), opened.contacts().map { it.name })
            assertTrue(opened.pendingIn().isEmpty())
        }
        assertTrue(db.parent.listDirectoryEntries("peers-pre-v2-*.sqlite").size == 1, "a copy before the schema changed")
    }

    private fun awaitTrue(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "not within $timeoutMs ms" }
            Thread.sleep(50)
        }
    }
}
