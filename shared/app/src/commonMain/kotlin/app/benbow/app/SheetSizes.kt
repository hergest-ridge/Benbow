package app.benbow.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The trackers' sheet (ADR-050): its measures, and how strongly marks lie on its paper. */
internal object SheetSizes {
    /** The month a size larger than at first (owner, ADR-058: the days' numbers were too small to read). */
    val DAY_WIDTH = 52.dp
    val ROW_HEIGHT = 46.dp
    val HEADER_HEIGHT = 64.dp
    val TOTALS_HEIGHT = 40.dp
    val WEEK_HEIGHT = 24.dp
    val SIDE = 4.dp
    val CELL_GAP = 3.dp

    /** Within the grid's lines a mark takes nearly the whole cell; a raised one keeps room for its shadow. */
    val GRID_GAP = 2.dp
    val RAISED_INSET = 3.dp
    val CELL_CORNER = 7.dp
    val BAR_CORNER = 6.dp
    val NOTE_DOT = 4.dp
    val PICK_SIZE = 36.dp
    const val BAR_HEIGHT = 0.56f

    /** The day's number and weekday, a column's short header, an hour's number, a week's figures. */
    val DAY_NUMBER = 17.sp
    val WEEKDAY = 11.sp
    val SHORT = 11.sp
    val HOUR_NUMBER = 11.sp
    val SMALL_FIGURE = 11.sp

    /** A page of a notebook: the month's sheet grows no wider on a big window; the sleep sheet's hours need more. */
    val SHEET = 760.dp
    val WIDE_SHEET = 1240.dp

    /** An hour of the sleep sheet is half as wide as a column; its day's length beside it a little wider than one. */
    const val HOUR_WEIGHT = 0.5f
    const val FIGURE_WEIGHT = 1.4f

    /** Hours narrower than this are numbered only every third (a whole day on a phone). */
    val HOUR_LABEL = 16.dp

    /**
     * A sleep sheet on a window narrower than [NARROW] (a phone, ADR-059): the hours take the row, and a day's length and
     * the other columns slide out from the right, [DRAWER_FIGURE] and [DRAWER_COLUMN] wide, pulled by a [GRIP].
     */
    val NARROW = 600.dp
    val DRAWER_FIGURE = 52.dp
    val DRAWER_COLUMN = 46.dp
    val GRIP = 14.dp
    val FIGURE_TEXT = 12.sp

    /** A column no narrower than this while its border is dragged; a row of a month across no lower. */
    val LEAST_COLUMN = 24.dp
    val LEAST_ROW = 20.dp
    const val FUTURE_ALPHA = 0.35f

    /**
     * How strongly marks lie on the paper (owner: calmer than the first spike's 0.85): a full mark, a part of one (a count
     * on its way to the goal, the lowest rating, a choice's tint), a cell's frame, an empty hour's dot, today's line, the
     * foot of the month and a hovered cell's state layer.
     */
    const val MARK_ALPHA = 0.5f
    const val PART_ALPHA = 0.18f
    const val FRAME_ALPHA = 0.1f
    const val DOT_ALPHA = 0.25f
    const val TODAY_ALPHA = 0.05f
    const val FOOT_ALPHA = 0.04f
    const val HOVER_ALPHA = 0.08f

    /** Rows above today when a month opens on it. */
    const val TODAY_ABOVE = 4
}
