package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.Purging
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A purge reaching a device (ADR-041): its copies go where nothing else holds them, the entry leaves every place. */
class PurgedOnDeviceTest {
    private var time = 1_000L
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ })
    private val server = FakeServer()
    private val media = MediaStore(Files.createTempDirectory("benbow-purged").toFile(), db, { FakeApi(server) }, { time++ })
    private val q = db.benbowQueries

    private suspend fun captured(seed: Int) = media.import(Random(seed).nextBytes(2_000).inputStream(), "image/jpeg", "IMG_$seed.jpg", null)

    /** What a pull of the server's purge brings: the entry's links purged and, when [whole], the entry marked. */
    private fun pullPurge(entryId: String, whole: Boolean) {
        val row = q.selectEntry(entryId).executeAsOne()
        val links = decodeAttachments(row.attachments)
        val purged = links.mapValues { (_, state) -> state.copy(present = false, purged = true, hlc = Hlc(time++, 0, "server")) }
        val fields = decodeFields(row.fields) + if (whole) mapOf(Purging.FIELD to "true") else emptyMap()
        q.upsertEntry(
            row.id, row.kind, row.day, row.created_at, row.head_rev, row.body, row.title, row.preview, row.card, row.conflict_heads,
            ClientJson.encodeToString(MapSerializer(String.serializer(), String.serializer().nullable), fields), time++, row.field_clocks,
            ClientJson.encodeToString(attachmentsSerializer, purged), 0, null,
        )
    }

    @Test
    fun copiesGoWhereNothingElseHoldsThemAndTheEntryLeavesTheTrash() = runTest {
        val gone = repo.createEntry("2026-10-20")
        val live = repo.createEntry("2026-10-21")
        val only = captured(1)
        val shared = captured(2)
        repo.attach(gone, only, "image")
        repo.attach(gone, shared, "image")
        repo.attach(live, shared, "image")
        repo.moveToTrash(gone)
        assertEquals(listOf(gone), repo.trash().first().map { it.id })

        pullPurge(gone, whole = true)
        assertEquals(2_000L, media.dropPurged())
        assertNull(media.localFile(only.sha256), "only purged links named it: gone here too")
        assertEquals(1L, media.pendingCount().first(), "never sent, and no row left waiting for a file that is gone")
        assertNotNull(media.localFile(shared.sha256), "a live entry still holds it")
        assertEquals(emptyList(), repo.trash().first(), "an entry deleted for good leaves the trash")
        assertTrue(repo.journal().first().none { it.id == gone })
        assertEquals(listOf(live), repo.library().first().map { it.postId })
        assertEquals(0L, media.dropPurged(), "nothing more")
    }

    @Test
    fun aFileNamedByTheTextOfALiveEntryStays() = runTest {
        val gone = repo.createEntry("2026-10-20")
        val picture = captured(3)
        repo.attach(gone, picture, "image")
        // A live entry's text names the same file (a link card's picture, say).
        repo.createEntry(
            "2026-10-21",
            body = """{"v":1,"blocks":[{"_key":"k","_type":"linkCard","href":"https://a.b","image":"${picture.sha256}"}]}""",
        )
        pullPurge(gone, whole = false)
        assertEquals(0L, media.dropPurged())
        assertNotNull(media.localFile(picture.sha256))
    }
}
