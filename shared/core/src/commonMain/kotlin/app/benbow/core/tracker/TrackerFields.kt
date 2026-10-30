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

    /**
     * Its marks' color, every column's (owner, ADR-058): an editor palette token (`BenbowPalette`) or a color of one's own
     * (`hue:<degrees>:<chroma>`); none: each column's own.
     */
    const val COLOR = "color"

    /** A note color's name or a color of one's own (`hue:<degrees>:<chroma>`, ADR-058). */
    const val BACKGROUND = "background"

    /** How its marks are drawn (ADR-058): `solid`, `marker`, `tube`, `snake`, …; none: soft, as at first. */
    const val MARK = "mark"

    /** Lines between its cells (ADR-058): `lines`, `table`, `dashed`, `dots`, `pencil`, `ink`; none: a faint frame each. */
    const val GRID = "grid"

    /** "true": its columns (rows, in a month across) can be stretched on the sheet; their sizes hold either way. */
    const val RESIZE = "resize"
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

    /**
     * The timers' fields (0.17-0.20; dropped by the owner, ADR-058): "one", and when a timer started. Nothing writes or
     * reads them now; a record keeps whatever it held, as with any field.
     */
    const val TIMER = "timer"
    const val RUNNING = "running"

    /** The look of a filled cell: `tick`, `fill`, `dot`, … (not drawn yet: the tracker's `mark` is, ADR-058). */
    const val MARK = "mark"

    /** Its width in a month down the page, a multiple of the usual ("1.5"); [HEIGHT] its row's in a month across (ADR-058). */
    const val WIDTH = "width"
    const val HEIGHT = "height"
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
