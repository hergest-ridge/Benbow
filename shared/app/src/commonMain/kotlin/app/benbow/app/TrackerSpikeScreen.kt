package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benbow.app.resources.Res
import app.benbow.app.resources.next
import app.benbow.app.resources.previous
import app.benbow.app.resources.today
import app.benbow.app.resources.trackers_figures_hide
import app.benbow.app.resources.trackers_figures_show
import app.benbow.app.resources.trackers_spike
import app.benbow.app.resources.trackers_total
import app.benbow.app.resources.weekdays_short
import app.benbow.ui.theme.BenbowPalette
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** Trackers show only in labs builds while they are the B0 spike (ADR-050). */
internal val AppServices.trackersShown: Boolean get() = labs

/** A tab of their own on the web and the desktop; a phone reaches them from «Ещё». */
internal val AppServices.trackersTab: Boolean get() = trackersShown && platform != "android"

/**
 * «Трекеры» (ADR-050), for now the B0 spike: made-up sheets to feel the vertical month before any data exists. A tab
 * on the web and the desktop ([tab]), a page from «Ещё» on a phone. Months swipe as in the calendar; the eye hides a
 * tracker's numbers (the real one remembers that per tracker on every device).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackersScreen(services: AppServices, nav: Navigator, tab: Boolean) {
    val today = remember { LocalDate.parse(services.today()) }
    var sheets by remember { mutableStateOf(spikeSheets(today)) }
    var chosen by rememberSaveable { mutableIntStateOf(0) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val sheet = sheets[chosen]
    val figures = sheet.tracker.id !in hidden
    val pager = rememberPagerState(initialPage = pageOf(today)) { MONTH_PAGES }
    val scope = rememberCoroutineScope()
    val shownMonth = monthOfPage(pager.currentPage)
    val change: ((SpikeSheet) -> SpikeSheet) -> Unit = { edit ->
        sheets = sheets.map { if (it.tracker.id == sheet.tracker.id) edit(it) else it }
    }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { if (!tab) BackButton(nav::back) },
            title = {
                Text(monthTitle(shownMonth.toString().take(7), withYear = true).orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            actions = {
                if (shownMonth != LocalDate(today.year, today.month, 1)) {
                    IconButton({ scope.launch { pager.animateScrollToPage(pageOf(today)) } }, modifier = Modifier.hand()) {
                        TodayIcon(today.day, stringResource(Res.string.today))
                    }
                }
                IconButton({ hidden = if (figures) hidden + sheet.tracker.id else hidden - sheet.tracker.id }, modifier = Modifier.hand()) {
                    Icon(
                        if (figures) Icons.Eye else Icons.EyeOff,
                        stringResource(if (figures) Res.string.trackers_figures_hide else Res.string.trackers_figures_show),
                    )
                }
                // A mouse can't swipe (desktop, web), as in the calendar.
                if (LocalPagerArrows.current) {
                    IconButton({
                        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                    }, modifier = Modifier.hand()) { Icon(Icons.ChevronLeft, stringResource(Res.string.previous)) }
                    IconButton({
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }, modifier = Modifier.hand()) { Icon(Icons.ChevronRight, stringResource(Res.string.next)) }
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sheets.forEachIndexed { i, each ->
                    FilterChip(i == chosen, { chosen = i }, {
                        Text(listOfNotNull(iconEmoji(each.tracker.icon), each.tracker.name).joinToString(" "))
                    }, modifier = Modifier.hand())
                }
            }
            Text(
                stringResource(Res.string.trackers_spike),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // The tracker's paper (a Keep swatch, as a note's background), as wide as a page of a notebook.
                Column(
                    Modifier.widthIn(max = if (sheet.tracker.columns.any { it.type == SpikeType.Time }) WIDE_SHEET else SHEET)
                        .fillMaxSize()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(swatch(sheet.tracker.paper) ?: MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    SheetHeader(sheet.tracker, figures)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HorizontalPager(pager, beyondViewportPageCount = 1, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                        MonthSheet(sheet, monthOfPage(page), today, figures, change)
                    }
                }
            }
        }
    }
}

/** The columns' icons and header texts; it stays while the months swipe under it. A time column names its hours. */
@Composable
private fun SheetHeader(tracker: SpikeTracker, figures: Boolean) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = SIDE), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(DAY_WIDTH))
        tracker.columns.forEach { column ->
            if (column.type == SpikeType.Time) {
                Column(Modifier.weight(column.hours.size * HOUR_WEIGHT), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(listOfNotNull(iconEmoji(column.icon), column.name).joinToString(" "), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth()) {
                        column.hours.forEach { hour ->
                            Text(
                                "$hour",
                                modifier = Modifier.weight(1f),
                                color = faint,
                                fontSize = 9.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (figures) Box(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(iconEmoji(column.icon) ?: column.short.take(1), fontSize = 18.sp)
                    Text(
                        column.short,
                        color = faint,
                        fontSize = 10.sp,
                        lineHeight = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** One month of the sheet: its days down the page (today a few rows from the top), then its figures. */
@Composable
private fun MonthSheet(
    sheet: SpikeSheet,
    month: LocalDate,
    today: LocalDate,
    figures: Boolean,
    onChange: ((SpikeSheet) -> SpikeSheet) -> Unit,
) {
    val days = remember(month) { daysOf(month) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(today) - TODAY_ABOVE).coerceAtLeast(0))
    val weekdays = stringArrayResource(Res.array.weekdays_short)
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            items(days, key = { it.toString() }) { day ->
                DayRow(sheet, day, weekdays.getOrElse(day.dayOfWeek.isoDayNumber - 1) { "" }, today, figures, onChange)
            }
        }
        if (figures) TotalsRow(sheet, days.filter { it <= today })
    }
}

/** A day's line: its number and weekday, then a cell per column; days to come are faint and wait. A line ends each week. */
@Composable
private fun DayRow(
    sheet: SpikeSheet,
    day: LocalDate,
    weekday: String,
    today: LocalDate,
    figures: Boolean,
    onChange: ((SpikeSheet) -> SpikeSheet) -> Unit,
) {
    val future = day > today
    Column {
        Row(
            Modifier.fillMaxWidth().height(ROW_HEIGHT)
                .then(if (day == today) Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)) else Modifier)
                .padding(horizontal = SIDE)
                .alpha(if (future) FUTURE_ALPHA else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DayLabel(day, weekday, day == today)
            sheet.tracker.columns.forEach { column ->
                if (column.type == SpikeType.Time) {
                    val slept = sheet.slept(column, day)
                    column.hours.indices.forEach { hour ->
                        HourCell(column, day, hour, slept, enabled = !future, modifier = Modifier.weight(HOUR_WEIGHT)) {
                            onChange { it.hourTapped(column, day, hour) }
                        }
                    }
                    if (figures) {
                        Text(
                            if (slept.isEmpty()) "" else nightLength(slept.size.toDouble()),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    Cell(
                        column,
                        day,
                        sheet.value(column, day),
                        figures,
                        enabled = !future,
                        modifier = Modifier.weight(1f),
                        onTap = { onChange { it.tapped(column, day) } },
                        onClear = { onChange { it.cleared(column, day) } },
                    )
                }
            }
        }
        if (day.dayOfWeek == DayOfWeek.SUNDAY) {
            HorizontalDivider(Modifier.padding(horizontal = SIDE), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun DayLabel(day: LocalDate, weekday: String, today: Boolean) {
    val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
    Column(Modifier.width(DAY_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${day.day}",
            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 16.sp,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            weekday,
            color = if (weekend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * A day's cell, drawn from the column's tokens: blank paper with a faint frame when empty; a check filled with the
 * column's ink; a count filling up towards its goal; a rating deeper with its level; a choice in its option's color with
 * its emoji. Numbers show only with the figures. A tap marks, a long press empties.
 */
@Composable
private fun Cell(
    column: SpikeColumn,
    day: LocalDate,
    value: String?,
    figures: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val ink = BenbowPalette.text(column.color, dark) ?: colors.primary
    val onInk = if (dark) Color.Black else Color.White
    val shape = RoundedCornerShape(CELL_CORNER)
    val fill = column.fill(value)
    Box(
        modifier.fillMaxHeight()
            .padding(CELL_GAP)
            .clip(shape)
            .border(1.dp, colors.outline.copy(alpha = 0.18f), shape)
            .then(if (enabled) Modifier.hand().combinedClickable(onClick = onTap, onLongClick = onClear) else Modifier)
            .testTag(cellId(column, day)),
        contentAlignment = Alignment.Center,
    ) {
        when (column.type) {
            SpikeType.Check -> if (value != null) Box(Modifier.fillMaxSize().background(ink.copy(alpha = 0.85f)))

            SpikeType.Count -> {
                if (fill > 0f) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fill)
                            .background(ink.copy(alpha = if (fill >= 1f) 0.85f else 0.3f)),
                    )
                }
                if (figures && value != null) {
                    Text(value, color = if (fill >= 1f) onInk else colors.onSurface, style = MaterialTheme.typography.labelMedium)
                }
            }

            SpikeType.Scale -> {
                if (value != null) Box(Modifier.fillMaxSize().background(ink.copy(alpha = 0.15f + 0.7f * fill)))
                if (figures && value != null) {
                    Text(value, color = if (fill > 0.6f) onInk else colors.onSurface, style = MaterialTheme.typography.labelMedium)
                }
            }

            SpikeType.Choice -> column.options.find { it.id == value }?.let { option ->
                Box(Modifier.fillMaxSize().background((BenbowPalette.text(option.color, dark) ?: ink).copy(alpha = 0.3f)))
                Text(option.emoji, fontSize = 16.sp)
            }

            SpikeType.Time -> Unit
        }
    }
}

/** An hour of the sleep sheet: painted hours join into one bar with round ends; an empty hour is a faint dot. */
@Composable
private fun HourCell(
    column: SpikeColumn,
    day: LocalDate,
    hour: Int,
    slept: Set<Int>,
    enabled: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = BenbowPalette.text(column.color, colors.background.luminance() < 0.5f) ?: colors.primary
    val painted = hour in slept
    val start = painted && hour - 1 !in slept
    val end = painted && hour + 1 !in slept
    Box(
        modifier.fillMaxHeight()
            .then(if (enabled) Modifier.hand().clickable(onClick = onTap) else Modifier)
            .testTag("${cellId(column, day)}:$hour"),
        contentAlignment = Alignment.Center,
    ) {
        if (painted) {
            val round = { at: Boolean -> if (at) BAR_CORNER else 0.dp }
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.56f)
                    .padding(start = if (start) 1.dp else 0.dp, end = if (end) 1.dp else 0.dp)
                    .background(
                        ink.copy(alpha = 0.8f),
                        RoundedCornerShape(
                            topStart = round(start),
                            topEnd = round(end),
                            bottomEnd = round(end),
                            bottomStart = round(start),
                        ),
                    ),
            )
        } else {
            Box(Modifier.size(3.dp).background(colors.outline.copy(alpha = 0.35f), CircleShape))
        }
    }
}

/** The month's figures so far: they only count up (ux.md "Psychology"), and the eye hides them. */
@Composable
private fun TotalsRow(sheet: SpikeSheet, days: List<LocalDate>) {
    Row(
        Modifier.fillMaxWidth().height(TOTALS_HEIGHT)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.trackers_total),
            modifier = Modifier.width(DAY_WIDTH),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )
        sheet.tracker.columns.forEach { column ->
            if (column.type == SpikeType.Time) Box(Modifier.weight(column.hours.size * HOUR_WEIGHT))
            Text(
                sheet.total(column, days).orEmpty(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private val DAY_WIDTH = 44.dp
private val ROW_HEIGHT = 40.dp
private val HEADER_HEIGHT = 56.dp
private val TOTALS_HEIGHT = 36.dp
private val SIDE = 4.dp
private val CELL_GAP = 3.dp
private val CELL_CORNER = 7.dp
private val BAR_CORNER = 6.dp

/** A page of a notebook: the month's sheet grows no wider on a big window; the sleep sheet's hours need more. */
private val SHEET = 640.dp
private val WIDE_SHEET = 960.dp

/** An hour of the sleep sheet is half as wide as a column. */
private const val HOUR_WEIGHT = 0.5f
private const val FUTURE_ALPHA = 0.35f

/** Rows above today when a month opens on it. */
private const val TODAY_ABOVE = 4
