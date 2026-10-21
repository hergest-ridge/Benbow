package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/** The media library (ADR-041): what shows, in which order, with which entry and veil; taking out and back. */
class MediaLibraryTest {
    private var time = 1_000L
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ })

    private fun file(n: Int) = MediaFile(n.toString(16).padStart(64, '0'), 1_000L * n, "image/jpeg", "media/$n", "IMG_$n.jpg", null)

    private suspend fun shown() = repo.library().first().map { it.attachment.meta.name }

    @Test
    fun theNewestEntriesComeFirstEachInItsOwnOrder() = runTest {
        val old = repo.createEntry("2026-09-01")
        val new = repo.createEntry("2026-10-20")
        repo.attach(old, file(1), "image")
        repo.attach(new, file(2), "video")
        repo.attach(new, file(3), "voice")
        repo.attach(new, file(4), "drawing")
        repo.attach(new, file(5), "file")
        assertEquals(listOf("IMG_2.jpg", "IMG_3.jpg", "IMG_4.jpg", "IMG_5.jpg", "IMG_1.jpg"), shown())
        assertEquals(
            listOf(LibrarySection.Visual, LibrarySection.Sounds, LibrarySection.Drawings, LibrarySection.Files, LibrarySection.Visual),
            repo.library().first().map { it.section },
        )
        assertEquals(listOf("2026-10-20", "2026-09-01"), repo.library().first().map { it.day }.distinct())
    }

    @Test
    fun whatIsTakenOffOrInTheTrashIsNotThere() = runTest {
        val kept = repo.createEntry("2026-10-20")
        val gone = repo.createEntry("2026-10-19")
        val off = repo.attach(kept, file(1), "image")
        repo.attach(kept, file(2), "image")
        repo.attach(gone, file(3), "image")
        repo.removeAttachment(kept, off)
        repo.moveToTrash(gone)
        assertEquals(listOf("IMG_2.jpg"), shown())
    }

    @Test
    fun aCommentsMediaBelongToItsEntryAndLeaveWithIt() = runTest {
        val post = repo.createEntry("2026-10-01")
        val comment = repo.createComment(post, "2026-10-20", "смотри")
        repo.attach(comment, file(1), "image")
        val item = repo.library().first().single()
        assertEquals(Triple(comment, post, "2026-10-01"), Triple(item.entryId, item.postId, item.day))
        repo.moveToTrash(post)
        assertEquals(emptyList(), shown())
    }

    @Test
    fun itemsCarryTheirEntrysVeilAndPlace() = runTest {
        val quiet = repo.createEntry("2026-10-20")
        val note = repo.createEntry("2026-10-19")
        repo.attach(quiet, file(1), "image")
        repo.attach(note, file(2), "image")
        repo.setVeil(quiet, Veil.Quiet)
        repo.moveToBoard(note)
        val (first, second) = repo.library().first()
        assertEquals(Veil.Quiet to false, first.veil to first.board)
        assertEquals(null to true, second.veil to second.board)
    }

    @Test
    fun severalComeOffTogetherAndComeBack() = runTest {
        val entry = repo.createEntry("2026-10-20")
        repo.attach(entry, file(1), "image")
        repo.attach(entry, file(2), "image")
        val keys = repo.library().first().map { it.key }
        repo.removeAttachments(keys)
        assertEquals(emptyList(), shown())
        repo.restoreAttachments(keys)
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), shown())
    }
}
