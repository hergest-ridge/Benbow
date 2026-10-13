package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.content.AttachmentBlock
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class GalleryTest {
    private var time = 1_000L

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val repo = JournalRepository(db, { time++ })
        val engine = SyncEngine(db, { FakeApi(server) }, { time++ })
        val media = MediaStore(Files.createTempDirectory("benbow-gallery-$name").toFile(), db, { FakeApi(server) }, { time++ })

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }

        suspend fun add(entry: String, name: String, seed: Int) =
            repo.attach(entry, media.import(Random(seed).nextBytes(200).inputStream(), "image/jpeg", name, null), "image")

        suspend fun names(entry: String) = repo.entry(entry).first()!!.attachments.map { it.meta.name }
    }

    @Test
    fun placedPicturesFollowTheTextAndTheRestTheHandMadeOrder() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val entry = phone.repo.createNote("2026-10-14", "Поездка")
        val a = phone.add(entry, "A.jpg", 1)
        val b = phone.add(entry, "B.jpg", 2)
        val c = phone.add(entry, "C.jpg", 3)
        assertEquals(listOf("A.jpg", "B.jpg", "C.jpg"), phone.names(entry), "added order to start with")

        val body = Document(listOf(TextBlock("t", children = listOf(Span("Поездка"))), AttachmentBlock("p", c)))
        phone.repo.saveDraft(entry, DocumentCodec.encode(body))
        phone.repo.commitDraft(entry)
        assertEquals(listOf("C.jpg", "A.jpg", "B.jpg"), phone.names(entry), "the one in the text comes first")

        phone.repo.moveAttachment(entry, b, after = null, before = a)
        assertEquals(listOf("C.jpg", "B.jpg", "A.jpg"), phone.names(entry), "dragged B before A")

        phone.engine.sync()
        val desktop = Device(server, "desktop")
        desktop.engine.sync()
        assertEquals(listOf("C.jpg", "B.jpg", "A.jpg"), desktop.names(entry), "the order syncs")
        assertEquals(listOf("C.jpg", "B.jpg", "A.jpg"), desktop.repo.feed().first().single().media.map { it.meta.name }, "the feed agrees")
    }

    @Test
    fun aNameSyncsAndABlankOneGoesBackToTheDefault() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val entry = phone.repo.createNote("2026-10-14", "Голос")
        val a = phone.add(entry, "A.jpg", 1)
        suspend fun Device.caption() = repo.entry(entry).first()!!.attachments.single().caption

        phone.repo.setCaption(entry, a, "  Утро у моря ")
        assertEquals("Утро у моря", phone.caption(), "trimmed")
        phone.engine.sync()
        val desktop = Device(server, "desktop")
        desktop.engine.sync()
        assertEquals("Утро у моря", desktop.caption(), "the name syncs")

        desktop.repo.setCaption(entry, a, " ")
        desktop.engine.sync()
        phone.engine.sync()
        assertEquals(null, phone.caption(), "blank = the default name again")
    }
}
