package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.months_nominative
import app.benbow.app.resources.trash_group_this_week
import app.benbow.app.resources.trash_group_today
import app.benbow.app.resources.trash_group_yesterday
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * When things went to the trash, as its list groups them (owner): today, yesterday, earlier this week, then by month
 * («Сентябрь», «Декабрь 2025» in another year). Newest or oldest first as the order says; by weight there are no groups.
 */
internal sealed interface TrashGroup {
    data object Today : TrashGroup

    data object Yesterday : TrashGroup

    data object ThisWeek : TrashGroup

    data class Month(val year: Int, val month: Int) : TrashGroup
}

/** The group of something deleted on [since], seen on [today] (a week starts on Monday). */
internal fun trashGroup(since: LocalDate, today: LocalDate): TrashGroup = when {
    since >= today -> TrashGroup.Today
    since == today.minus(1, DateTimeUnit.DAY) -> TrashGroup.Yesterday
    since >= today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY) -> TrashGroup.ThisWeek
    else -> TrashGroup.Month(since.year, since.month.number)
}

/** [items], already in their order, cut into runs of one group each; a run of those with no time yet has no group. */
internal fun <T> trashRuns(items: List<T>, now: Long, zone: TimeZone, since: (T) -> Long?): List<Pair<TrashGroup?, List<T>>> {
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    val runs = ArrayList<Pair<TrashGroup?, MutableList<T>>>()
    for (item in items) {
        val group = since(item)?.let { trashGroup(Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date, today) }
        val last = runs.lastOrNull()
        if (last != null && last.first == group) last.second += item else runs += group to mutableListOf(item)
    }
    return runs
}

@Composable
internal fun trashGroupLabel(group: TrashGroup, thisYear: Int): String = when (group) {
    TrashGroup.Today -> stringResource(Res.string.trash_group_today)

    TrashGroup.Yesterday -> stringResource(Res.string.trash_group_yesterday)

    TrashGroup.ThisWeek -> stringResource(Res.string.trash_group_this_week)

    is TrashGroup.Month -> stringArrayResource(Res.array.months_nominative)[group.month - 1].let {
        if (group.year == thisYear) it else "$it ${group.year}"
    }
}

/**
 * A date group's line: its name and what its files weigh; while choosing, a tap on it chooses the whole group (or lets
 * it go when all of it is chosen already), [chosen] saying how much of it is.
 */
@Composable
internal fun TrashGroupHeader(label: String, bytes: Long, chosen: Boolean?, onChoose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .then(if (chosen != null) Modifier.hand().clickable(onClick = onChoose) else Modifier)
            .padding(start = 24.dp, end = 28.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (bytes > 0) {
            Text(sizeLabel(bytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (chosen != null) {
            // Under the entries' own marks.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { ChoiceMark(chosen, Modifier.padding(end = 8.dp)) }
        }
    }
}
