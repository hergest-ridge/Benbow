package app.benbow.app

import app.benbow.client.ArchiveExport
import app.benbow.client.write
import app.benbow.client.ByteSink
import app.benbow.client.ExportProgress
import app.benbow.client.ExportReport
import app.benbow.client.Media
import app.benbow.client.MediaStore
import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.db.BenbowDb
import java.io.File

// Shared by the Android and desktop targets (src/jvmShared is a source dir of both).

internal actual suspend fun writeArchive(
    db: BenbowDb,
    media: Media,
    calendar: CalendarRepository,
    out: ByteSink,
    withMedia: Boolean,
    today: String,
    onProgress: (ExportProgress) -> Unit,
): ExportReport = ArchiveExport(db, media, calendar).write(out, withMedia, today, onProgress)

/** Media files in [dir] on this device (the app's own storage). */
fun mediaIn(dir: File): MediaFactory = MediaFactory { db, apis, now -> MediaStore(dir, db, apis, now) }
