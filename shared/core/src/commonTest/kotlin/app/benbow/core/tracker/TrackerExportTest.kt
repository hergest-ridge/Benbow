package app.benbow.core.tracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Trackers readable without the app (ADR-050): a folder each, lossless JSON, CSVs for Excel in Russian. */
class TrackerExportTest {
    private val habits = TrackerRecord("t1", mapOf("name" to "Привычки", "rank" to "a1", "look_paper" to "dots"))
    private val sleep = TrackerRecord("t2", mapOf("name" to "Сон", "rank" to "a0"))
    private val trashed = TrackerRecord("t3", mapOf("name" to "Старый", "deleted" to "true"))
    private val options = ChoiceOption.write(listOf(ChoiceOption("good", "Хороший"), ChoiceOption("bad", "Плохой")))
    private val columns = listOf(
        column("walk", "t1", "a0", "type" to "check", "name" to "Прогулка"),
        column("water", "t1", "a1", "type" to "count", "name" to "Вода; стаканы"),
        column("day", "t1", "a2", "type" to "choice", "name" to "День", "options" to options),
        column("gone", "t1", "a3", "type" to "check", "name" to "Убрана", "deleted" to "true"),
        column("night", "t2", "a0", "type" to "time", "name" to "Сон", "day_by" to "end"),
        column("old", "t3", "a0", "type" to "check", "name" to "Старое"),
    )
    private val cells = listOf(
        cell("walk", "2026-10-26", "value" to "true", "note" to "в парке, \"долго\""),
        cell("water", "2026-10-26", "add:a" to "2", "add:b" to "1.5"),
        cell("day", "2026-10-27", "value" to "good"),
        cell("day", "2025-12-31", "value" to "someday"),
        cell("gone", "2026-10-25", "value" to "true"),
        cell("walk", "2026-10-24", "value" to null),
        cell("old", "2026-10-26", "value" to "true"),
    )
    private val spans = listOf(
        TrackerRecord("s1", mapOf("column" to "night", "start" to "2026-10-26T23:10:00", "end" to "2026-10-27T07:05:00")),
        TrackerRecord(
            "s2",
            mapOf("column" to "night", "start" to "2026-10-25T23:00:00", "end" to "2026-10-26T06:00:00", "deleted" to "true"),
        ),
    )

    private val files = TrackerExport.files(listOf(habits, sleep, trashed), columns, cells, spans).associate { it.path to it.text }

    @Test
    fun eachTrackerOutsideTheTrashHasAFolder() {
        assertEquals(
            listOf(
                "trackers/Сон/tracker.json",
                "trackers/Сон/2026.csv",
                "trackers/Сон/spans.csv",
                "trackers/Привычки/tracker.json",
                "trackers/Привычки/2025.csv",
                "trackers/Привычки/2026.csv",
            ),
            files.keys.toList(),
            "by rank; the one in the trash left out",
        )
    }

    @Test
    fun theJsonKeepsEveryRecordAsItIs() {
        val dump = Json.parseToJsonElement(files.getValue("trackers/Привычки/tracker.json")).jsonObject
        assertEquals("dots", dump.getValue("tracker").jsonObject.getValue("fields").jsonObject.getValue("look_paper").toString().trim('"'))
        assertEquals(4, dump.getValue("columns").jsonArray.size, "the column in the trash too")
        assertEquals(6, dump.getValue("cells").jsonArray.size, "cleared and trashed columns' cells too")
    }

    @Test
    fun aYearsCsvHasADayPerLineInWords() {
        val bom = Char(0xFEFF)
        assertEquals(
            "${bom}Дата;Прогулка;Прогулка: заметка;\"Вода; стаканы\";День\r\n" +
                "2026-10-26;да;\"в парке, \"\"долго\"\"\";3,5;\r\n" +
                "2026-10-27;;;;Хороший\r\n",
            files.getValue("trackers/Привычки/2026.csv"),
        )
        assertEquals(
            "${bom}Дата;Прогулка;Прогулка: заметка;\"Вода; стаканы\";День\r\n2025-12-31;;;;someday\r\n",
            files.getValue("trackers/Привычки/2025.csv"),
            "an unknown option shows as it is",
        )
    }

    @Test
    fun timeColumnsGiveTheDaysTimeAndTheirSpans() {
        val bom = Char(0xFEFF)
        assertEquals("${bom}Дата;Сон\r\n2026-10-27;7:55\r\n", files.getValue("trackers/Сон/2026.csv"))
        assertEquals(
            "${bom}Колонка;Начало;Конец;Длина;Заметка\r\nСон;2026-10-26T23:10:00;2026-10-27T07:05:00;7:55;\r\n",
            files.getValue("trackers/Сон/spans.csv"),
            "the span in the trash left out",
        )
    }

    @Test
    fun folderNamesAreSafeAndApart() {
        val taken = HashSet<String>()
        assertEquals("Сон_ мой", TrackerExport.folderName("Сон: мой", "id-1", taken))
        assertEquals("сон_ мой-000002", TrackerExport.folderName("сон: мой", "id-000002", taken), "the same name in another case")
        assertEquals("tracker", TrackerExport.folderName("...", "x", taken))
        assertTrue(TrackerExport.clock(59) == "0:01" && TrackerExport.clock(3600 * 25) == "25:00")
    }

    private fun column(id: String, tracker: String, rank: String, vararg fields: Pair<String, String>) =
        TrackerRecord(id, mapOf("tracker" to tracker, "rank" to rank) + fields)

    private fun cell(column: String, day: String, vararg fields: Pair<String, String?>) =
        TrackerRecord(TrackerCells.id(column, day), TrackerCells.create(column, day) + fields)
}
