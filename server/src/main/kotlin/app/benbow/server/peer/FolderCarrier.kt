package app.benbow.server.peer

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * A folder some other program keeps the same in both homes (the Google Drive desktop app's mirror, Syncthing) as a
 * carrier (ADR-055). Parts wait as files in `Benbow/<id12>/` (where bodies lay before ADR-055, so an older server's
 * `<id>.bin` is found), letters in `Benbow/<id12>/letters/`. A file is written beside the folder in [staging] and moved
 * in, but the syncing program may still show it half-way at the other end: items here don't land whole, and nobody is
 * told of an arrival, so it is looked for every [pollMs].
 */
class FolderCarrier(val root: Path, private val staging: Path, private val pollMs: Long = POLL_MS) : Carrier {
    override val traits = CarrierTraits(maxItem = MAX_ITEM, dailyBudget = null, pushes = false, whole = false)

    private val lock = ReentrantLock()
    private val woken = lock.newCondition()
    private var wakeUp = false

    private fun dir(at: Address): Path {
        val owner = root.resolve("Benbow").resolve(at.short)
        return if (at.box == Box.LETTERS) owner.resolve("letters") else owner
    }

    override fun drop(to: Address, name: String, data: ByteArray) {
        val tmp = staging.createDirectories().resolve("${UUID.randomUUID()}.dropping")
        try {
            tmp.writeBytes(data)
            val target = dir(to).createDirectories().resolve(name)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) // another disk: a copy, seen half-way for a moment
            }
        } finally {
            tmp.deleteIfExists()
        }
    }

    override fun waiting(at: Address): List<Dropped> = files(at).map {
        Dropped(it.name, it.name, runCatching { it.fileSize() }.getOrDefault(0))
    }

    override fun read(at: Address, item: Dropped): ByteArray = dir(at).resolve(item.handle).readBytes()

    override fun remove(at: Address, items: Collection<Dropped>) {
        items.forEach { runCatching { dir(at).resolve(it.handle).deleteIfExists() } }
    }

    override fun names(at: Address): Set<String> = files(at).map { it.name }.toSet()

    override fun await(at: Address, timeoutMs: Long) = lock.withLock {
        if (!wakeUp) woken.await(minOf(timeoutMs, pollMs), TimeUnit.MILLISECONDS)
        wakeUp = false
    }

    override fun wake() = lock.withLock {
        wakeUp = true
        woken.signalAll()
    }

    override fun close() = wake()

    /** Files only, not the syncing program's own (hidden, temporary) ones. */
    private fun files(at: Address): List<Path> = runCatching { dir(at).listDirectoryEntries() }.getOrDefault(emptyList())
        .filter { it.isRegularFile() && !it.name.startsWith(".") && !it.name.startsWith("~") && !it.name.endsWith(".tmp") }

    companion object {
        /** Synced folders take large files well; parts are only there to be resumed and checked one by one. */
        const val MAX_ITEM = 32L * 1024 * 1024
        const val POLL_MS = 10_000L
    }
}
