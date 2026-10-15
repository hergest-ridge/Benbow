package app.benbow.client.calendar

import app.benbow.client.JournalRepository
import app.benbow.client.START_FIELD
import app.benbow.client.db.BenbowDb
import app.benbow.client.decodeFields
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.toPlainText

/**
 * Moves events made before 7.3a (journal entries of kind "event") into the calendar's own file (ADR-027): each one
 * becomes an event in «Мои события» with the same id (first line = title, the rest = description), then its entry
 * goes to the journal's trash. Nothing is erased. Safe to run on every start and on every device: an event that is
 * already in the calendar isn't written again. Returns how many entries it moved.
 */
fun moveJournalEvents(journalDb: BenbowDb, journal: JournalRepository, calendar: CalendarRepository): Int {
    val entries = journalDb.benbowQueries.journalEvents().executeAsList()
    for (entry in entries) {
        if (calendar.event(entry.id) == null) {
            val lines = text(entry.body).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val start = decodeFields(entry.fields)[START_FIELD] ?: entry.day
            val description = lines.drop(1).joinToString("\n").takeIf { it.isNotEmpty() }
            calendar.put(
                CalendarKind.EVENT,
                entry.id,
                mapOf(EventField.CALENDAR to MY_CALENDAR, EventField.TITLE to lines.firstOrNull().orEmpty(), EventField.START to start) +
                    listOfNotNull(description?.let { EventField.DESCRIPTION to it }),
            )
        }
        journal.moveToTrash(entry.id)
    }
    return entries.size
}

private fun text(body: String): String = try {
    DocumentCodec.decode(body).toPlainText(includeSpoilers = true)
} catch (_: DocumentFormatException) {
    ""
}
