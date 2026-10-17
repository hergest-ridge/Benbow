package app.benbow.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.benbow.app.resources.Res
import app.benbow.app.resources.feed_jump
import app.benbow.app.resources.feed_jump_date
import app.benbow.app.resources.feed_jump_random
import app.benbow.app.resources.feed_jump_today
import app.benbow.app.resources.feed_jump_year_ago
import app.benbow.app.resources.months_nominative
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

/**
 * Where «Перейти» can go in the feed as it is (ADR-036 N4): the months with posts and the rows of their titles, the
 * days with posts and the rows of their lines. Quiet posts are already out of the rows, so a month of only quiet ones
 * shows as one without posts, like a month not written in.
 */
internal class JumpIndex(private val rows: List<FeedRow>) {
    /** "YYYY-MM" to the row of its title, newest first. */
    val months = LinkedHashMap<String, Int>()

    /** Each day with posts and the row of its line, newest first. */
    private val days = ArrayList<Pair<String, Int>>()

    init {
        rows.forEachIndexed { row, it ->
            when (it) {
                is FeedRow.Month -> months[it.month] = row
                is FeedRow.Day -> days += it.day to row
                is FeedRow.Item -> Unit
            }
        }
    }

    /** The years with posts, newest first. */
    val years: List<String> = months.keys.map { it.take(4) }.distinct()

    /** The row of the newest day on or before [day] ("YYYY-MM-DD"); the oldest day when every one is later. */
    fun dayRow(day: String): Int? = days.firstOrNull { it.first <= day }?.second ?: days.lastOrNull()?.second

    /** Whether the feed goes back as far as [day]. */
    fun reaches(day: String): Boolean = days.lastOrNull()?.let { it.first <= day } == true

    /**
     * A day to come back to at random: of a post older than [before], under no curtain (a hard day never comes up by
     * chance). Null when there is none.
     */
    fun randomDay(before: String, random: Random = Random): Int? {
        val days = rows.mapNotNullTo(HashSet()) { row ->
            (row as? FeedRow.Item)?.place?.takeIf { it.veil == null && it.day < before }?.day
        }
        return days.randomOrNull(random)?.let(::dayRow)
    }
}

/**
 * «Перейти» (ADR-036 N4): today, a year ago, a day at random, a date; then every year with its twelve months, those
 * with posts to tap, the others faint. No counts: how much was written is not the point here. A sheet on a phone, a
 * dialog in a wide window; [inView] ("YYYY-MM") is marked and its year comes first into view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedJumpSheet(rows: List<FeedRow>, inView: String?, onJump: (row: Int) -> Unit, onToday: () -> Unit, onDismiss: () -> Unit) {
    val index = remember(rows) { JumpIndex(rows) }
    var picking by remember { mutableStateOf(false) }
    val today = journalToday()
    val go = { row: Int? ->
        if (row != null) {
            onDismiss()
            onJump(row)
        }
    }
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(
                stringResource(Res.string.feed_jump),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            val yearAgo = today.minus(1, DateTimeUnit.YEAR).toString()
            val monthAgo = today.minus(30, DateTimeUnit.DAY).toString()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({
                    onDismiss()
                    onToday()
                }, { Text(stringResource(Res.string.feed_jump_today)) }, modifier = Modifier.hand())
                AssistChip({
                    go(index.dayRow(yearAgo))
                }, { Text(stringResource(Res.string.feed_jump_year_ago)) }, Modifier.hand(), enabled = index.reaches(yearAgo))
                AssistChip({
                    go(index.randomDay(monthAgo))
                }, { Text(stringResource(Res.string.feed_jump_random)) }, Modifier.hand(), enabled = index.reaches(monthAgo))
                AssistChip({ picking = true }, { Text(stringResource(Res.string.feed_jump_date)) }, modifier = Modifier.hand())
            }
            val names = stringArrayResource(Res.array.months_nominative).map { it.take(3) }
            val focus = index.years.indexOf(inView?.take(4)).coerceAtLeast(0)
            LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = focus), modifier = Modifier.heightIn(max = 520.dp)) {
                items(index.years, key = { it }) { year ->
                    YearMonths(year, names, index.months, inView, today.toString().take(7)) { go(it) }
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
        ModalBottomSheet(onDismissRequest = onDismiss) { content() }
    }
    if (picking) {
        DayPicker(today, onPick = { day ->
            picking = false
            go(index.dayRow(day.toString()))
        }, onDismiss = { picking = false })
    }
}

/** A year's twelve months in two rows, January first: those with posts to tap, the month in view marked. */
@Composable
private fun YearMonths(
    year: String,
    names: List<String>,
    months: Map<String, Int>,
    inView: String?,
    thisMonth: String,
    onPick: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(year, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        for (half in 0..1) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in half * 6 + 1..half * 6 + 6) {
                    val month = "$year-" + m.toString().padStart(2, '0')
                    val row = months[month]
                    Surface(
                        onClick = { row?.let(onPick) },
                        enabled = row != null,
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            month == inView -> colors.primaryContainer
                            row != null -> colors.surfaceContainerHigh
                            else -> Color.Transparent
                        },
                        border = if (month == thisMonth) BorderStroke(1.dp, colors.primary) else null,
                        modifier = Modifier.weight(1f).height(40.dp).then(if (row != null) Modifier.hand() else Modifier),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                names.getOrElse(m - 1) { "$m" },
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                color = if (row != null) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.38f),
                            )
                        }
                    }
                }
            }
        }
    }
}
