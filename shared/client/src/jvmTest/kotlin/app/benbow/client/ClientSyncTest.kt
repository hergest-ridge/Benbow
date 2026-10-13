package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.benbow.core.sync.PutRevision
import app.benbow.core.sync.SyncOp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClientSyncTest {
    private var time = 1_000L

    private inner class Device(server: FakeServer, private val name: String, paired: Boolean = true) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val api = FakeApi(server)
        val repo = JournalRepository(db, { time++ })
        val engine = SyncEngine(db, { api }, { time++ })

        init {
            if (paired) pair(server)
        }

        fun pair(server: FakeServer) {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, 1, "device-$name")
        }

        fun outbox() = db.benbowQueries.pendingCount().executeAsOne()
    }

    private fun doc(vararg blocks: Pair<String, String>) =
        DocumentCodec.encode(Document(blocks.map { (key, text) -> TextBlock(key, children = listOf(Span(text))) }))

    @Test
    fun entryWrittenOfflineReachesAnotherDevice() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone", paired = false)
        val id = phone.repo.createEntry("2026-10-13", body = doc("k1" to "Записала в метро"))
        assertIs<SyncResult.NotPaired>(phone.engine.sync())
        assertEquals(2, phone.outbox(), "create + revision wait in the outbox")

        phone.pair(server)
        assertEquals(SyncResult.Done(pushed = 2, pulled = 1, rejected = 0), phone.engine.sync())
        assertEquals(0, phone.outbox())

        val desktop = Device(server, "desktop")
        desktop.engine.sync()
        assertEquals(doc("k1" to "Записала в метро"), desktop.repo.entry(id).first()!!.body)
        assertEquals(listOf(DaySummary("2026-10-13", 1)), desktop.repo.days().first())
    }

    @Test
    fun concurrentEditsOfDifferentParagraphsMerge() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val desktop = Device(server, "desktop")
        val id = phone.repo.createEntry("2026-10-13", body = doc("k1" to "Утро", "k2" to "День"))
        phone.engine.sync()
        desktop.engine.sync()

        phone.repo.saveDraft(id, doc("k1" to "Утро, туман", "k2" to "День"))
        phone.repo.commitDraft(id)
        desktop.repo.saveDraft(id, doc("k1" to "Утро", "k2" to "День", "k3" to "Вечер"))
        desktop.repo.commitDraft(id)
        phone.engine.sync()
        desktop.engine.sync()
        phone.engine.sync()

        val merged = doc("k1" to "Утро, туман", "k2" to "День", "k3" to "Вечер")
        assertEquals(merged, phone.repo.entry(id).first()!!.body)
        assertEquals(merged, desktop.repo.entry(id).first()!!.body)
    }

    @Test
    fun lostResponseIsRetriedWithoutDuplicates() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        phone.repo.createEntry("2026-10-13")
        phone.api.dropResponse = true
        assertIs<SyncResult.Failed>(phone.engine.sync())
        assertEquals(2, phone.outbox(), "nothing is dropped without an answer")

        phone.api.dropResponse = false
        assertIs<SyncResult.Done>(phone.engine.sync())
        assertEquals(0, phone.outbox())
        assertEquals(1, server.store.changesSince(0, 100).map { it.second }.distinct().size)
    }

    @Test
    fun wrongServerStopsSyncAndKeepsTheOutbox() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        phone.repo.createEntry("2026-10-13")
        phone.api.serverStore = "someone-else"
        assertEquals(SyncResult.WrongServer("store-1", "someone-else"), phone.engine.sync())
        assertEquals(2, phone.outbox())
        assertTrue(server.store.changesSince(0, 100).isEmpty())
    }

    @Test
    fun rejectedOpsAreKeptForTheUser() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val q = phone.db.benbowQueries
        val op = PutRevision("op-x", "missing-entry", "r-x", emptyList(), doc("k" to "x"))
        q.enqueue(op.opId, op.entryId, ClientJson.encodeToString(SyncOp.serializer(), op), 1)
        val result = phone.engine.sync()
        assertEquals(1, (result as SyncResult.Done).rejected)
        assertEquals(listOf("op-x"), q.rejectedOps().executeAsList().map { it.op_id })
    }

    @Test
    fun draftKeepsItsBaseAndCommitsOnce() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val id = phone.repo.createEntry("2026-10-13", body = doc("k1" to "a"))
        val base = phone.repo.entry(id).first()!!.headRevision
        phone.repo.saveDraft(id, doc("k1" to "ab"))
        phone.repo.saveDraft(id, doc("k1" to "abc"))
        assertTrue(phone.repo.entry(id).first()!!.hasDraft)
        assertTrue(phone.repo.commitDraft(id))
        assertEquals(false, phone.repo.commitDraft(id), "no draft, no revision")
        val ops = phone.db.benbowQueries.pendingOps(10).executeAsList().map { ClientJson.decodeFromString(SyncOp.serializer(), it.op) }
        val revision = ops.filterIsInstance<PutRevision>().last()
        assertEquals(listOf(base), revision.parents)
        assertEquals(doc("k1" to "abc"), revision.body)
    }

    @Test
    fun pickingAVersionResolvesTheConflict() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val desktop = Device(server, "desktop")
        val id = phone.repo.createEntry("2026-10-13", body = doc("k1" to "Утро"))
        phone.engine.sync()
        desktop.engine.sync()
        phone.repo.saveDraft(id, doc("k1" to "Утро на телефоне"))
        phone.repo.commitDraft(id)
        desktop.repo.saveDraft(id, doc("k1" to "Утро за компьютером"))
        desktop.repo.commitDraft(id)
        phone.engine.sync()
        desktop.engine.sync()
        assertTrue(desktop.repo.entry(id).first()!!.conflictHeads.size == 2, "same paragraph changed on both: both heads kept")

        desktop.repo.restoreVersion(id, doc("k1" to "Утро на телефоне"))
        desktop.engine.sync()
        phone.engine.sync()
        assertTrue(server.store.entry(id)!!.conflictHeads.isEmpty())
        assertEquals(doc("k1" to "Утро на телефоне"), phone.repo.entry(id).first()!!.body)
        assertTrue(phone.repo.entry(id).first()!!.conflictHeads.isEmpty())
    }

    @Test
    fun restoredServerIsHealedFromTheDeviceCache() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val before = phone.repo.createEntry("2026-10-12", body = doc("k1" to "Вчера"))
        phone.engine.sync()
        val backup = server.restoredCopy(epoch = 2) // what last night's backup holds

        val after = phone.repo.createEntry("2026-10-13", body = doc("k1" to "Сегодня"))
        phone.repo.saveDraft(before, doc("k1" to "Вчера, дописано утром"))
        phone.repo.commitDraft(before)
        phone.repo.moveToTrash(after)
        phone.engine.sync()
        assertEquals(0, phone.outbox(), "everything was acknowledged by the server that then died")

        phone.api.server = backup
        assertIs<SyncResult.Done>(phone.engine.sync())
        assertEquals(2L, Settings(phone.db).connection!!.epoch)
        assertEquals(doc("k1" to "Сегодня"), backup.store.revision(backup.store.entry(after)!!.head!!)!!.body, "a lost entry came back")
        assertEquals("true", backup.store.entry(after)!!.fields["deleted"]!!.value, "with its trash flag")
        val heads = backup.store.entry(before)!!.conflictHeads
        assertTrue(phone.repo.entry(before).first()!!.headRevision in heads, "the lost edit is back, shown next to the old head")

        val desktop = Device(backup, "desktop")
        desktop.engine.sync()
        assertTrue(desktop.repo.entry(after).first()!!.inTrash)
        assertEquals(2, desktop.repo.entry(before).first()!!.conflictHeads.size)
        assertIs<SyncResult.Done>(phone.engine.sync())
        assertEquals(0, phone.outbox())
    }

    @Test
    fun cursorAheadOfTheServerMeansARestoreEvenWithoutANewEpoch() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        phone.repo.createEntry("2026-10-13", body = doc("k1" to "a"))
        phone.engine.sync()
        phone.api.server = FakeServer() // same store and epoch, empty: a database copied back by hand
        phone.engine.sync()
        assertEquals(1, phone.api.server.store.changesSince(0, 100).map { it.second }.distinct().size)
    }

    @Test
    fun leftoverDraftsAreCommittedAtStartup() = runTest {
        val phone = Device(FakeServer(), "phone")
        val id = phone.repo.createEntry("2026-10-13")
        phone.repo.saveDraft(id, doc("k1" to "набрано перед падением"))
        assertEquals(1, phone.repo.commitLeftoverDrafts())
        assertEquals(false, phone.repo.entry(id).first()!!.hasDraft)
        assertEquals(3, phone.outbox())
    }

    @Test
    fun quickNoteShowsTitleAndPreview() = runTest {
        val phone = Device(FakeServer(), "phone")
        phone.repo.createNote("2026-10-13", "Купить хлеб\nи молоко\n\nи сыр")
        val note = phone.repo.journal().first().single()
        assertEquals("Купить хлеб", note.title)
        assertEquals("и молоко и сыр", note.preview)
    }

    @Test
    fun forgettingTheServerWaitsForUnsyncedChanges() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        phone.repo.createEntry("2026-10-13")
        assertFailsWith<PairingException> { Pairing(phone.db).forget() }
        phone.engine.sync()
        Pairing(phone.db).forget()
        assertEquals(null, Settings(phone.db).connection)
        assertTrue(phone.repo.journal().first().isEmpty())
    }

    @Test
    fun trashHidesAndRestoreBringsBack() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val id = phone.repo.createEntry("2026-10-13", body = doc("k1" to "секрет"))
        phone.repo.moveToTrash(id)
        assertTrue(phone.repo.entriesForDay("2026-10-13").first().isEmpty())
        assertEquals(listOf(id), phone.repo.trash().first().map { it.id })
        phone.engine.sync()
        val desktop = Device(server, "desktop")
        desktop.engine.sync()
        assertTrue(desktop.repo.entry(id).first()!!.inTrash, "trash syncs as a field")
        phone.repo.restore(id)
        assertEquals(listOf(id), phone.repo.entriesForDay("2026-10-13").first().map { it.id })
    }
}
