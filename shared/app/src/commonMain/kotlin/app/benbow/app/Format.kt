package app.benbow.app

import androidx.compose.runtime.Composable
import app.benbow.app.resources.Res
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.months_nominative
import app.benbow.app.resources.today
import app.benbow.app.resources.weekdays
import app.benbow.app.resources.yesterday
import app.benbow.client.Prompts
import app.benbow.client.TemplateContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A journal day starts at 04:00 (open-questions: answered 2026-10-12): writing at 1 a.m. still belongs to the evening before. */
internal const val DAY_START_HOUR = 4

internal fun journalToday(): LocalDate =
    Clock.System.now().minus(DAY_START_HOUR.hours).toLocalDateTime(TimeZone.currentSystemDefault()).date

/** "Сегодня", "Вчера", "Пятница, 9 октября", "Среда, 31 декабря 2025". */
@Composable
internal fun dayLabel(day: String): String {
    val date = LocalDate.parse(day)
    val today = journalToday()
    return when (date) {
        today -> stringResource(Res.string.today)

        today.minus(1, DateTimeUnit.DAY) -> stringResource(Res.string.yesterday)

        else -> {
            val weekday = stringArrayResource(Res.array.weekdays)[date.dayOfWeek.isoDayNumber - 1]
            val month = stringArrayResource(Res.array.months_genitive)[date.month.number - 1]
            "$weekday, ${date.day} $month" + if (date.year != today.year) " ${date.year}" else ""
        }
    }
}

/** "Вторник, 13 октября" even for today (the Home header). */
@Composable
internal fun fullDateLabel(day: String): String {
    val date = LocalDate.parse(day)
    val weekday = stringArrayResource(Res.array.weekdays)[date.dayOfWeek.isoDayNumber - 1]
    val month = stringArrayResource(Res.array.months_genitive)[date.month.number - 1]
    return "$weekday, ${date.day} $month"
}

/** What template placeholders become on [day] (the question of the day included). */
@Composable
internal fun templateContext(day: String): TemplateContext {
    val date = LocalDate.parse(day)
    val month = stringArrayResource(Res.array.months_genitive)[date.month.number - 1]
    return TemplateContext(
        date = "${date.day} $month ${date.year}",
        weekday = stringArrayResource(Res.array.weekdays)[date.dayOfWeek.isoDayNumber - 1].lowercase(),
        month = stringArrayResource(Res.array.months_nominative)[date.month.number - 1],
        prompt = Prompts.ofTheDay(day),
        kindness = Prompts.kindness(day),
    )
}

internal fun timeLabel(millis: Long): String {
    val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

@Composable
internal fun dateTimeLabel(millis: Long): String {
    // The day part follows the journal day, so 01:30 tonight still reads "Сегодня".
    val date = Instant.fromEpochMilliseconds(millis).minus(DAY_START_HOUR.hours).toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${dayLabel(date.toString())}, ${timeLabel(millis)}"
}

/** SHA-256 fingerprint as four lines of eight bytes: easy to compare with the admin page. */
internal fun fingerprintLines(fingerprint: String): String = fingerprint.split(':').chunked(8).joinToString("\n") { it.joinToString(":") }
