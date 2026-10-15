package app.benbow.server.backup

import app.benbow.core.calendar.CalendarExport
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.MY_CALENDAR_NAME
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.calendar.SqliteCalendarStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `export/calendars/`: the calendar as files any calendar app opens (ADR-028), one `.ics` per calendar plus a full
 * `calendar.json`. Rewritten whole whenever the calendar store changed (epoch or change log); files of calendars that
 * are gone or renamed are removed, since everything here is derived from the store and its backups.
 */
class CalendarMirror(private val dir: Path) {
    private val state = dir.resolve(".last-change")

    /** Returns true when it wrote the files. */
    fun update(store: CalendarDatabase, now: Long = System.currentTimeMillis()): Boolean {
        val mark = "${store.epoch}:${store.latestCursor()}"
        if (state.exists() && state.readText().trim() == mark) return false
        val records = SqliteCalendarStore(store).all()
        val ofKind = { kind: String ->
            records.filter { it.kind == kind }.map { r -> CalendarExport.Record(r.id, r.fields.mapValues { it.value.value }) }
        }
        val files = CalendarExport.files(ofKind(CalendarKind.CALENDAR), ofKind(CalendarKind.EVENT), MY_CALENDAR_NAME, now)
        dir.createDirectories()
        files.forEach { atomicWrite(dir.resolve(it.name), it.text) }
        val names = files.map { it.name }.toSet()
        dir.listDirectoryEntries("*.ics").filter { it.name !in names }.forEach(Files::delete)
        atomicWrite(state, mark)
        return true
    }

    private fun atomicWrite(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        tmp.writeText(text)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
