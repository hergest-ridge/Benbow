package app.benbow.client

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Before the app opens [file] with a schema at [version]: when the file is older, a copy of it ([MigrationCopies]).
 * Returns the copy, or null when nothing was to migrate.
 */
fun keepBeforeMigrating(file: File, version: Long, now: Long = System.currentTimeMillis()): File? {
    if (!file.isFile) return null
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        if (db.version.toLong() !in 1 until version) return null
        val copy = MigrationCopies.target(file, version, now)
        db.execSQL("VACUUM INTO ?", arrayOf(copy.path))
        MigrationCopies.prune(file)
        return copy
    }
}
