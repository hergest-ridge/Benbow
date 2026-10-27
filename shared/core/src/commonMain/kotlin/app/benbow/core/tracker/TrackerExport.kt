package app.benbow.core.tracker

import app.benbow.core.LocalDays
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Trackers as files readable without the app (ADR-050): a folder per tracker outside the trash with `tracker.json`
 * (the tracker, its columns, cells and spans with every field as the store keeps them: lossless), a CSV per year with
 * a line per day that has anything, and `spans.csv` with its time columns' spans. The CSVs are for Excel in Russian:
 * `;` between values, a decimal comma, UTF-8 with a BOM. Used by the server's mirror and the app's ZIP.
 */
object TrackerExport {
    data class File(val path: String, val text: String)

    @Serializable
    private data class Dump(
        val tracker: TrackerRecord,
        val columns: List<TrackerRecord>,
        val cells: List<TrackerRecord>,
        val spans: List<TrackerRecord>,
    )

    private val json = Json { prettyPrint = true }

    const val FOLDER = "trackers"
    const val JSON_FILE = "tracker.json"
    const val SPANS_FILE = "spans.csv"

    /** The files of every tracker outside the trash, under [FOLDER]. */
    fun files(
        trackers: List<TrackerRecord>,
        columns: List<TrackerRecord>,
        cells: List<TrackerRecord>,
        spans: List<TrackerRecord>,
    ): List<File> {
        val taken = HashSet<String>()
        val columnsOf = columns.groupBy { it[ColumnField.TRACKER] }
        val cellsOf = cells.groupBy { it[CellField.COLUMN] }
        val spansOf = spans.groupBy { it[SpanField.COLUMN] }
        return trackers.filter { !it.deleted }.sortedWith(TrackerRecord.byRank).flatMap { tracker ->
            val own = columnsOf[tracker.id].orEmpty().sortedWith(TrackerRecord.byRank)
            val ownCells = own.flatMap { cellsOf[it.id].orEmpty() }.sortedBy { it.id }
            val ownSpans = own.flatMap { spansOf[it.id].orEmpty() }.sortedBy { it.id }
            val folder = FOLDER + "/" + folderName(tracker[TrackerField.NAME].orEmpty(), tracker.id, taken)
            val shown = own.filter { !it.deleted }.map(::TrackerColumn)
            val dump = json.encodeToString(Dump.serializer(), Dump(tracker, own, ownCells, ownSpans))
            listOf(File("$folder/$JSON_FILE", dump)) +
                years(shown, ownCells, ownSpans).map { (year, text) -> File("$folder/$year.csv", text) } +
                listOfNotNull(spansCsv(shown, ownSpans)?.let { File("$folder/$SPANS_FILE", it) })
        }
    }

    /**
     * A CSV per year: `Дата`, then a column per column in its order (a note column beside it when any of its cells has
     * a note); a line per day that has anything, oldest first. Columns in the trash are left out (the JSON has them).
     */
    internal fun years(columns: List<TrackerColumn>, cells: List<TrackerRecord>, spans: List<TrackerRecord>): List<Pair<String, String>> {
        val ids = columns.map { it.id }.toSet()
        val cellOf = cells.filter { it[CellField.COLUMN] in ids }.associateBy { it[CellField.COLUMN] to it[CellField.DAY] }
        val spansOf = spans.filter { !it.deleted }.groupBy { it[SpanField.COLUMN] }
        val noted = cellOf.values.filter { !it[CellField.NOTE].isNullOrBlank() }.mapNotNull { it[CellField.COLUMN] }.toSet()
        val days = (
            cellOf.values.filter(::hasAnything).mapNotNull { it[CellField.DAY] } +
                columns.filter {
                    it.type == ColumnType.Time
                }.flatMap { c -> spansOf[c.id].orEmpty().mapNotNull { TrackerSpans.dayOf(it, c.dayBy) } }
            ).filter { LocalDays.parse(it) != null }.distinct().sorted()
        val header =
            listOf("Дата") + columns.flatMap { c -> listOf(c.name) + if (c.id in noted) listOf("${c.name}: заметка") else emptyList() }
        return days.groupBy { it.take(4) }.map { (year, inYear) ->
            year to csv(
                listOf(header) + inYear.map { day ->
                    listOf(day) + columns.flatMap { c ->
                        val fields = cellOf[c.id to day]?.fields
                        listOf(text(c, fields, spansOf[c.id].orEmpty(), day)) +
                            if (c.id in noted) listOf(fields?.get(CellField.NOTE).orEmpty()) else emptyList()
                    }
                },
            )
        }
    }

    /** `spans.csv`: every span of the time columns, by its start; null when there are none. */
    internal fun spansCsv(columns: List<TrackerColumn>, spans: List<TrackerRecord>): String? {
        val names = columns.filter { it.type == ColumnType.Time }.associate { it.id to it.name }
        val rows = spans.filter { !it.deleted && it[SpanField.COLUMN] in names }
            .sortedWith(compareBy({ it[SpanField.START].orEmpty() }, { it.id }))
            .map { span ->
                listOf(
                    names[span[SpanField.COLUMN]].orEmpty(),
                    span[SpanField.START].orEmpty(),
                    span[SpanField.END].orEmpty(),
                    TrackerSpans.seconds(span)?.let(::clock).orEmpty(),
                    span[SpanField.NOTE].orEmpty(),
                )
            }
        return if (rows.isEmpty()) null else csv(listOf(listOf("Колонка", "Начало", "Конец", "Длина", "Заметка")) + rows)
    }

    /** A cell in words: «да», a count with a decimal comma, a rating, an option's name, a note, a day's time as "7:30". */
    internal fun text(column: TrackerColumn, fields: Map<String, String?>?, spans: List<TrackerRecord>, day: String): String {
        val value = fields?.get(CellField.VALUE)
        return when (column.type) {
            ColumnType.Check -> if (value == "true") "да" else ""

            ColumnType.Count -> fields?.let(TrackerCells::count)?.takeIf {
                it != 0.0
            }?.let { TrackerCells.number(it).replace('.', ',') }.orEmpty()

            ColumnType.Choice -> value?.let { id -> column.options.find { it.id == id }?.name?.ifBlank { null } ?: id }.orEmpty()

            ColumnType.Time -> TrackerSpans.total(spans, day, column.dayBy).takeIf { it > 0 }?.let(::clock).orEmpty()

            ColumnType.Scale, ColumnType.Note, null -> value.orEmpty()
        }
    }

    /** "7:30" for seconds, to the nearest minute. */
    internal fun clock(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
    }

    /** "Привычки": safe on Windows, Android and Linux; a second tracker with the same name gets its id's end. */
    internal fun folderName(name: String, id: String, taken: MutableSet<String>): String {
        val safe = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.').take(MAX_NAME).ifBlank { "tracker" }
        return if (taken.add(safe.lowercase())) safe else "$safe-${id.takeLast(6)}".also { taken.add(it.lowercase()) }
    }

    private fun hasAnything(cell: TrackerRecord) =
        cell[CellField.VALUE] != null || TrackerCells.count(cell.fields) != 0.0 || !cell[CellField.NOTE].isNullOrBlank()

    /** Lines of values: `;` between them, quoted when they hold `;`, a quote or a line break; a BOM for Excel. */
    private fun csv(rows: List<List<String>>): String = BOM + rows.joinToString("") { row ->
        row.joinToString(";") { value ->
            if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
        } + "\r\n"
    }

    private val BOM = Char(0xFEFF).toString()
    private const val MAX_NAME = 80
}
