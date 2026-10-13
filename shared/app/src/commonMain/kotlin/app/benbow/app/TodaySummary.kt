package app.benbow.app

import app.benbow.app.resources.Res
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.weekdays
import app.benbow.app.resources.widget_entries
import app.benbow.app.resources.widget_no_entries
import app.benbow.client.Prompts
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import kotlin.math.roundToInt

/** The day at a glance for places outside the app's UI (the home-screen widget). */
class TodaySummary(val date: String, val prompt: String, val status: String)

/** "Вторник, 13 октября", the question of the day, and "2 записи сегодня · 🙂" (or a calm line when there are none). */
suspend fun AppServices.todaySummary(): TodaySummary {
    val day = today()
    val date = LocalDate.parse(day)
    val weekday = getStringArray(Res.array.weekdays)[date.dayOfWeek.isoDayNumber - 1]
    val month = getStringArray(Res.array.months_genitive)[date.month.number - 1]
    val entries = repo.entriesForDay(day).first()
    val mood = entries.mapNotNull { it.mood }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.let(::moodEmoji)
    val status = if (entries.isEmpty()) {
        getString(Res.string.widget_no_entries)
    } else {
        getPluralString(Res.plurals.widget_entries, entries.size, entries.size) + mood?.let { " · $it" }.orEmpty()
    }
    return TodaySummary("$weekday, ${date.day} $month", Prompts.ofTheDay(day), status)
}
