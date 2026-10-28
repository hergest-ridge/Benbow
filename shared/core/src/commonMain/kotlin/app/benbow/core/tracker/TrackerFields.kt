package app.benbow.core.tracker

/** What a tracker record is (ADR-050): records of the calendar store beside calendars and events, LWW per field. */
object TrackerKind {
    const val TRACKER = "tracker"
    const val COLUMN = "column"
    const val CELL = "cell"
    const val SPAN = "span"
    val all = setOf(TRACKER, COLUMN, CELL, SPAN)
}

/** Fields of a tracker record (docs/data-model.md#trackers). Its look is a note's: `background` and `look_<layer>` (ADR-047). */
object TrackerField {
    const val NAME = "name"

    /** A look token: `emoji:<emoji>` for now; other schemes (a hand-drawn set) come later. */
    const val ICON = "icon"

    /** An editor palette token (`BenbowPalette`) for its marks. */
    const val COLOR = "color"
    const val BACKGROUND = "background"
    const val LOOK_PREFIX = "look_"

    /** The screen behind its sheet (ADR-051): `screen_pattern`, `screen_light`, `screen_motion`, tokens of a note's look. */
    const val SCREEN_PREFIX = "screen_"

    /** How its month lies: `across` puts the days along the top and the columns down the side; none: days down the page. */
    const val LAYOUT = "layout"

    /** Where it opens: `month`, `week`, `year` or `sleep`. */
    const val VIEW = "view"
    const val RANK = "rank"

    /** "hidden": its totals, averages and goals' numbers hidden (the eye). */
    const val FIGURES = "figures"

    /** "true": shown in the calendar (off by default). */
    const val CALENDAR = "calendar"
    const val PRESET = "preset"
    const val ARCHIVED = "archived"
    const val DELETED = "deleted"
}

/** Fields of a column record. */
object ColumnField {
    const val TRACKER = "tracker"

    /** Its header text; [SHORT] is the header where the column is narrow. */
    const val NAME = "name"
    const val SHORT = "short"
    const val ICON = "icon"
    const val COLOR = "color"

    /** A [ColumnType] token. */
    const val TYPE = "type"

    /** A choice's own options, JSON `[{id, name, color, icon}]` ([ChoiceOption]). */
    const val OPTIONS = "options"
    const val UNIT = "unit"

    /** A count's (or a time column's hours, or a check's days) to reach; [GOAL_PER] a [GoalPer] token. */
    const val GOAL = "goal"
    const val GOAL_PER = "goal_per"

    /** A rating's range, 1-5 unless set. */
    const val MIN = "min"
    const val MAX = "max"

    /** A time column's window on the sleep sheet, "18:00-12:00" ([HourWindow]). */
    const val HOURS = "hours"

    /** A [DayBy] token: which day a span counts for. */
    const val DAY_BY = "day_by"

    /** "one": starting its timer stops the tracker's other timers. */
    const val TIMER = "timer"

    /** When its timer started, "YYYY-MM-DDTHH:MM:SS"; null: stopped. */
    const val RUNNING = "running"

    /** The look of a filled cell: `tick`, `fill`, `dot`, … */
    const val MARK = "mark"
    const val RANK = "rank"
    const val ARCHIVED = "archived"
    const val DELETED = "deleted"
}

/** Fields of a day's cell; its id is [TrackerCells.id]. */
object CellField {
    const val COLUMN = "column"
    const val DAY = "day"

    /** "true" (check), a number (rating), an option's id (choice), a short text (note); null clears it. */
    const val VALUE = "value"
    const val NOTE = "note"

    /** A count's part from one device, `add:<device id>`: a signed decimal; the count is their sum. */
    const val ADD_PREFIX = "add:"
}

/** Fields of a span of time. */
object SpanField {
    const val COLUMN = "column"

    /** "YYYY-MM-DDTHH:MM:SS", local and floating like the rest of the app (ADR-028). */
    const val START = "start"
    const val END = "end"
    const val NOTE = "note"
    const val DELETED = "deleted"
}

/** What a column's cells hold. A type this app doesn't know reads as null, and its cells stay as they are. */
enum class ColumnType(val token: String) {
    Check("check"),
    Count("count"),
    Scale("scale"),
    Choice("choice"),
    Time("time"),
    Note("note"),
    ;

    companion object {
        fun of(token: String?): ColumnType? = entries.firstOrNull { it.token == token }
    }
}

/** Which day a span counts for: the day it starts (a time tracker's) or ends (sleep: the day one wakes). */
enum class DayBy(val token: String) {
    Start("start"),
    End("end"),
    ;

    companion object {
        fun of(token: String?): DayBy = entries.firstOrNull { it.token == token } ?: Start
    }
}

/** A goal's period: a count or a time per day, a check's days per week or month. */
enum class GoalPer(val token: String) {
    Day("day"),
    Week("week"),
    Month("month"),
    ;

    companion object {
        fun of(token: String?): GoalPer = entries.firstOrNull { it.token == token } ?: Day
    }
}
