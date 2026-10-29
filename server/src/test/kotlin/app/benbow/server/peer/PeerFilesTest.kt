package app.benbow.server.peer

import java.nio.file.Files
import java.nio.file.Path
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

/** Files between servers: sealed bodies, parts through a carrier with everything that can go wrong (ADR-055), Drive's. */
class PeerFilesTest {
    private val dir = Files.createTempDirectory("benbow-files")
    private val bytes = Random(7).nextBytes(3 * 1024 * 1024 + 123) // four parts of 1 MB, sealed
    private var now = 1_000_000L
    private val box = MemoryCarrier(CarrierTraits(maxItem = 1L shl 20, dailyBudget = null, pushes = true, whole = true))

    private inner class Server(name: String, val carrier: Carrier, window: Long = Transfers.IN_FLIGHT_BYTES) {
        val identity = PeerIdentity.generate()
        val db = PeerDatabase.inMemory()
        val peerDir: Path = dir.resolve(name)
        val files = Transfers(db, identity, peerDir, { now }, window)
        val courier = Courier(identity, db, carrier, clock = { now })

        fun pass() = files.pass(carrier, VIA_MAIL, "mail:box")

        val friend get() = db.contacts().single()
    }

    private fun pair(carrier: Carrier = box, window: Long = Transfers.IN_FLIGHT_BYTES): Pair<Server, Server> {
        val anna = Server("anna", carrier, window)
        val max = Server("max", carrier)
        anna.db.addContact("Макс", max.identity.card, 0)
        max.db.addContact("Аня", anna.identity.card, 0)
        return anna to max
    }

    private fun Server.send(): String = files.seal(friend, "фото: море?.jpg", bytes.inputStream(), VIA_MAIL)

    /** Letters and parts both ways until nothing moves. */
    private fun settle(anna: Server, max: Server) {
        repeat(30) {
            val moved = listOf(
                anna.pass().moved,
                anna.courier.round().moved,
                max.courier.round().moved,
                max.pass().moved,
                max.courier.round().moved,
                anna.courier.round().moved,
            )
            if (moved.none { it }) return
        }
        error("still moving")
    }

    private fun Server.got(from: Server) = db.filesIn(from.identity.card.id, 10).single()

    private fun Server.sent() = db.filesOut(friend.id, 10).single()

    private fun assertArrived(anna: Server, max: Server) {
        val got = max.got(anna)
        assertNotNull(got.receivedAt)
        assertContentEquals(bytes, Path.of(got.path!!).readBytes())
        assertNotNull(anna.sent().doneAt)
    }

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
    fun aFileGoesInPartsAndComesWhole() {
        val (anna, max) = pair()
        val id = anna.send()
        settle(anna, max)
        assertArrived(anna, max)
        assertEquals("фото_ море_.jpg", Path.of(max.got(anna).path!!).name) // a name safe for any disk
        val out = anna.sent()
        assertEquals(4, out.parts!!.size)
        assertEquals(4, out.got)
        assertNotNull(out.removedAt)
        assertNull(anna.db.fileKey(id), "the key is forgotten once the friend has the file")
        assertTrue(!anna.peerDir.resolve("outgoing/$id.sealed").exists(), "and so is the sealed copy")
        val parts = Address.parts(max.identity.card)
        assertEquals(4, box.dropped.count { it.first == parts }, "each part dropped once")
        assertEquals(0, box.count(parts))
        assertTrue(max.peerDir.resolve("incoming").listDirectoryEntries().isEmpty())
        assertTrue(max.peerDir.resolve("tmp").listDirectoryEntries().isEmpty(), "no plain leftovers")
        assertTrue(box.dropped.all { it.second <= box.traits.maxItem })
    }

    @Test
    fun partsWaitingForAnAbsentFriendAreNotDroppedAgain() {
        val (anna, max) = pair()
        anna.send()
        anna.pass()
        now += Transfers.RESEND_AFTER_MS * 3
        anna.pass()
        assertEquals(4, box.dropped.count { it.first == Address.parts(max.identity.card) })
        settle(anna, max)
        assertArrived(anna, max)
    }

    @Test
    fun lostPartsAreDroppedAgainOnceGoneFromTheBox() {
        val (anna, max) = pair()
        anna.send()
        anna.pass()
        val parts = Address.parts(max.identity.card)
        box.lose(parts)
        settle(anna, max)
        assertNull(max.got(anna).receivedAt)
        now += Transfers.RESEND_AFTER_MS + 1
        settle(anna, max)
        assertArrived(anna, max)
        assertEquals(8, box.dropped.count { it.first == parts })
    }

    @Test
    fun aDamagedPartIsRemovedAndComesAgain() {
        val (anna, max) = pair()
        val id = anna.send()
        anna.pass()
        anna.courier.round()
        max.courier.round() // the announcement is there
        val parts = Address.parts(max.identity.card)
        box.spoil(parts, "$id.1")
        max.pass()
        assertEquals(0, box.count(parts), "the damaged part is removed from the box, the rest taken")
        assertEquals(setOf(0, 2, 3), max.db.partsIn(id))
        settle(anna, max)
        assertNull(max.got(anna).receivedAt)
        now += Transfers.RESEND_AFTER_MS + 1
        settle(anna, max)
        assertArrived(anna, max)
    }

    @Test
    fun doubledPartsAreTakenOnce() {
        val (anna, max) = pair()
        anna.send()
        anna.pass()
        val parts = Address.parts(max.identity.card)
        box.double(parts)
        settle(anna, max)
        assertArrived(anna, max)
        assertEquals(0, box.count(parts))
        assertEquals(4, anna.sent().got)
    }

    @Test
    fun aCutBeforeThePartsLeaveTheBoxRepeatsHarmlessly() {
        val (anna, max) = pair()
        anna.send()
        anna.pass()
        anna.courier.round()
        max.courier.round()
        box.failNextRemove = true
        assertFailsWith<IllegalStateException> { max.pass() }
        settle(anna, max)
        assertArrived(anna, max)
        assertEquals(0, box.count(Address.parts(max.identity.card)))
        assertEquals(4, box.dropped.count { it.first == Address.parts(max.identity.card) }, "nothing had to be sent again")
    }

    @Test
    fun partsWaitForTheDailyBudget() {
        val tight = MemoryCarrier(CarrierTraits(maxItem = 1L shl 20, dailyBudget = (2L shl 20) + 100, pushes = true, whole = true))
        val (anna, max) = pair(tight)
        anna.send()
        settle(anna, max)
        assertEquals(2, anna.sent().dropped)
        assertEquals(now + DAY_MS, anna.files.budgetUntil)
        now += DAY_MS
        settle(anna, max)
        assertArrived(anna, max)
        assertNull(anna.files.budgetUntil)
    }

    @Test
    fun onlyAWindowOfPartsIsOnItsWayAtOnce() {
        val (anna, max) = pair(window = 2L shl 20)
        anna.send()
        anna.pass()
        assertEquals(2, anna.sent().dropped, "an absent friend's box doesn't fill up with the whole file")
        settle(anna, max)
        assertArrived(anna, max)
    }

    @Test
    fun aPartStillSyncingInAFolderWaitsUntilWhole() {
        val drive = dir.resolve("drive")
        val (anna, max) = pair(FolderCarrier(drive, dir.resolve("staging"), pollMs = 50))
        val id = anna.send()
        anna.pass()
        anna.courier.round()
        max.courier.round()
        val part = drive.resolve("Benbow/${max.identity.card.id.take(12)}/$id.0") // one part: a folder takes 8 MB
        val whole = part.readBytes()
        part.writeBytes(whole.copyOf(whole.size / 3))
        max.pass()
        assertTrue(part.exists(), "not removed: the syncing program is still bringing it")
        assertNull(max.got(anna).receivedAt)
        part.writeBytes(whole)
        settle(anna, max)
        assertArrived(anna, max)
        assertTrue(drive.toFile().walk().none { it.isFile }, "nothing left in the folder")
    }

    @Test
    fun partsAnnouncedWhileThisServerDidNotKnowTheKindAreTakenAfterAll() {
        val (anna, max) = pair()
        val file = PartedFile("f1", "a.txt", 1, "00", "a2V5", 30, 30, listOf("00"))
        max.db.receive(anna.identity.card.id, Letter("l1", 1, Letter.FILE_PARTS, fileJson.encodeToString(file), sentAt = 0), 0)
        assertTrue(max.db.pendingIn(VIA_MAIL).isEmpty())
        Transfers(max.db, max.identity, max.peerDir, { now })
        assertEquals(listOf("f1"), max.db.pendingIn(VIA_MAIL).map { it.id })
    }

    @Test
    fun onlyTheFriendAFileWentToCanSayItHasParts() {
        val (anna, max) = pair()
        val id = anna.send()
        anna.pass()
        val stranger = PeerIdentity.generate().card
        anna.db.addContact("Чужой", stranger, 0)
        assertEquals(0, anna.db.partsGot(stranger.id, id, listOf(0, 1), now))
        assertEquals(1, anna.db.partsGot(max.identity.card.id, id, listOf(1, 99), now))
    }

    @Test
    fun aBodyFromAnOlderServerStillSyncingInDriveWaitsAndIsTakenOnceWhole() {
        val anna = PeerIdentity.generate()
        val max = PeerIdentity.generate()
        val maxDb = PeerDatabase.inMemory().apply { addContact("Аня", anna.card, 0) }
        val folder = DriveFolder(dir.resolve("drive"))
        val maxFiles = DriveFiles(maxDb, max, dir.resolve("max"), clock = { 2 })

        // What a server before ADR-055 did: the sealed body into the Drive folder, then a `file` letter.
        val key = FileSeal.newKey()
        val measured = FileSeal.seal(bytes.inputStream(), dir.resolve("sealing"), key, "file-1")
        val body = folder.place(dir.resolve("sealing"), max.card, "file-1")
        maxDb.announceFile(anna.card.id, SharedFile("file-1", "notes.txt", measured.size, measured.sha256, b64(key)), 0)
        val whole = body.readBytes()
        body.writeBytes(whole.copyOf(whole.size / 3)) // Drive has brought a third of it

        assertTrue(maxFiles.busy())
        assertEquals(0, maxFiles.sweep(folder))
        assertNotNull(maxDb.pendingIn(VIA_DRIVE).single().lastError)

        body.writeBytes(whole)
        assertEquals(1, maxFiles.sweep(folder))
        val got = maxDb.filesIn(anna.card.id, 10).single()
        assertContentEquals(bytes, Path.of(got.path!!).readBytes())
        assertTrue(!body.exists(), "the receiver clears the folder")
        assertEquals(listOf(Letter.FILE_DONE), maxDb.due(anna.card.id, Long.MAX_VALUE).map { it.kind })
        assertEquals(0, maxFiles.sweep(folder), "taken once")
    }

    @Test
    fun twoLinksSendAFileInPartsOnTheirOwn() {
        val shared = MemoryCarrier(CarrierTraits(maxItem = 1L shl 20, dailyBudget = null, pushes = true, whole = true))
        fun link(name: String) = PeerLink(
            PeerDatabase.inMemory(),
            PeerIdentity.generate(),
            connect = { shared },
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
                me.start()
            }
            anna.sendFile(max.identity.card.id, "notes.txt", bytes.inputStream())
            awaitTrue { max.db.filesIn(anna.identity.card.id, 10).singleOrNull()?.receivedAt != null }
            awaitTrue { anna.db.filesOut(max.identity.card.id, 10).single().removedAt != null }
            val got = Path.of(max.db.filesIn(anna.identity.card.id, 10).single().path!!)
            assertContentEquals(bytes, got.readBytes())
        } finally {
            anna.close()
            max.close()
        }
    }

    /** Two links with letters through [letters]; [setUp] chooses how files go. */
    private fun twoLinks(
        letters: Carrier,
        connectParts: (MailSettings) -> Carrier,
        setUp: (PeerLink) -> Unit,
        check: (PeerLink, PeerLink) -> Unit,
    ) {
        fun link(name: String) = PeerLink(
            PeerDatabase.inMemory(),
            PeerIdentity.generate(),
            connect = { letters },
            idleMs = 200,
            peerDir = dir.resolve(name),
            sweepMs = 200,
            connectParts = connectParts,
        )
        val anna = link("anna")
        val max = link("max")
        try {
            for ((me, other) in listOf(anna to max, max to anna)) {
                me.db.addContact("друг", other.identity.card, 0)
                me.db.saveMailSettings(MailSettings("memory", 1, "box", "secret"))
                setUp(me)
                me.start()
            }
            anna.sendFile(max.identity.card.id, "notes.txt", bytes.inputStream())
            awaitTrue { max.db.filesIn(anna.identity.card.id, 10).singleOrNull()?.receivedAt != null }
            awaitTrue { anna.db.filesOut(max.identity.card.id, 10).single().removedAt != null }
            assertContentEquals(bytes, Path.of(max.db.filesIn(anna.identity.card.id, 10).single().path!!).readBytes())
            check(anna, max)
        } finally {
            anna.close()
            max.close()
        }
    }

    @Test
    fun partsCanGoThroughASecondMailboxWhileLettersStayInTheFirst() {
        val letters = MemoryCarrier()
        val parts = MemoryCarrier(CarrierTraits(maxItem = 1L shl 20, dailyBudget = null, pushes = true, whole = true))
        twoLinks(
            letters,
            connectParts = { if (it.user == "files") parts else letters },
            setUp = {
                it.db.savePartsMailSettings(MailSettings("memory", 1, "files", "secret"))
                it.db.saveFilesVia(VIA_PARTS_MAIL)
            },
        ) { anna, _ ->
            assertTrue(letters.dropped.none { it.first.box == Box.PARTS }, "no part in the letters' mailbox")
            assertEquals(4, parts.dropped.count { it.first.box == Box.PARTS })
            assertEquals(VIA_PARTS_MAIL, anna.db.filesOut(anna.db.contacts().single().id, 10).single().via)
        }
    }

    @Test
    fun partsCanGoThroughASharedFolder() {
        val folder = Files.createDirectories(dir.resolve("Google Drive"))
        val letters = MemoryCarrier()
        twoLinks(
            letters,
            connectParts = { error("files don't go through a mailbox here") },
            setUp = {
                it.db.saveDriveFolder(folder.toString())
                it.db.saveFilesVia(VIA_FOLDER)
            },
        ) { _, _ ->
            assertTrue(letters.dropped.none { it.first.box == Box.PARTS })
            assertTrue(folder.toFile().walk().none { it.isFile }, "nothing left in the folder")
        }
    }

    @Test
    fun aFileTheFriendSendsAWayNotSetUpHereWaitsAndSaysWhy() {
        val (anna, max) = pair()
        val link = PeerLink(max.db, max.identity, connect = { box }, peerDir = max.peerDir, sweepMs = 100)
        try {
            max.db.announceParts(anna.identity.card.id, PartedFile("f1", "a.txt", 1, "00", "a2V5", 30, 30, listOf("00"), VIA_FOLDER), now)
            awaitTrue { link.filesTrouble(VIA_FOLDER) != null }
            assertEquals(null, link.filesTrouble(VIA_MAIL))
        } finally {
            link.close()
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
            assertTrue(opened.pendingIn(VIA_DRIVE).isEmpty())
            assertTrue(opened.pendingIn(VIA_MAIL).isEmpty())
        }
        assertTrue(db.parent.listDirectoryEntries("peers-pre-v2-*.sqlite").size == 1, "a copy before the schema changed")
    }

    @Test
    fun aVersionTwoStoreIsCopiedAndItsFilesStayWithTheDriveFolder() {
        val db = dir.resolve("db").also { Files.createDirectories(it) }.resolve(PeerDatabase.FILE)
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE contacts (id TEXT PRIMARY KEY, name TEXT NOT NULL, card TEXT NOT NULL, added_at INTEGER NOT NULL, removed_at INTEGER)",
                )
                st.execute(
                    "CREATE TABLE inbox (contact TEXT, seq INTEGER, letter_id TEXT, kind TEXT, text TEXT, sent_at INTEGER, received_at INTEGER)",
                )
                st.execute(
                    "CREATE TABLE outbox (contact TEXT, seq INTEGER, letter_id TEXT, kind TEXT, text TEXT, created_at INTEGER, sent_at INTEGER, acked_at INTEGER)",
                )
                st.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                st.execute(
                    "CREATE TABLE files_out (id TEXT PRIMARY KEY, contact TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, " +
                        "sha256 TEXT NOT NULL, created_at INTEGER NOT NULL, done_at INTEGER, removed_at INTEGER)",
                )
                st.execute(
                    "CREATE TABLE files_in (id TEXT PRIMARY KEY, contact TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, " +
                        "sha256 TEXT NOT NULL, file_key TEXT NOT NULL, announced_at INTEGER NOT NULL, received_at INTEGER, path TEXT, " +
                        "last_error TEXT)",
                )
                st.execute(
                    "INSERT INTO files_in(id, contact, name, size, sha256, file_key, announced_at) VALUES ('old', 'c', 'a.txt', 1, '00', 'a2V5', 1)",
                )
                st.execute("INSERT INTO files_out(id, contact, name, size, sha256, created_at) VALUES ('mine', 'c', 'b.txt', 1, '00', 1)")
                st.execute("PRAGMA user_version = 2")
            }
        }
        PeerDatabase.open(dir).use { opened ->
            assertEquals(listOf("old"), opened.pendingIn(VIA_DRIVE).map { it.id })
            assertTrue(opened.pendingIn(VIA_MAIL).isEmpty())
            assertTrue(opened.outInFlight(VIA_MAIL).isEmpty(), "a file of the Drive folder is never cut into parts")
            assertEquals(listOf("mine"), opened.outInFlight(VIA_DRIVE).map { it.id })
        }
        assertTrue(db.parent.listDirectoryEntries("peers-pre-v3-*.sqlite").size == 1, "a copy before the schema changed")
    }

    private fun awaitTrue(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "not within $timeoutMs ms" }
            Thread.sleep(50)
        }
    }
}
