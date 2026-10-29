package app.benbow.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.benbow.app.resources.Res
import app.benbow.app.resources.calendar_first_entry
import app.benbow.app.resources.calendar_nearest
import app.benbow.app.resources.calendar_overview
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.months_nominative
import app.benbow.ui.theme.BenbowTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * The days with entries over the whole journal ("YYYY-MM-DD", newest first, the days the grid has dots for) and what
 * the overview and an empty month take from them (7.14).
 */
internal class WrittenDays(private val days: List<String>) {
    /** "YYYY-MM" to the numbers of its days with entries. */
    val months: Map<String, Set<Int>> = days.groupBy({ it.take(7) }, { it.drop(8).toIntOrNull() ?: 0 }).mapValues { it.value.toSet() }

    /** The journal's first day. */
    val first: LocalDate? = days.lastOrNull()?.let(LocalDate::parse)

    /** The years written in and [also] (this year, the one in view), newest first. */
    fun years(vararg also: Int): List<Int> =
        (months.keys.mapNotNull { it.take(4).toIntOrNull() } + also.toList()).distinct().sortedDescending()

    fun isEmpty(month: LocalDate) = month.toString().take(7) !in months

    /** The newest day with entries before [month]. */
    fun before(month: LocalDate): LocalDate? =
        month.monthStart().toString().let { start -> days.firstOrNull { it < start } }?.let(LocalDate::parse)

    /** The oldest day with entries after [month]. */
    fun after(month: LocalDate): LocalDate? = month.monthEnd().toString().let { end -> days.lastOrNull { it > end } }?.let(LocalDate::parse)
}

/**
 * «Обзор» (7.14, a tap on the calendar's title): every year written in, newest first, as twelve small months with a
 * dot on each day with entries, so how far the journal goes and where it is full shows at a glance. Entries only:
 * events are context (ADR-027), and an imported calendar would cover everything. No numbers, and a day without
 * entries is only paper, never a hole. A month opens in the calendar (the empty ones too: events go anywhere); the
 * year in view comes first. A sheet on a phone, a dialog in a wide window, as «Перейти» in the feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarOverviewSheet(
    written: WrittenDays,
    inView: LocalDate,
    today: LocalDate,
    onMonth: (LocalDate) -> Unit,
    onDay: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val years = remember(written, inView, today) { written.years(today.year, inView.year) }
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(
                stringResource(Res.string.calendar_overview),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            val names = stringArrayResource(Res.array.months_nominative)
            val genitive = stringArrayResource(Res.array.months_genitive)
            val state = rememberLazyListState(initialFirstVisibleItemIndex = years.indexOf(inView.year).coerceAtLeast(0))
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 560.dp)) {
                items(years, key = { it }) { year ->
                    OverviewYear(year, names, written.months, inView.monthStart(), today) {
                        onDismiss()
                        onMonth(it)
                    }
                }
                written.first?.let { first ->
                    item(key = "first") {
                        Text(
                            stringResource(
                                Res.string.calendar_first_entry,
                                "${first.day} ${genitive[first.month.number - 1]} ${first.year}",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).hand().clickable {
                                onDismiss()
                                onDay(first)
                            }.padding(vertical = 8.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
    if (LocalWide.current) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 480.dp)) {
                Column(Modifier.padding(top = 12.dp)) { content() }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            content()
        }
    }
}

/** A year as three rows of four small months, January first. */
@Composable
private fun OverviewYear(
    year: Int,
    names: List<String>,
    months: Map<String, Set<Int>>,
    inView: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text("$year", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        for (row in 0..2) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in row * 4 + 1..row * 4 + 4) {
                    val month = LocalDate(year, m, 1)
                    MiniMonth(
                        month,
                        names.getOrElse(m - 1) {
                            "$m"
                        },
                        months[month.toString().take(7)],
                        month == inView,
                        today,
                        Modifier.weight(1f),
                    ) {
                        onPick(month)
                    }
                }
            }
        }
    }
}

/**
 * A month as seven columns of dots: a full one on each day with entries, a faint speck on the others, so the month
 * keeps its shape; today ringed. The month in view is filled, this month outlined (as in «Перейти»).
 */
@Composable
private fun MiniMonth(
    month: LocalDate,
    name: String,
    written: Set<Int>?,
    inView: Boolean,
    today: LocalDate,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val thisMonth = today.monthStart() == month
    val length = remember(month) { month.monthEnd().day }
    val ink = colors.primary
    val paper = colors.onSurfaceVariant.copy(alpha = 0.22f)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (inView) colors.primaryContainer else Color.Transparent,
        border = if (thisMonth) BorderStroke(1.dp, colors.primary) else null,
        modifier = modifier.hand().semantics { contentDescription = "$name ${month.year}" },
    ) {
        Column(Modifier.padding(6.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = if (written != null) colors.onSurface else BenbowTheme.colors.textQuiet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Canvas(Modifier.fillMaxWidth().aspectRatio(7f / 6f)) {
                val cell = size.width / 7
                val offset = month.dayOfWeek.isoDayNumber - 1
                for (day in 1..length) {
                    val i = offset + day - 1
                    val center = Offset(cell * (i % 7 + 0.5f), cell * (i / 7 + 0.5f))
                    if (written?.contains(day) == true) drawCircle(ink, cell * 0.34f, center) else drawCircle(paper, cell * 0.13f, center)
                    if (thisMonth && day == today.day) drawCircle(ink, cell * 0.46f, center, style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

/**
 * Under the day of a month without entries: the nearest days with them before and after it, one tap away, so leafing
 * through empty months is never blind (7.14). A chip opens that very day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NearestEntries(written: WrittenDays, month: LocalDate, onDay: (LocalDate) -> Unit) {
    val before = remember(written, month) { written.before(month) }
    val after = remember(written, month) { written.after(month) }
    if (before == null && after == null) return
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(
            stringResource(Res.string.calendar_nearest),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            before?.let { day ->
                AssistChip(
                    { onDay(day) },
                    { Text(monthTitle(day.toString().take(7), withYear = true).orEmpty()) },
                    Modifier.hand(),
                    leadingIcon = { Icon(Icons.ChevronLeft, null, Modifier.size(18.dp)) },
                )
            }
            after?.let { day ->
                AssistChip(
                    { onDay(day) },
                    { Text(monthTitle(day.toString().take(7), withYear = true).orEmpty()) },
                    Modifier.hand(),
                    trailingIcon = { Icon(Icons.ChevronRight, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}
