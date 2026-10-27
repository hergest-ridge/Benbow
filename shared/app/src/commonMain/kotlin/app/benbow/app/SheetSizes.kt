package app.benbow.app

import androidx.compose.ui.unit.dp

/** The trackers' sheet (ADR-050): its measures, and how strongly marks lie on its paper. */
internal object SheetSizes {
    val DAY_WIDTH = 44.dp
    val ROW_HEIGHT = 40.dp
    val HEADER_HEIGHT = 56.dp
    val TOTALS_HEIGHT = 36.dp
    val WEEK_HEIGHT = 22.dp
    val SIDE = 4.dp
    val CELL_GAP = 3.dp
    val CELL_CORNER = 7.dp
    val BAR_CORNER = 6.dp
    val NOTE_DOT = 4.dp
    val PICK_SIZE = 36.dp
    const val BAR_HEIGHT = 0.56f

    /** A page of a notebook: the month's sheet grows no wider on a big window; the sleep sheet's hours need more. */
    val SHEET = 640.dp
    val WIDE_SHEET = 960.dp

    /** An hour of the sleep sheet is half as wide as a column. */
    const val HOUR_WEIGHT = 0.5f
    const val FUTURE_ALPHA = 0.35f

    /**
     * How strongly marks lie on the paper (owner: calmer than the first spike's 0.85): a full mark, a part of one (a count
     * on its way to the goal, the lowest rating, a choice's tint), a cell's frame, an empty hour's dot, today's line and
     * the foot of the month.
     */
    const val MARK_ALPHA = 0.5f
    const val PART_ALPHA = 0.18f
    const val FRAME_ALPHA = 0.1f
    const val DOT_ALPHA = 0.25f
    const val TODAY_ALPHA = 0.05f
    const val FOOT_ALPHA = 0.04f

    /** Rows above today when a month opens on it. */
    const val TODAY_ABOVE = 4
}
