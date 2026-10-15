package app.benbow.app

import app.benbow.client.ArchiveExport
import app.benbow.client.BlobZipWriter
import app.benbow.client.ByteSink
import app.benbow.client.ExportProgress
import app.benbow.client.ExportReport
import app.benbow.client.Media
import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.db.BenbowDb
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The browser builds the ZIP from parts (ADR-029): the media stay the Blobs in IndexedDB, so even gigabytes of video
 * aren't read into memory, and the finished archive goes to the browser's downloads.
 */
internal actual suspend fun writeArchive(
    db: BenbowDb,
    media: Media,
    calendar: CalendarRepository,
    out: ByteSink,
    withMedia: Boolean,
    today: String,
    onProgress: (ExportProgress) -> Unit,
): ExportReport {
    val zip = BlobZipWriter(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()))
    val report = ArchiveExport(db, media, calendar).write(zip, withMedia, today, onProgress)
    (out as DownloadSink).deliver(zip.finish())
    return report
}
