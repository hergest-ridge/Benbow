package app.benbow.client

import java.io.File
import java.sql.DriverManager

/**
 * Before the desktop app opens [file] with a schema at [version]: when the file is older, a copy of it ([MigrationCopies]).
 * Returns the copy, or null when nothing was to migrate.
 */
fun keepBeforeMigrating(file: File, version: Long, now: Long = System.currentTimeMillis()): File? {
    if (!file.isFile) return null
    DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
        val current = c.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use {
                it.next()
                it.getLong(1)
            }
        }
        if (current !in 1 until version) return null
        val copy = MigrationCopies.target(file, version, now)
        c.prepareStatement("VACUUM INTO ?").use {
            it.setString(1, copy.absolutePath)
            it.execute()
        }
        MigrationCopies.prune(file)
        return copy
    }
}
