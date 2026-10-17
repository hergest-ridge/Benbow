package app.benbow.app

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import app.benbow.client.FeedKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The feed's date scrubber goes by posts (ADR-036 N3). */
class FeedScrubberTest {
    private val rows = feedRows(
        listOf(
            FeedKey("a", "2026-10-17", 5),
            FeedKey("b", "2026-10-02", 4),
            FeedKey("c", "2026-09-30", 3),
            FeedKey("d", "2025-12-31", 2),
            FeedKey("e", "2025-12-30", 1),
        ),
    )

    @Test
    fun eachPostHasItsRowItsMonthAndEachYearItsPlace() {
        val index = ScrubIndex(rows)
        assertEquals(5, index.size)
        assertContentEquals(listOf("2026-10", "2026-10", "2026-09", "2025-12", "2025-12"), index.monthOf.toList())
        assertEquals(listOf("a", "b", "c", "d", "e"), index.rowOf.map { rows[it].key }, "the rows of the posts, titles skipped")
        assertEquals(listOf("2026" to 0f, "2025" to 0.75f), index.years, "where each year's newest post is, as a share")
    }

    @Test
    fun aShareIsAPostAndBack() {
        val index = ScrubIndex(rows)
        assertEquals(0, index.posAt(0f))
        assertEquals(4, index.posAt(1f))
        assertEquals(2, index.posAt(0.5f))
        assertEquals(4, index.posAt(7f), "past the end: the last post")
        (0 until index.size).forEach { assertEquals(it, index.posAt(index.share(it))) }
        assertEquals(0f, ScrubIndex(emptyList()).share(0))
    }

    @Test
    fun aTickAtEachMonthAStrongerOneAtEachYear() {
        assertNull(scrubTick(null, "2026-10"), "the first month, as the drag starts")
        assertNull(scrubTick("2026-10", "2026-10"))
        assertEquals(HapticFeedbackType.SegmentFrequentTick, scrubTick("2026-10", "2026-09"))
        assertEquals(HapticFeedbackType.SegmentTick, scrubTick("2026-01", "2025-12"))
    }
}
