package app.benbow.core.board

/**
 * Notes on the board (ADR-040): entries with the LWW field [SPACE] = [BOARD]. Only entries without the field are the
 * journal's, so one with a space a later app knows never shows in the journal either. The board's own fields lie
 * dormant while a note is in the journal, so it comes back as it was.
 */
object Board {
    /** Where an entry lives: [BOARD], or absent for the journal. */
    const val SPACE = "space"
    const val BOARD = "board"

    /** The note's place on the board ([BoardRank]). */
    const val RANK = "board_rank"

    /** "true": among the pinned notes on top. */
    const val PIN = "board_pin"

    /** "true": in the board's archive, out of sight. */
    const val ARCHIVED = "board_archived"

    /** [JOURNAL]: the note came from the journal (its card says so, deleting it asks first). */
    const val FROM = "board_from"
    const val JOURNAL = "journal"

    fun onBoard(fields: Map<String, String?>): Boolean = fields[SPACE] == BOARD

    /** A note on the board that was a journal entry. */
    fun fromJournal(fields: Map<String, String?>): Boolean = onBoard(fields) && fields[FROM] == JOURNAL

    /** A journal entry that has been on the board: its `space` was set and is now gone (an LWW field set to none). */
    fun fromBoard(fields: Map<String, String?>): Boolean = SPACE in fields && fields[SPACE] == null
}
