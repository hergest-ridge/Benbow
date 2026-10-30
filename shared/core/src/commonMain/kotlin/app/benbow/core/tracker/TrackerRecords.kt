package app.benbow.core.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A tracker, column, cell or span record as the store keeps it: its id and field values (ADR-050). */
@Serializable
data class TrackerRecord(val id: String, val fields: Map<String, String?>) {
    operator fun get(field: String): String? = fields[field]

    /** In the trash (trackers, columns and spans; a cell is cleared by its value instead). */
    val deleted: Boolean get() = fields[TrackerField.DELETED] == "true"

    companion object {
        /** Its place among its kind: rank keys ascending ([app.benbow.core.board.BoardRank]), then ids; no key first. */
        val byRank: Comparator<TrackerRecord> = compareBy({ it[TrackerField.RANK].orEmpty() }, { it.id })
    }
}

/** A column record read for its type's rules; every field stays in [record] as it is, unknown ones included. */
class TrackerColumn(val record: TrackerRecord) {
    val id: String get() = record.id
    val tracker: String? get() = record[ColumnField.TRACKER]
    val name: String get() = record[ColumnField.NAME].orEmpty()
    val type: ColumnType? get() = ColumnType.of(record[ColumnField.TYPE])
    val options: List<ChoiceOption> by lazy { ChoiceOption.list(record[ColumnField.OPTIONS]) }
    val unit: String? get() = record[ColumnField.UNIT]?.takeIf { it.isNotBlank() }

    /** What to reach, above zero; null without a goal. */
    val goal: Double? get() = record[ColumnField.GOAL]?.toDoubleOrNull()?.takeIf { it > 0 }
    val goalPer: GoalPer get() = GoalPer.of(record[ColumnField.GOAL_PER])
    val min: Int get() = record[ColumnField.MIN]?.toIntOrNull() ?: 1
    val max: Int get() = record[ColumnField.MAX]?.toIntOrNull()?.takeIf { it > min } ?: maxOf(min + 1, 5)
    val hours: HourWindow? get() = HourWindow.of(record[ColumnField.HOURS])
    val dayBy: DayBy get() = DayBy.of(record[ColumnField.DAY_BY])
}

/** One of a choice column's own options: cells hold its [id], so renaming or recoloring it changes no cell. */
@Serializable
data class ChoiceOption(val id: String, val name: String = "", val color: String? = null, val icon: String? = null) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The options [text] lists; none for text that doesn't read (the field itself is kept as it is). */
        fun list(text: String?): List<ChoiceOption> {
            val options = text?.let { runCatching { json.decodeFromString(ListSerializer(serializer()), it) }.getOrNull() }.orEmpty()
            return options.filter { it.id.isNotBlank() }.distinctBy { it.id }
        }

        fun write(options: List<ChoiceOption>): String = json.encodeToString(ListSerializer(serializer()), options)
    }
}

/**
 * A time column's window on the sleep sheet, "18:00-12:00": from [from] to [to] minutes after midnight, over midnight
 * when [to] is not after [from].
 */
data class HourWindow(val from: Int, val to: Int) {
    /** The clock hours it shows, one cell each: 18, 19, … 23, 0, … 11. */
    val hours: List<Int> get() {
        val length = (to - from).mod(MINUTES_A_DAY).let { if (it == 0) MINUTES_A_DAY else it }
        return (0 until (length + 59) / 60).map { (from / 60 + it) % 24 }
    }

    override fun toString() = "${clock(from)}-${clock(to)}"

    companion object {
        fun of(text: String?): HourWindow? {
            val (from, to) = text?.split('-')?.takeIf { it.size == 2 }?.map { minutes(it.trim()) ?: return null } ?: return null
            return HourWindow(from, to)
        }

        private fun minutes(clock: String): Int? {
            val (h, m) = clock.split(':').takeIf { it.size == 2 } ?: return null
            val hour = h.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val minute = m.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            return hour * 60 + minute
        }

        private fun clock(minutes: Int) = "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

        private const val MINUTES_A_DAY = 24 * 60
    }
}
