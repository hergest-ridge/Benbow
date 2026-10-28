package app.benbow.app

import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.DayBy
import app.benbow.core.tracker.HourWindow
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerFigure
import app.benbow.core.tracker.TrackerRecord
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The trackers' sheet (ADR-050, B4): what a tap writes by type, how full a cell is, the foot's words, the sleep sheet. */
class TrackerSheetLogicTest {
    private val columns = TrackerPresets.habits.columns.mapIndexed { i, fields -> TrackerColumn(TrackerRecord("c$i", fields)) }
    private val check = columns[0]
    private val water = columns[3]
    private val energy = columns[5]
    private val day = columns[6]

    @Test
    fun aTapDependsOnTheType() {
        assertEquals(CellTap.Set("true"), tapOf(check, null))
        assertEquals(CellTap.Set(null), tapOf(check, mapOf("value" to "true")), "a second tap takes it off")
        assertEquals(CellTap.Add, tapOf(water, mapOf("add:a" to "3")))
        assertEquals(CellTap.Pick, tapOf(energy, mapOf("value" to "3")))
        assertEquals(listOf("1", "2", "3", "4", "5"), pickValues(energy))
        assertEquals(listOf("hard", "plain", "good", "great"), pickValues(day))
        assertEquals(CellTap.Ask, tapOf(TrackerColumn(TrackerRecord("n", mapOf("type" to "note"))), null))
        assertEquals(CellTap.None, tapOf(TrackerColumn(TrackerRecord("x", mapOf("type" to "habit"))), null), "a type this app doesn't know")
    }

    @Test
    fun aCellFillsByItsType() {
        assertEquals(0.5f, water.fill(mapOf("add:a" to "2", "add:b" to "2")), "four of eight glasses")
        assertEquals(1f, water.fill(mapOf("add:a" to "9")))
        assertEquals(0.6f, energy.fill(mapOf("value" to "3")))
        assertEquals(1f, day.fill(mapOf("value" to "good")))
        assertEquals(0f, day.fill(mapOf("value" to "gone")), "a value that is no option draws nothing (and stays)")
    }

    @Test
    fun theFootSpeaksPlainly() {
        assertEquals("12", figureText(check, TrackerFigure.Days(12)))
        assertEquals("7,5", figureText(water, TrackerFigure.Sum(7.5)))
        assertEquals("3,4", figureText(energy, TrackerFigure.Average(3.36)))
        assertEquals("🙂 9", figureText(day, TrackerFigure.Top("good", 9)))
        assertEquals("7:20", figureText(check, TrackerFigure.Time(7 * 3600 + 20 * 60, 0)))
        assertEquals("", figureText(check, null))
    }

    @Test
    fun aNightsHoursBelongToTheDayOneWakes() {
        val window = HourWindow.of("21:00-11:00")!!
        val hours = hourStarts(window, LocalDate(2026, 10, 27), DayBy.End)
        assertEquals(14, hours.size)
        assertEquals(LocalDateTime(2026, 10, 26, 21, 0), hours.first(), "the evening is the day before")
        assertEquals(LocalDateTime(2026, 10, 27, 0, 0), hours[3])
        assertEquals(LocalDateTime(2026, 10, 27, 10, 0), hours.last())
        val byStart = hourStarts(window, LocalDate(2026, 10, 27), DayBy.Start)
        assertEquals(LocalDateTime(2026, 10, 27, 21, 0), byStart.first())
        assertEquals(LocalDateTime(2026, 10, 28, 10, 0), byStart.last(), "or the morning is the day after")
        assertEquals(
            LocalDateTime(2026, 10, 27, 8, 0),
            hourStarts(HourWindow.of("08:00-20:00")!!, LocalDate(2026, 10, 27), DayBy.End).first(),
        )
        // The sleep sheet's whole day (owner): from midnight, all of it the day itself, whichever day a span counts for.
        for (by in DayBy.entries) {
            val day = hourStarts(HourWindow.of("00:00-00:00")!!, LocalDate(2026, 10, 27), by)
            assertEquals(24, day.size)
            assertEquals(LocalDateTime(2026, 10, 27, 0, 0), day.first())
            assertEquals(LocalDateTime(2026, 10, 27, 23, 0), day.last())
        }
        assertEquals("00:00-00:00", TrackerPresets.sleep.columns.first()[ColumnField.HOURS])
        // A whole day from another hour still crosses midnight.
        val evening = hourStarts(HourWindow.of("18:00-18:00")!!, LocalDate(2026, 10, 27), DayBy.End)
        assertEquals(LocalDateTime(2026, 10, 26, 18, 0), evening.first())
        assertEquals(LocalDateTime(2026, 10, 27, 17, 0), evening.last())
    }

    @Test
    fun aTapOnAnHourPaintsOrClearsItInTheSpans() {
        val night = span("s1", "2026-10-26T23:00:00", "2026-10-27T07:00:00")
        val spans = listOf(night)
        assertTrue(covered(spans, LocalDateTime(2026, 10, 27, 3, 0)))
        assertEquals(listOf(SpanEdit.Add("2026-10-26T21:00:00", "2026-10-26T22:00:00")), hourTap(spans, LocalDateTime(2026, 10, 26, 21, 0)))
        assertEquals(
            listOf(change("s1", "start", "2026-10-26T22:00:00")),
            hourTap(spans, LocalDateTime(2026, 10, 26, 22, 0)),
            "joins the night",
        )
        assertEquals(listOf(change("s1", "end", "2026-10-27T08:00:00")), hourTap(spans, LocalDateTime(2026, 10, 27, 7, 0)))
        assertEquals(
            listOf(change("s1", "start", "2026-10-27T00:00:00")),
            hourTap(spans, LocalDateTime(2026, 10, 26, 23, 0)),
            "its first hour off",
        )
        assertEquals(
            listOf(change("s1", "end", "2026-10-27T06:00:00")),
            hourTap(spans, LocalDateTime(2026, 10, 27, 6, 0)),
            "its last hour off",
        )
        assertEquals(
            listOf(change("s1", "end", "2026-10-27T03:00:00"), SpanEdit.Add("2026-10-27T04:00:00", "2026-10-27T07:00:00")),
            hourTap(spans, LocalDateTime(2026, 10, 27, 3, 0)),
            "awake at three: two spans",
        )
        val split = listOf(span("a", "2026-10-26T23:00:00", "2026-10-27T03:00:00"), span("b", "2026-10-27T04:00:00", "2026-10-27T07:00:00"))
        assertEquals(
            listOf(change("a", "end", "2026-10-27T07:00:00"), SpanEdit.Trash("b")),
            hourTap(split, LocalDateTime(2026, 10, 27, 3, 0)),
        )
        assertEquals(
            listOf(SpanEdit.Trash("x")),
            hourTap(listOf(span("x", "2026-10-27T05:00:00", "2026-10-27T06:00:00")), LocalDateTime(2026, 10, 27, 5, 0)),
        )
        val trashed = span("t", "2026-10-27T05:00:00", "2026-10-27T06:00:00").let { it.copy(fields = it.fields + ("deleted" to "true")) }
        assertEquals(
            listOf(SpanEdit.Add("2026-10-27T05:00:00", "2026-10-27T06:00:00")),
            hourTap(listOf(trashed), LocalDateTime(2026, 10, 27, 5, 0)),
        )
    }

    @Test
    fun wordsAndPresetsReadAsTheAppWritesThem() {
        assertEquals("2,5", countText(2.5))
        assertEquals("4", decimal(4.0))
        assertEquals("0:01", clockText(59))
        assertEquals("🌱", TrackerPresets.habits.tracker["icon"]?.let(::iconEmoji))
        assertEquals(null, iconEmoji("drawn:leaf"))
        assertEquals("2026-10-27T07:05:00", timeText(LocalDateTime(2026, 10, 27, 7, 5).let { seconds(it) }))
        for (preset in TrackerPresets.all) {
            assertTrue(preset.columns.all { ColumnType.of(it["type"]) != null }, "${preset.id}: every column has a type this app knows")
        }
        assertEquals(4, day.options.size)
        assertEquals("🌱 Привычки", trackerTitle(TrackerRecord("t", TrackerPresets.habits.tracker), "Без названия"))
        assertEquals("Без названия", trackerTitle(TrackerRecord("t", mapOf("name" to " ")), "Без названия"))
        assertEquals("Вода", water.short)
        assertEquals(null, water.sheetHours)
        assertEquals(
            mapOf("look_paper" to "lines", "look_light" to null),
            trackerLook(mapOf("paper" to "lines", "light" to null, "font" to "serif")),
            "only the layers the app sets",
        )
        assertEquals(mapOf("look_pattern" to null), trackerLook(mapOf("pattern" to "<svg>")), "anything but a token takes it off")
        assertEquals(31, monthDays(LocalDate(2026, 10, 27)).size)
        assertEquals(LocalDate(2026, 2, 28), monthDays(LocalDate(2026, 2, 1)).last())
        assertEquals(24, TrackerColumn(TrackerRecord("s", TrackerPresets.sleep.columns.first())).hours?.hours?.size)
    }

    private fun span(id: String, start: String, end: String) = TrackerRecord(id, mapOf("column" to "sleep", "start" to start, "end" to end))

    private fun change(id: String, field: String, value: String) = SpanEdit.Change(id, mapOf(field to value))

    private fun seconds(time: LocalDateTime) = time.toInstantSeconds()

    private fun LocalDateTime.toInstantSeconds(): Long = (date.toEpochDays().toLong() * 86_400) + hour * 3600 + minute * 60 + second
}
