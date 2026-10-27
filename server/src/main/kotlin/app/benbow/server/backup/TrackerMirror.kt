package app.benbow.server.backup

import app.benbow.core.tracker.TrackerExport
import app.benbow.core.tracker.TrackerKind
import app.benbow.core.tracker.TrackerRecord
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.calendar.SqliteCalendarStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `export/trackers/`: each tracker outside the trash as files readable without the app (ADR-050), a folder of its own
 * with `tracker.json`, a CSV per year and `spans.csv`. Rewritten whenever the calendar store changed (its epoch or
 * change log); files of trackers gone, renamed or in the trash are removed, since everything here is derived from the
 * store and its backups. Nothing is written until there is a tracker.
 */
class TrackerMirror(private val dir: Path) {
    private val state = dir.resolve(".last-change")

    /** Returns true when it wrote the files. */
    fun update(store: CalendarDatabase): Boolean {
        val mark = "${store.epoch}:${store.latestCursor()}"
        if (state.exists() && state.readText().trim() == mark) return false
        val records = SqliteCalendarStore(store).all()
        val ofKind = { kind: String ->
            records.filter { it.kind == kind }.map { r -> TrackerRecord(r.id, r.fields.mapValues { it.value.value }) }
        }
        val files = TrackerExport.files(
            ofKind(TrackerKind.TRACKER),
            ofKind(TrackerKind.COLUMN),
            ofKind(TrackerKind.CELL),
            ofKind(TrackerKind.SPAN),
        )
        if (files.isEmpty() && !dir.exists()) return false
        dir.createDirectories()
        val written = files.map { file ->
            dir.resolve(file.path.removePrefix(TrackerExport.FOLDER + "/")).also { target ->
                target.parent.createDirectories()
                atomicWrite(target, file.text)
            }
        }.toSet()
        // What the store no longer has: the files of trackers gone or renamed, then the folders left empty.
        val stale = Files.walk(dir).use { paths -> paths.filter { it.isRegularFile() && it.extension in OWN && it !in written }.toList() }
        stale.forEach(Files::delete)
        dir.listDirectoryEntries().filter { it.isDirectory() && it.listDirectoryEntries().isEmpty() }.forEach(Files::delete)
        atomicWrite(state, mark)
        return true
    }

    private fun atomicWrite(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        tmp.writeText(text)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        /** The kinds of files the mirror writes here; anything else in the folder is left alone. */
        val OWN = setOf("json", "csv")
    }
}
