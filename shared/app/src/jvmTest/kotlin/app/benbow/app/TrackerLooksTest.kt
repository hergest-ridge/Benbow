package app.benbow.app

import app.benbow.core.tracker.DayBy
import app.benbow.core.tracker.HourWindow
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerRecord
import app.benbow.ui.theme.HueToken
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's round on trackers (ADR-058): the sleep sheet's runs while a drag stretches them, what a right click
 * clears and puts back, columns' sizes, colors of one's own, and tokens this app doesn't know.
 */
class TrackerLooksTest {
    private val hours = listOf(false, true, true, false, false, true, false, false)

    @Test
    fun paintedHoursAreRunsAndADragStretchesOrCutsThem() {
        assertEquals(listOf(HourRun(1f, 3f), HourRun(5f, 6f)), runsOf(hours))
        assertEquals(emptyList(), runsOf(List(4) { false }))
        assertEquals(listOf(HourRun(0f, 4f)), runsOf(List(4) { true }))
        // Painting from an empty hour to the pointer half through another: it follows the pointer, and joins a neighbour.
        assertEquals(listOf(HourRun(1f, 4.5f), HourRun(5f, 6f)), dragRuns(hours, from = 3, at = 4.5f, erasing = false))
        assertEquals(listOf(HourRun(1f, 6f)), dragRuns(hours, from = 3, at = 5.2f, erasing = false), "two snakes become one")
        assertEquals(listOf(HourRun(0.4f, 3f), HourRun(5f, 6f)), dragRuns(hours, from = 1, at = 0.4f, erasing = false), "leftwards")
        // A drag that started on a painted hour cuts.
        assertEquals(listOf(HourRun(2.5f, 3f), HourRun(5f, 6f)), dragRuns(hours, from = 1, at = 2.5f, erasing = true))
        // Let go, the ends settle on whole hours as the spans are written.
        assertEquals(listOf(HourRun(1f, 6f)), droppedRuns(hours, from = 3, to = 4, erasing = false))
        assertEquals(listOf(HourRun(5f, 6f)), droppedRuns(hours, from = 2, to = 1, erasing = true))
        assertEquals(listOf(HourRun(0f, 8f)), droppedRuns(hours, from = 7, to = 0, erasing = false), "the whole day")
    }

    @Test
    fun aRightClickClearsADayAndKnowsWhatToPutBack() {
        val check = column("check")
        val count = column("count")
        val time = column("time")
        assertEquals(DayClear.Value("true"), dayClear(check, "2026-10-30", mapOf("value" to "true", "note" to "ok"), emptyList()))
        assertNull(dayClear(check, "2026-10-30", mapOf("note" to "only a note"), emptyList()), "a note isn't the column's value")
        assertEquals(DayClear.Count(5.0), dayClear(count, "2026-10-30", mapOf("add:a" to "3", "add:b" to "2"), emptyList()))
        assertNull(dayClear(count, "2026-10-30", mapOf("add:a" to "2", "add:b" to "-2"), emptyList()), "nothing to clear")
        val spans = listOf(span("n", "2026-10-29T23:00:00", "2026-10-30T07:00:00"), span("x", "2026-10-30T13:00:00", null))
        assertEquals(DayClear.Spans(listOf("n")), dayClear(time, "2026-10-30", null, spans), "the day's spans that have ended")
        assertNull(dayClear(column(null), "2026-10-30", mapOf("value" to "?"), emptyList()), "a type this app doesn't know")

        // A sleep row clears every span drawn in its hours, so it is blank after; the night over midnight goes whole.
        val starts = hourStarts(HourWindow.of("00:00-00:00")!!, LocalDate(2026, 10, 29), DayBy.End)
        val night =
            spans + span("old", "2026-10-28T23:00:00", "2026-10-29T06:00:00") +
                span("gone", "2026-10-29T10:00:00", "2026-10-29T11:00:00", deleted = true)
        assertEquals(DayClear.Spans(listOf("n", "old")), rowClear(night, starts))
        assertNull(rowClear(emptyList(), starts))
    }

    @Test
    fun columnsKeepTheirSizesAsMultiples() {
        assertEquals(1f, factorOf(null))
        assertEquals(1f, factorOf("wide"))
        assertEquals(1.5f, factorOf("1.5"))
        assertEquals(MAX_FACTOR, factorOf("100"))
        assertNull(factorText(1f), "the usual size clears the field")
        assertNull(factorText(1.004f))
        assertEquals("1.5", factorText(1.5f))
        assertEquals("2", factorText(2f))
        assertEquals("0.75", factorText(0.75f))
        // A border between a column (weight 1, 100 px) and a sleep column (12 hours at half a column, 600 px).
        val (a, b) = resizePair(1f, 1f, 100f, 1f, 6f, 600f, dx = 50f, least = 20f)
        assertEquals(1.5f, a, 0.001f)
        assertEquals(5.5f / 6f, b, 0.001f)
        assertEquals((1f + 6f), a * 1f + b * 6f, 0.001f, "their weights keep their sum: no other column moves")
        val (narrow, _) = resizePair(1f, 1f, 100f, 1f, 1f, 100f, dx = -95f, least = 24f)
        assertEquals(0.3f, narrow, 0.001f, "no narrower than the least")
        assertEquals(1.5f, resizeRow(1f, 40f, 20f, 16f), 0.001f)
        assertEquals(0.5f, resizeRow(1f, 40f, -30f, 20f), 0.001f, "no lower than the least")
    }

    @Test
    fun aColorOfOnesOwnIsAHueAndAChroma() {
        assertEquals(HueToken(210, 24), HueToken.of("hue:210:24"))
        assertEquals("hue:210:24", HueToken(210, 24).toString())
        assertNull(HueToken.of("fog"), "a note color's name")
        assertNull(HueToken.of("hue:400:10"))
        assertNull(HueToken.of("hue:10"))
        assertNull(HueToken.of("hue:10:20:30"))
        assertTrue(HuePalette.paper.flatten().all { HueToken.of(it) != null } && HuePalette.ink.flatten().all { HueToken.of(it) != null })
        assertTrue(HuePalette.paper.flatten().size >= 50, "many colors (owner)")
        // Its tone is the theme's: words read on the paper in either mode.
        val light = HueToken(210, 30).fill(dark = false)
        val dark = HueToken(210, 30).fill(dark = true)
        assertTrue(light.red + light.green + light.blue > dark.red + dark.green + dark.blue)
    }

    @Test
    fun unknownTokensDrawTheDefault() {
        assertEquals(MarkStyle.Soft, MarkStyle.of(null))
        assertEquals(MarkStyle.Soft, MarkStyle.of("crayon"), "a newer app's style")
        assertEquals(MarkStyle.Snake, MarkStyle.of("snake"))
        assertEquals(GridStyle.None, GridStyle.of("woven"))
        assertEquals(GridStyle.Dots, GridStyle.of("dots"))
        assertEquals(MarkStyle.entries.size, MarkStyle.entries.map { it.token }.toSet().size)
    }

    private fun column(type: String?) = TrackerColumn(TrackerRecord("c", mapOfNotNull("type" to type, "day_by" to "end")))

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>) = pairs.filter { it.second != null }.toMap()

    private fun span(id: String, start: String, end: String?, deleted: Boolean = false) = TrackerRecord(
        id,
        mapOf("column" to "c", "start" to start, "end" to end) + if (deleted) mapOf("deleted" to "true") else emptyMap(),
    )
}
