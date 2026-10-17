package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.stats_days
import app.benbow.app.resources.stats_moments
import app.benbow.app.resources.stats_mood
import app.benbow.app.resources.stats_pictures
import app.benbow.app.resources.stats_since
import app.benbow.app.resources.stats_tags
import app.benbow.client.BlockSize
import app.benbow.client.HomeStats
import app.benbow.client.StatFigure
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Whether a figure has something to say: a zero is left out, so nothing reads as "you didn't". */
internal fun HomeStats.has(figure: StatFigure): Boolean = when (figure) {
    StatFigure.Days -> days > 0
    StatFigure.Moments -> moments > 0
    StatFigure.Pictures -> pictures > 0
    StatFigure.Tags -> tags.isNotEmpty()
    StatFigure.Since -> since != null
    StatFigure.Mood -> weekMood != null
}

/** The figures of a stats block (ADR-034) that have something to say: one large in S, side by side in M. */
@Composable
internal fun StatsBlock(stats: HomeStats, figures: List<StatFigure>, size: BlockSize, today: String, modifier: Modifier) {
    val shown = figures.filter(stats::has)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shown.forEach { figure -> Figure(stats, figure, large = size == BlockSize.S, today, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Figure(stats: HomeStats, figure: StatFigure, large: Boolean, today: String, modifier: Modifier) {
    val number = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall
    val (value, caption) = when (figure) {
        StatFigure.Days -> stats.days.toString() to pluralStringResource(Res.plurals.stats_days, stats.days)

        StatFigure.Moments -> stats.moments.toString() to pluralStringResource(Res.plurals.stats_moments, stats.moments)

        StatFigure.Pictures -> stats.pictures.toString() to stringResource(Res.string.stats_pictures)

        StatFigure.Tags -> stats.tags.take(2).joinToString(", ") { it.name } to stringResource(Res.string.stats_tags)

        StatFigure.Since -> {
            val days = (stats.since?.let { LocalDate.parse(it).daysUntil(LocalDate.parse(today)) } ?: 0) + 1
            days.toString() to pluralStringResource(Res.plurals.stats_since, days)
        }

        StatFigure.Mood -> moodEmoji(stats.weekMood?.roundToInt()).orEmpty() to stringResource(Res.string.stats_mood)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            value,
            style = if (figure == StatFigure.Tags) MaterialTheme.typography.titleMedium else number,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}
