package app.benbow.app

import app.benbow.client.ByteSink
import app.benbow.client.ExportProgress
import app.benbow.client.ExportReport
import app.benbow.client.Media
import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.db.BenbowDb

/** Writes the whole journal and calendar as one ZIP into [out] ("Ещё → Экспорт", data-safety.md#backups). */
internal expect suspend fun writeArchive(
    db: BenbowDb,
    media: Media,
    calendar: CalendarRepository,
    out: ByteSink,
    withMedia: Boolean,
    today: String,
    onProgress: (ExportProgress) -> Unit,
): ExportReport
