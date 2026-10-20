package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.board.Board
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
import kotlin.test.assertTrue

/** Notes on the board (ADR-040): never in the journal's places, moving both ways without losing anything, in order. */
class BoardTest {
    private var time = 1_000L

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val repo = JournalRepository(db, { time++ }, today = { TODAY })
        val engine = SyncEngine(db, { FakeApi(server) }, { time++ })
        val media = MediaStore(Files.createTempDirectory("benbow-board-$name").toFile(), db, { FakeApi(server) }, { time++ })

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }
    }

    /** One of everything the journal has places for, all on the board: none of it may show in the journal. */
    @Test
    fun aNoteOnTheBoardShowsInNoneOfTheJournalsPlaces() = runTest {
        val phone = Device(FakeServer(), "phone")
        val repo = phone.repo
        val kept = repo.createNote(TODAY, "Дневник: смородина")
        val note = repo.createBoardNote(LAST_YEAR, JournalRepository.textBody("Записка: смородина"))
        val tag = repo.addTag(kept, "дача")!!
        listOf(kept, note).forEach { id ->
            repo.setFavorite(id, true)
            repo.setPinned(id, true)
            repo.addTag(id, "дача")
            repo.setVeilUntil(id, TODAY)
        }
        repo.setField(note, "mood", "2")
        repo.saveDraft(note, JournalRepository.textBody("Записка: смородина, черная"))
        repo.saveDraft(kept, JournalRepository.textBody("Дневник: смородина, красная"))

        fun List<EntrySummary>.ids() = map { it.id }
        assertEquals(listOf(kept), repo.journal().first().ids())
        assertEquals(emptyList(), repo.entriesForDay(LAST_YEAR).first().ids())
        assertEquals(listOf(kept), repo.pinned().first().ids())
        assertEquals(listOf(kept), repo.withDrafts().first().ids())
        assertEquals(emptyList(), repo.onThisDay(TODAY).first().ids())
        assertEquals(listOf(kept), repo.favoritesUpTo(TODAY).first().ids())
        assertEquals(1, repo.daysWritten(LAST_YEAR, TODAY).first())
        assertEquals(listOf(TODAY), repo.monthDays(LAST_YEAR, TODAY).first().keys.toList())
        assertEquals(listOf(TODAY), repo.entryDays().first())
        assertEquals(listOf(kept), repo.feedIndex().first().map { it.id })
        assertEquals(listOf(kept), repo.returned(TODAY).first().ids())
        assertEquals(listOf(kept), repo.search(SearchQuery("смородина")).first().ids())
        assertEquals(listOf(kept), repo.search(SearchQuery(tag = tag)).first().ids())
        assertEquals(listOf(1), repo.tags().first().filter { it.name == "дача" }.map { it.uses })
        val stats = repo.homeStats(LAST_YEAR, TODAY, LAST_YEAR, TODAY, gentle = false).first()
        assertEquals(1, stats.days)
        assertEquals(TODAY, stats.since, "the journal began today, whatever the board holds")

        // And on the board it is.
        assertEquals(listOf(note), repo.board().first().others.map { it.id })
    }

    @Test
    fun movingBothWaysKeepsTheEntryWholeAndSyncs() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val id = phone.repo.createNote("2026-03-03", "Купить ниток")
        phone.repo.addTag(id, "рукоделие")
        phone.repo.attach(id, phone.media.import(Random(1).nextBytes(200).inputStream(), "image/jpeg", "A.jpg", null), "image")
        phone.repo.saveDraft(id, JournalRepository.textBody("Купить ниток и пуговиц"))
        phone.repo.commitDraft(id)
        val before = phone.repo.entry(id).first()!!

        phone.repo.moveToBoard(id)
        val onBoard = phone.repo.entry(id).first()!!
        assertEquals(before.headRevision, onBoard.headRevision, "no new revision: only a field moved")
        assertEquals(before.day, onBoard.day)
        assertEquals(before.attachments, onBoard.attachments)
        assertEquals(before.tags, onBoard.tags)
        assertEquals(Board.BOARD, onBoard.fields[Board.SPACE])
        assertEquals(emptyList(), phone.repo.journal().first())

        // Another device sees it on the board, never in its journal.
        phone.engine.sync()
        val desktop = Device(server, "desktop")
        desktop.engine.sync()
        assertEquals(emptyList(), desktop.repo.journal().first())
        assertEquals(listOf(id), desktop.repo.board().first().others.map { it.id })

        // Back to the journal, on the day it was made; then the desktop follows.
        desktop.repo.moveToJournal(id)
        assertEquals(listOf("2026-03-03"), desktop.repo.journal().first().map { it.day })
        assertEquals(BoardNotes(), desktop.repo.board().first())
        desktop.engine.sync()
        phone.engine.sync()
        val back = phone.repo.entry(id).first()!!
        assertEquals(before.headRevision, back.headRevision)
        assertEquals(before.attachments, back.attachments)
        assertEquals(listOf(id), phone.repo.journal().first().map { it.id })
        assertTrue(back.fields[Board.RANK] != null, "its place on the board waits for it")
    }

    @Test
    fun newNotesComeOnTopAndMovesPinsAndTheArchiveKeepOrder() = runTest {
        val repo = Device(FakeServer(), "phone").repo
        val (a, b, c) = listOf("A", "B", "C").map { repo.createBoardNote(TODAY, JournalRepository.textBody(it)) }
        fun titles(notes: List<BoardNote>) = notes.map { it.entry.title }
        assertEquals(listOf("C", "B", "A"), titles(repo.board().first().others), "the newest on top")

        repo.placeOnBoard(c, 2)
        assertEquals(listOf("B", "A", "C"), titles(repo.board().first().others))
        repo.placeOnBoard(a, 0)
        assertEquals(listOf("A", "B", "C"), titles(repo.board().first().others))

        repo.pinOnBoard(b, true)
        assertEquals(listOf("B"), titles(repo.board().first().pinned))
        assertEquals(listOf("A", "C"), titles(repo.board().first().others))

        repo.archiveOnBoard(a, true)
        assertEquals(listOf("C"), titles(repo.board().first().others))
        assertEquals(listOf("A"), titles(repo.boardArchive().first()))
        repo.archiveOnBoard(a, false)
        assertEquals(listOf("A", "C"), titles(repo.board().first().others), "back in its place")
        repo.placeOnBoard(a, 1)
        repo.archiveOnBoard(a, true)
        repo.archiveOnBoard(a, false)
        assertEquals(listOf("C", "A"), titles(repo.board().first().others), "back in its place, not on top")
        repo.placeOnBoard(a, 0)

        // In the journal and back again: on top, out of the archive.
        repo.archiveOnBoard(c, true)
        repo.moveToJournal(c)
        repo.moveToBoard(c)
        assertEquals(listOf("C", "A"), titles(repo.board().first().others))
        assertEquals(emptyList(), repo.boardArchive().first())
    }

    @Test
    fun ticksOnACardFromTwoDevicesMergeWithoutAConflict() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val body = DocumentCodec.encode(
            Document(
                listOf(TextBlock("t", children = listOf(Span("Продукты")))) +
                    listOf("молоко", "хлеб", "сыр").mapIndexed { i, item ->
                        TextBlock("b$i", listItem = "check", level = 1, checked = false, children = listOf(Span(item)))
                    },
            ),
        )
        val id = phone.repo.createBoardNote(TODAY, body)
        phone.engine.sync()
        val desktop = Device(server, "desktop")
        desktop.engine.sync()

        // Both tick a box while apart (neighbouring ones: one run of changed blocks for diff3).
        assertTrue(phone.repo.tickOnBoard(id, "b0", true))
        assertTrue(desktop.repo.tickOnBoard(id, "b1", true))
        phone.engine.sync()
        desktop.engine.sync()
        phone.engine.sync()
        listOf(phone, desktop).forEach { device ->
            val entry = device.repo.entry(id).first()!!
            assertEquals(emptyList(), entry.conflictHeads, "no conflict")
            val boxes = DocumentCodec.decode(entry.body).blocks.drop(1).map { (it as TextBlock).checked }
            assertEquals(listOf(true, true, false), boxes)
        }
        assertEquals(2, phone.repo.board().first().others.single().card.ticked)
        assertEquals(listOf("сыр"), phone.repo.board().first().others.single().card.lines.map { it.text })
    }

    private companion object {
        const val TODAY = "2026-10-20"
        const val LAST_YEAR = "2025-10-20"
    }
}
