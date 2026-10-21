package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun takenOffAttachmentsWaitInTheTrashTheLatestFirstThoseOfTrashedEntriesWithThem() = runTest {
        val live = repo.createEntry("2026-10-20")
        val gone = repo.createEntry("2026-10-19")
        val comment = repo.createComment(live, "2026-10-21", "ещё")
        val first = repo.attach(live, file(1), "image")
        val second = repo.attach(live, file(2), "image")
        val inComment = repo.attach(comment, file(3), "image")
        val withEntry = repo.attach(gone, file(4), "image")
        repo.removeAttachment(live, first)
        repo.removeAttachment(comment, inComment)
        repo.removeAttachment(live, second)
        repo.removeAttachment(gone, withEntry)
        repo.moveToTrash(gone)
        val off = repo.takenOff().first()
        assertEquals(listOf("IMG_2.jpg", "IMG_3.jpg", "IMG_1.jpg"), off.map { it.attachment.meta.name })
        assertEquals(listOf(live, live, live), off.map { it.postId })
        assertTrue(off.zipWithNext().all { (a, b) -> a.since!! > b.since!! })
        repo.restoreAttachment(live, second)
        assertEquals(listOf("IMG_3.jpg", "IMG_1.jpg"), repo.takenOff().first().map { it.attachment.meta.name })
    }

    @Test
    fun theTrashWeighsEachEntryWithItsCommentsEachFileOnce() = runTest {
        val post = repo.createEntry("2026-10-20")
        val comment = repo.createComment(post, "2026-10-20", "к ней")
        repo.attach(post, file(1), "image")
        repo.attach(post, file(1), "image") // the same file twice
        val off = repo.attach(post, file(2), "image")
        repo.removeAttachment(post, off) // taken off before: still the entry's
        repo.attach(comment, file(3), "image")
        val live = repo.createEntry("2026-10-21")
        val takenOff = repo.attach(live, file(4), "image")
        repo.removeAttachment(live, takenOff)
        // Before the post goes, both taken-off files wait in the trash on their own.
        assertEquals(TrashMedia(emptyMap(), 2_000 + 4_000), repo.mediaInTrash().first())
        repo.moveToTrash(post)
        val trash = repo.mediaInTrash().first()
        assertEquals(1_000L + 2_000 + 3_000, trash.entries.getValue(post).bytes)
        assertTrue(trash.entries.getValue(post).since != null)
        assertEquals(1_000L + 2_000 + 3_000 + 4_000, trash.bytes)
    }
}
