package app.benbow.app

import app.benbow.client.ByteSink
import app.benbow.client.ExportProgress
import app.benbow.client.ExportReport
import app.benbow.client.Media
import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.db.BenbowDb

/** The browser writes no archive yet (W5); the desktop and Android apps do. */
internal actual suspend fun writeArchive(
    db: BenbowDb,
    media: Media,
    calendar: CalendarRepository,
    out: ByteSink,
    withMedia: Boolean,
    today: String,
    onProgress: (ExportProgress) -> Unit,
): ExportReport = throw UnsupportedOperationException("The archive is saved from the desktop or Android app for now")
