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

    fun onBoard(fields: Map<String, String?>): Boolean = fields[SPACE] == BOARD
}
