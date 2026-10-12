package app.benbow.server.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * How far the store has been (store id, epoch, latest change), kept outside the database. A database
 * put back in time by copying a file instead of `--restore` is noticed at startup and gets a new epoch,
 * so devices reconcile instead of trusting cursors that now point at reused change numbers (S11).
 */
class HighWater(dataDir: Path) {
    data class Mark(val storeId: String, val epoch: Long, val cursor: Long)

    private val file = dataDir.resolve("db").resolve("high-water.properties")

    fun read(): Mark? {
        if (!file.exists()) return null
        val props = Properties().apply { file.inputStream().use(::load) }
        return Mark(props.getProperty("store_id") ?: return null, props.getProperty("epoch").toLong(), props.getProperty("cursor").toLong())
    }

    @Synchronized
    fun write(mark: Mark) {
        file.parent.createDirectories()
        val tmp = file.resolveSibling("high-water.properties.tmp")
        val props = Properties().apply {
            setProperty("store_id", mark.storeId)
            setProperty("epoch", mark.epoch.toString())
            setProperty("cursor", mark.cursor.toString())
        }
        tmp.outputStream().use { props.store(it, "Benbow: highest state of this store seen by the server. Don't edit.") }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun record(db: Database) = write(Mark(db.storeId, db.epoch, db.latestCursor()))

    /** At startup: moves [db] to a new epoch if it is behind what this store already reached. Returns true if it did. */
    fun check(db: Database): Boolean {
        val mark = read()
        val behind = mark != null &&
            mark.storeId == db.storeId &&
            (db.epoch < mark.epoch || (db.epoch == mark.epoch && db.latestCursor() < mark.cursor))
        if (behind) db.bumpEpoch(maxOf(db.epoch, mark.epoch) + 1)
        record(db)
        return behind
    }
}
