package app.benbow.core.board

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The board's order keys (ADR-040): always strictly between, short when notes keep coming on top, moves as asked. */
class BoardRankTest {
    @Test
    fun keysComeBetweenTheirNeighbours() {
        assertEquals("a0", BoardRank.between(null, null))
        assertEquals("a1", BoardRank.between("a0", null))
        assertEquals("Zz", BoardRank.between(null, "a0"))
        assertEquals("a0V", BoardRank.between("a0", "a1"))
        assertEquals("a0l", BoardRank.between("a0V", "a1"))
        assertTrue(BoardRank.isValid("a0"))
        listOf(null, "", "a", "a00", "a0V0", "x-1", "zz").forEach { assertFalse(BoardRank.isValid(it), "$it") }
    }

    @Test
    fun tenThousandNotesOnTopKeepKeysShort() {
        var top = BoardRank.FIRST
        repeat(10_000) {
            val next = BoardRank.between(null, top)
            assertTrue(next < top && BoardRank.isValid(next), "$next before $top")
            top = next
        }
        assertTrue(top.length <= 4, top)
        var bottom = BoardRank.FIRST
        repeat(10_000) {
            val next = BoardRank.between(bottom, null)
            assertTrue(next > bottom && BoardRank.isValid(next), "$next after $bottom")
            bottom = next
        }
        assertTrue(bottom.length <= 4, bottom)
    }

    @Test
    fun randomInsertsStayInOrder() {
        val random = Random(7)
        val keys = mutableListOf(BoardRank.FIRST)
        repeat(3_000) {
            val at = random.nextInt(keys.size + 1)
            val key = BoardRank.between(keys.getOrNull(at - 1), keys.getOrNull(at))
            assertTrue(BoardRank.isValid(key), key)
            keys.add(at, key)
        }
        assertEquals(keys.sorted(), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun placingPutsTheNoteWhereAskedAndKeepsTheOthersInTheirOrder() {
        val random = Random(11)
        repeat(2_000) { round ->
            // A board as two devices may leave it: some notes without a key (or a foreign one), some keys equal.
            val pool = generateSequence(BoardRank.FIRST) { BoardRank.between(it, null) }.take(6).toList()
            val notes = List(random.nextInt(0, 9)) { i ->
                val key = when (random.nextInt(6)) {
                    0 -> null
                    1 -> "junk"
                    else -> pool[random.nextInt(pool.size)]
                }
                "n${random.nextInt(1000)}x$i" to key
            }
            val shown = BoardRank.sort(notes, { it.first }, { it.second })
            val moving = "m$round"
            val at = random.nextInt(shown.size + 1)
            val writes = BoardRank.place(moving, shown, at)
            val after = (shown + (moving to null)).map { (id, key) -> id to (writes[id] ?: key) }
            val expected = shown.map { it.first }.toMutableList().apply { add(at, moving) }
            assertEquals(expected, BoardRank.sort(after, { it.first }, { it.second }).map { it.first }, "round $round: $shown at $at")
            assertTrue(writes.values.all(BoardRank::isValid))
            // Only what had to change: the moved note, notes without a key, and equal keys pulled apart.
            val valid = shown.count { BoardRank.isValid(it.second) }
            if (valid == shown.size && shown.map { it.second }.distinct().size == shown.size) assertEquals(setOf(moving), writes.keys)
        }
    }
}
