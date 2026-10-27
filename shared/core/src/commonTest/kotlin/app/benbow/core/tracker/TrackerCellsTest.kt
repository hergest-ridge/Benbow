package app.benbow.core.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A day's cell (ADR-050): one id on every device, a count summed from each device's own part. */
class TrackerCellsTest {
    @Test
    fun aDaysCellHasOneIdEverywhere() {
        assertEquals("water:2026-10-27", TrackerCells.id("water", "2026-10-27"))
        assertEquals(mapOf("column" to "water", "day" to "2026-10-27"), TrackerCells.create("water", "2026-10-27"))
    }

    @Test
    fun aCountIsItsDevicesPartsAndATapWritesOnlyItsOwn() {
        var cell = mapOf<String, String?>("column" to "water", "add:phone" to "3", "add:pc" to "2")
        assertEquals(5.0, TrackerCells.count(cell))
        assertEquals(mapOf("add:phone" to "4"), TrackerCells.add(cell, "phone", 1.0))
        assertEquals(mapOf("add:web" to "1"), TrackerCells.add(cell, "web", 1.0), "a device's first tap")
        cell = cell + TrackerCells.setTotal(cell, "phone", 8.0)
        assertEquals("6", cell["add:phone"], "8 in all: the phone's part is 8 less the pc's 2")
        assertEquals(8.0, TrackerCells.count(cell))
        assertEquals(mapOf("add:pc" to "-2"), TrackerCells.setTotal(cell, "pc", 4.0), "lowering it is a part below zero")
        assertEquals(0.0, TrackerCells.count(mapOf("add:phone" to "not a number", "value" to "7")))
    }

    @Test
    fun markedFollowsTheColumnsType() {
        assertTrue(TrackerCells.marked(ColumnType.Check, mapOf("value" to "true")))
        assertFalse(TrackerCells.marked(ColumnType.Check, mapOf("value" to null)))
        assertTrue(TrackerCells.marked(ColumnType.Count, mapOf("add:a" to "1", "add:b" to "-0.5")))
        assertFalse(TrackerCells.marked(ColumnType.Count, mapOf("add:a" to "1", "add:b" to "-1")), "taps taken back")
        assertTrue(TrackerCells.marked(ColumnType.Scale, mapOf("value" to "3")))
        assertTrue(TrackerCells.marked(ColumnType.Choice, mapOf("value" to "good")))
        assertFalse(TrackerCells.marked(ColumnType.Note, mapOf("value" to "  ")))
        assertFalse(TrackerCells.marked(null, mapOf("value" to "true")), "a type this app doesn't know")
        assertNull(ColumnType.of("habit"))
    }

    @Test
    fun numbersAreWrittenPlainly() {
        assertEquals("3", TrackerCells.number(3.0))
        assertEquals("2.5", TrackerCells.number(2.5))
        assertEquals("0.3", TrackerCells.number(0.1 + 0.2))
        assertEquals("-1.25", TrackerCells.number(-1.25))
        assertEquals("0", TrackerCells.number(-0.0001))
        assertEquals("12345678.5", TrackerCells.number(12_345_678.5), "never in exponent form")
    }

    @Test
    fun aColumnReadsItsSettingsAndKeepsWhatItDoesntKnow() {
        val options = """[{"id":"good","name":"Хороший","color":"green","icon":"emoji:🙂","later":1},{"id":""},{"id":"good"}]"""
        val column = TrackerColumn(
            TrackerRecord(
                "day",
                mapOf("type" to "choice", "options" to options, "goal" to "3", "goal_per" to "week", "hours" to "21:00-10:00", "x" to "y"),
            ),
        )
        assertEquals(listOf(ChoiceOption("good", "Хороший", "green", "emoji:🙂")), column.options, "blank and repeated ids left out")
        assertEquals(3.0, column.goal)
        assertEquals(GoalPer.Week, column.goalPer)
        assertEquals(listOf(21, 22, 23, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9), column.hours?.hours)
        assertEquals("y", column.record["x"])
        assertEquals(emptyList(), ChoiceOption.list("not json"))
        assertEquals(1 to 5, column.min to column.max, "a rating's range by default")
        assertEquals(DayBy.Start, column.dayBy)
        assertEquals("18:30-12:00", HourWindow.of("18:30-12:00").toString())
        assertNull(HourWindow.of("25:00-07:00"))
        assertEquals(18, HourWindow.of("18:30-12:00")?.hours?.size, "a part of an hour takes a cell")
    }
}
