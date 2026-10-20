package app.benbow.client

import app.benbow.core.board.Board
import app.benbow.core.board.BoardRank
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Notes on the board (ADR-040): entries with the field `space` = board, so they keep everything an entry has
// (revisions, media, tags, colors) and never show in the journal's places, which read the journal_entry view.
// Moving between the journal and the board is that one field: the same id, history, media, tags and day; the board's
// own fields (place, pin, archive) lie dormant in the journal. Every change here is an LWW field of one note.

/** A note on the board, as its card needs it. */
data class BoardNote(val entry: EntrySummary, val rank: String?, val pinned: Boolean, val archived: Boolean) {
    val id get() = entry.id
}

/** The board as it shows: the pinned notes, then the others, each in board order ([BoardRank.sort]). */
data class BoardNotes(val pinned: List<BoardNote> = emptyList(), val others: List<BoardNote> = emptyList())

/** The board outside its archive. */
fun JournalRepository.board(): Flow<BoardNotes> = boardRows().map { notes ->
    val shown = notes.filter { !it.archived }
    BoardNotes(sorted(shown.filter { it.pinned }), sorted(shown.filter { !it.pinned }))
}

/** The board's archive, in board order. */
fun JournalRepository.boardArchive(): Flow<List<BoardNote>> = boardRows().map { notes -> sorted(notes.filter { it.archived }) }

/** A new note on top of the board (the quick bar): an entry of [kind] made on [day], on the board from the start. */
fun JournalRepository.createBoardNote(day: String, body: String = JournalRepository.emptyBody(), kind: String = "note"): String =
    db.transactionWithResult {
        val id = createEntry(day, kind, body)
        setField(id, Board.SPACE, Board.BOARD)
        placeOnBoard(id, 0)
        id
    }

/** From the journal to the board, on top of its part of the board (pinned or not, as it was); out of the archive. */
fun JournalRepository.moveToBoard(id: String) = db.transaction {
    val fields = decodeFields(q.selectEntry(id).executeAsOneOrNull()?.fields ?: return@transaction)
    if (Board.onBoard(fields)) return@transaction
    setField(id, Board.SPACE, Board.BOARD)
    if (fields[Board.ARCHIVED] == "true") setField(id, Board.ARCHIVED, null)
    placeOnBoard(id, 0)
}

/** From the board to the journal, on the day the note was made (owner); its board fields wait for it. */
fun JournalRepository.moveToJournal(id: String) {
    val fields = decodeFields(q.selectEntry(id).executeAsOneOrNull()?.fields ?: return)
    if (Board.onBoard(fields)) setField(id, Board.SPACE, null)
}

/**
 * Puts the note at [at] in its part of the board (the pinned notes or the others, as they show, without it): one key
 * written, or a few more when notes around it have none or two devices gave two the same ([BoardRank.place]).
 */
fun JournalRepository.placeOnBoard(id: String, at: Int) = db.transaction {
    val notes = q.boardNotes(::boardNote).executeAsList()
    val note = notes.firstOrNull { it.id == id } ?: return@transaction
    val part = sorted(notes.filter { it.id != id && it.archived == note.archived && it.pinned == note.pinned })
    BoardRank.place(id, part.map { it.id to it.rank }, at.coerceIn(0, part.size)).forEach { (note, rank) ->
        setField(note, Board.RANK, rank)
    }
}

/** Pinned on top of the board, or back among the others: on top of the part it goes to. */
fun JournalRepository.pinOnBoard(id: String, pinned: Boolean) = db.transaction {
    setField(id, Board.PIN, if (pinned) "true" else null)
    placeOnBoard(id, 0)
}

/** Into the board's archive (out of sight), or back from it on top of its part of the board. */
fun JournalRepository.archiveOnBoard(id: String, archived: Boolean) = db.transaction {
    setField(id, Board.ARCHIVED, if (archived) "true" else null)
    placeOnBoard(id, 0)
}

private fun JournalRepository.boardRows(): Flow<List<BoardNote>> = q.boardNotes(::boardNote).asFlow().mapToList(context)

private fun sorted(notes: List<BoardNote>) = BoardRank.sort(notes, { it.id }, { it.rank })

private fun boardNote(
    id: String,
    day: String,
    createdAt: Long,
    title: String,
    preview: String,
    conflictHeads: String,
    mediaCount: Long,
    cover: String?,
    fields: String,
) = decodeFields(fields).let { f ->
    BoardNote(
        summaryOf(id, day, createdAt, title, preview, conflictHeads, mediaCount, cover, fields),
        rank = f[Board.RANK],
        pinned = f[Board.PIN] == "true",
        archived = f[Board.ARCHIVED] == "true",
    )
}
