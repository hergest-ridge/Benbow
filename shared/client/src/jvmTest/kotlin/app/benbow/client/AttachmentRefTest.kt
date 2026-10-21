package app.benbow.client

import app.benbow.client.db.Attachment_ref
import app.benbow.client.db.BenbowDb
import app.benbow.core.media.MediaEntry
import app.benbow.core.media.MediaRefs
import app.benbow.core.media.MediaRole
import app.benbow.core.media.MediaState
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.Hlc
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** attachment_ref (ADR-041): one row per attachment link, in step with entry.attachments whatever writes it. */
class AttachmentRefTest {
    private var time = 1_000L

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val repo = JournalRepository(db, { time++ })
        val engine = SyncEngine(db, { FakeApi(server) }, { time++ })

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }

        fun rows() = db.benbowQueries.attachmentRefs().executeAsList()
    }

    private fun file(n: Int, size: Long = 1_000L * n) = MediaFile(sha(n), size, "image/jpeg", "media/${sha(n)}", "IMG_$n.jpg", 500L + n)

    private fun sha(n: Int) = n.toString(16).padStart(64, '0')

    @Test
    fun rowsFollowEveryWriteOfAnEntrysAttachments() {
        val phone = Device(FakeServer(), "phone")
        val entry = phone.repo.createEntry("2026-10-20")
        val photo = phone.repo.attach(entry, file(1), "image", 4000, 3000, preview = file(2, 40))
        val voice = phone.repo.attach(entry, file(3), "voice", durationMs = 9_000)
        val rows = phone.rows()
        assertEquals(
            listOf(
                Attachment_ref(
                    entry_id = entry,
                    attachment_id = photo,
                    sha256 = sha(1),
                    kind = "image",
                    mime = "image/jpeg",
                    size = 1_000,
                    name = "IMG_1.jpg",
                    width = 4000,
                    height = 3000,
                    duration_ms = null,
                    captured_at = 501,
                    preview_sha256 = sha(2),
                    caption = null,
                    position = 1.0,
                    present = 1,
                    purged = 0,
                    changed_at = null,
                ),
                Attachment_ref(
                    entry_id = entry,
                    attachment_id = voice,
                    sha256 = sha(3),
                    kind = "voice",
                    mime = "image/jpeg",
                    size = 3_000,
                    name = "IMG_3.jpg",
                    width = null,
                    height = null,
                    duration_ms = 9_000,
                    captured_at = 503,
                    preview_sha256 = null,
                    caption = null,
                    position = 2.0,
                    present = 1,
                    purged = 0,
                    changed_at = null,
                ),
            ),
            rows.map { it.copy(changed_at = null) },
        )
        // When each link last changed is its clock's wall time: the later one later.
        assertTrue(rows[0].changed_at!! < rows[1].changed_at!!)
        phone.repo.removeAttachment(entry, photo)
        assertEquals(listOf(0L, 1L), phone.rows().map { it.present })
        phone.repo.restoreAttachment(entry, photo)
        phone.repo.setCaption(entry, voice, "у моря")
        phone.repo.moveAttachment(entry, voice, after = null, before = photo)
        assertEquals(listOf(voice to 1L, photo to 1L), phone.rows().map { it.attachment_id to it.present })
        phone.repo.moveToTrash(entry)
        assertEquals(2, phone.rows().size, "the trash is the entry's, joined from it")
        phone.db.benbowQueries.deleteAllEntries()
        assertEquals(emptyList(), phone.rows())
    }

    @Test
    fun anotherDeviceGetsTheSameRowsFromAPull() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val desktop = Device(server, "desktop")
        val entry = phone.repo.createEntry("2026-10-20")
        val photo = phone.repo.attach(entry, file(1), "image", 4000, 3000, preview = file(2, 40))
        phone.repo.attach(entry, file(3), "voice", durationMs = 9_000)
        phone.repo.removeAttachment(entry, photo)
        phone.engine.sync()
        desktop.engine.sync()
        assertEquals(phone.rows(), desktop.rows())
        assertEquals(2, desktop.rows().size)
    }

    @Test
    fun anUnreadableValueGivesNoRowAndNeverStopsAWrite() {
        val phone = Device(FakeServer(), "phone")
        val q = phone.db.benbowQueries
        val purged = AttachmentState(
            AttachmentMeta("c", sha(7), 70, "video/mp4", "video"),
            3.0,
            present = false,
            hlc = Hlc.ZERO,
            purged = true,
        )
        val good = ClientJson.encodeToString(attachmentsSerializer, mapOf("c" to purged))
        val mixed = good.dropLast(1) + ""","a":{"meta":{"id":"a"}},"b":5,"d":"x"}"""
        listOf("not json", "[]", "5", mixed).forEachIndexed { i, attachments ->
            q.upsertEntry("e$i", "journal", "2026-10-20", 1, "r", "{}", "", "", "", "[]", "{}", 1, "{}", attachments, 0, null)
        }
        assertEquals(listOf("e3" to "c"), phone.rows().map { it.entry_id to it.attachment_id })
        val row = phone.rows().single()
        assertEquals(Triple(sha(7), 0L, 1L), Triple(row.sha256, row.present, row.purged))
        assertEquals(4, q.allEntries().executeAsList().size, "every write went in")
    }

    @Test
    fun rowsAgreeWithMediaRefsAfterAnyWrites() {
        val random = Random(5)
        val phone = Device(FakeServer(), "phone")
        val entries = List(4) { phone.repo.createEntry("2026-10-2$it") }
        val links = mutableListOf<Pair<String, String>>()
        repeat(60) {
            val entry = entries.random(random)
            when (random.nextInt(5)) {
                0, 1 -> links += entry to phone.repo.attach(entry, file(random.nextInt(1, 6)), "image")
                2 -> links.randomOrNull(random)?.let { (e, a) -> phone.repo.removeAttachment(e, a) }
                3 -> links.randomOrNull(random)?.let { (e, a) -> phone.repo.restoreAttachment(e, a) }
                else -> if (random.nextBoolean()) phone.repo.moveToTrash(entry) else phone.repo.restore(entry)
            }
        }
        val refs = MediaRefs(
            phone.db.benbowQueries.allEntries().executeAsList().map {
                MediaEntry(it.id, decodeFields(it.fields)[TRASH_FIELD] == "true", null, decodeAttachments(it.attachments))
            },
        )
        val files = refs.refs.filter { it.role == MediaRole.File }
        assertEquals(links.size, phone.rows().size)
        assertEquals(
            files.map { Triple(it.entryId to it.attachmentId, it.sha256, it.state == MediaState.Removed) }.toSet(),
            phone.rows().map { Triple(it.entry_id to it.attachment_id, it.sha256, it.present == 0L) }.toSet(),
        )
    }
}
