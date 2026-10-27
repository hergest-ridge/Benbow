package app.benbow.app

import app.benbow.core.tracker.ChoiceOption
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.GoalPer
import app.benbow.core.tracker.HourWindow
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerRecord

// The trackers' constructor (ADR-050, B5): what a new column starts with, what changing a column's type writes, and the
// editor's words for numbers, hours and emoji, read back into fields. Plain functions; TrackerEditorScreen.kt and
// ColumnEditor.kt draw them. Nothing here rewrites a cell: a column's fields change, its values stay as they are.

/** The types in the order the editor offers them. */
internal val COLUMN_TYPES =
    listOf(ColumnType.Check, ColumnType.Count, ColumnType.Scale, ColumnType.Choice, ColumnType.Time, ColumnType.Note)

/** A rating's ranges offered as chips: (min, max). */
internal val SCALE_RANGES = listOf(1 to 3, 1 to 5, 1 to 10, 0 to 10)

/** Emoji offered in one tap for a tracker and for a column; any other can be typed. */
internal val TRACKER_EMOJI = listOf("🌱", "📋", "🎯", "💪", "🧘", "📚", "💧", "🏃", "😴", "🕒", "🌈", "📝")
internal val COLUMN_EMOJI = listOf(
    "💧", "🧘", "🚶", "🏃", "💪", "📖", "📚", "📝", "🍎", "🥗", "🍵", "😴", "💊", "🧹", "🎸", "🎨",
    "💻", "🧠", "🙏", "🌞", "🌙", "📵", "💰", "🦷", "🛁", "🐕", "🌱", "🔋", "🌈", "🎯",
)

/**
 * A new column of [type] named [name] in [ink]: what the type needs to be used at once (a choice's three options, made
 * with ids from [newId]); everything can be changed in its editor after.
 */
internal fun newColumn(type: ColumnType, name: String, ink: String, newId: () -> String): Map<String, String?> {
    val fields = mutableMapOf<String, String?>(ColumnField.NAME to name, ColumnField.COLOR to ink, ColumnField.TYPE to type.token)
    if (type == ColumnType.Choice) fields[ColumnField.OPTIONS] = ChoiceOption.write(defaultOptions(newId))
    return fields
}

/** What switching [column] to [type] writes: the type, and the options a choice needs when the column has none. */
internal fun typeFields(column: TrackerColumn, type: ColumnType, newId: () -> String): Map<String, String?> =
    if (type == ColumnType.Choice && column.options.isEmpty()) {
        mapOf(ColumnField.TYPE to type.token, ColumnField.OPTIONS to ChoiceOption.write(defaultOptions(newId)))
    } else {
        mapOf(ColumnField.TYPE to type.token)
    }

private fun defaultOptions(newId: () -> String) = listOf(
    ChoiceOption(newId(), "Плохо", "red", "emoji:😫"),
    ChoiceOption(newId(), "Так себе", "yellow", "emoji:😐"),
    ChoiceOption(newId(), "Хорошо", "green", "emoji:🙂"),
)

/** The next ink for a new column: the first of the palette [taken] doesn't use yet, else the palette goes round. */
internal fun nextInk(palette: List<String>, taken: List<String?>): String =
    palette.firstOrNull { it !in taken } ?: palette[taken.size % palette.size]

/** An emoji typed in the editor as an icon token (`emoji:<e>`); null for none. */
internal fun emojiToken(text: String): String? = text.trim().take(EMOJI_MAX).ifEmpty { null }?.let { EMOJI_PREFIX + it }

/** A number as the editor shows it ("8", "2,5"); "" for none. */
internal fun numberText(value: String?): String = value?.toDoubleOrNull()?.let(::countText).orEmpty()

/** A number typed with a comma or a point, as a field keeps it ("2.5"); null when it doesn't read. */
internal fun typedNumber(text: String): String? = text.trim().replace(',', '.').toDoubleOrNull()?.let(TrackerCells::number)

/** A clock typed in the editor ("21", "21:30", "9:05") as minutes after midnight; null when it doesn't read. */
internal fun typedClock(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size > 2) return null
    val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = parts.getOrNull(1)?.let { typed -> typed.toIntOrNull()?.takeIf { it in 0..59 } ?: return null } ?: 0
    return hour * 60 + minute
}

/** Minutes after midnight as the editor shows them: "21:00". */
internal fun clockOf(minutes: Int): String = "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

/** A tracker's name and emoji as typed in its editor. */
internal data class TrackerDraft(val name: String, val emoji: String) {
    /**
     * What the draft changes in [tracker]: a blank name keeps the old one, a blank emoji takes the icon off (an icon of
     * another scheme, which the field can't show, stays).
     */
    fun changes(tracker: TrackerRecord): Map<String, String?> = buildMap {
        name.trim().takeIf { it.isNotEmpty() && it != tracker[TrackerField.NAME] }?.let { put(TrackerField.NAME, it) }
        val icon = emojiToken(emoji)
        val shown = tracker[TrackerField.ICON]?.let(::iconEmoji) != null
        if (icon != tracker[TrackerField.ICON] && (icon != null || shown)) put(TrackerField.ICON, icon)
    }

    companion object {
        fun of(tracker: TrackerRecord) =
            TrackerDraft(tracker[TrackerField.NAME].orEmpty(), tracker[TrackerField.ICON]?.let(::iconEmoji).orEmpty())
    }
}

/** A column's texts as typed in its editor: header texts, emoji, unit, goal, the sleep sheet's hours, a choice's options. */
internal data class ColumnDraft(
    val name: String,
    val short: String,
    val emoji: String,
    val unit: String,
    val goal: String,
    val from: String,
    val to: String,
    val options: List<ChoiceOption>,
) {
    /**
     * What the draft changes in [column]: texts trimmed and an empty one taken off (a blank name keeps the old one); a
     * goal or hours that don't read leave the field as it was; both hours empty take the sheet's hours off.
     */
    fun changes(column: TrackerColumn): Map<String, String?> {
        val record = column.record
        val out = LinkedHashMap<String, String?>()
        fun text(field: String, value: String?) {
            if (value != record[field]) out[field] = value
        }
        name.trim().takeIf { it.isNotEmpty() }?.let { text(ColumnField.NAME, it) }
        text(ColumnField.SHORT, short.trim().ifEmpty { null })
        val icon = emojiToken(emoji)
        if (icon != null || record[ColumnField.ICON]?.let(::iconEmoji) != null) text(ColumnField.ICON, icon)
        text(ColumnField.UNIT, unit.trim().ifEmpty { null })
        when {
            goal.isBlank() -> text(ColumnField.GOAL, null)
            typedNumber(goal)?.toDoubleOrNull()?.let { it > 0 } == true -> text(ColumnField.GOAL, typedNumber(goal))
        }
        // A check's goal counts marked days in a week or a month, never a day: a week unless said otherwise.
        if (column.type == ColumnType.Check && out[ColumnField.GOAL] != null && column.goalPer == GoalPer.Day) {
            out[ColumnField.GOAL_PER] = GoalPer.Week.token
        }
        val window = if (from.isBlank() && to.isBlank()) {
            ""
        } else {
            val a = typedClock(from)
            val b = typedClock(to)
            if (a != null && b != null && a != b) HourWindow(a, b).toString() else null
        }
        window?.let { text(ColumnField.HOURS, it.ifEmpty { null }) }
        if (options != column.options) out[ColumnField.OPTIONS] = ChoiceOption.write(options)
        return out
    }

    companion object {
        fun of(column: TrackerColumn): ColumnDraft {
            val record = column.record
            val window = column.hours
            return ColumnDraft(
                name = column.name,
                short = record[ColumnField.SHORT].orEmpty(),
                emoji = record[ColumnField.ICON]?.let(::iconEmoji).orEmpty(),
                unit = column.unit.orEmpty(),
                goal = numberText(record[ColumnField.GOAL]),
                from = window?.let { clockOf(it.from) }.orEmpty(),
                to = window?.let { clockOf(it.to) }.orEmpty(),
                options = column.options,
            )
        }
    }
}

private const val EMOJI_PREFIX = "emoji:"

/** An emoji may be several characters (skin tones, flags, joined people). */
private const val EMOJI_MAX = 16
