package app.benbow.client

import java.io.File

/**
 * A copy of a device database before a newer build migrates it (data-safety "Client-side safety", as the browser keeps
 * one): the outbox and drafts live only here until synced, and with updates from the server (ADR-042) a migration may run
 * while nobody watches. `<name>.before-v<N>-<time>` beside the file, made by SQLite's `VACUUM INTO` (one consistent file,
 * what the WAL holds included); the newest [KEEP] stay.
 */
internal object MigrationCopies {
    const val KEEP = 3

    fun target(file: File, version: Long, now: Long) = File(file.parentFile, "${file.name}.before-v$version-$now")

    /** The copies of [file] beyond the newest [KEEP], deleted. */
    fun prune(file: File) {
        file.parentFile.listFiles { f -> f.name.startsWith("${file.name}.before-v") }.orEmpty()
            .sortedByDescending { it.name.substringAfterLast('-').toLongOrNull() ?: 0 }
            .drop(KEEP)
            .forEach { it.delete() }
    }
}
