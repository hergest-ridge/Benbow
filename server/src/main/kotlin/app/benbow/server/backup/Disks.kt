package app.benbow.server.backup

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Which disk a folder is on (ADR-042): its volume, the physical disk under it where Windows can tell, and the room left.
 * A path that doesn't exist yet counts as the nearest folder above it that does.
 */
interface Disks {
    /** The volume, or null when nothing of the path exists (a disk that isn't connected). */
    fun volume(path: Path): String?

    /** The physical disk's number, or null when it can't be told. */
    fun physical(path: Path): Int?

    /** Bytes free for this process, or null when nothing of the path exists. */
    fun free(path: Path): Long?

    /** The file system (NTFS, ReFS, exFAT, FAT32...), or null when nothing of the path exists. */
    fun fileSystem(path: Path): String?

    /** Whether the path is on the Google Drive app's streaming disk, which only shows files kept in the cloud. */
    fun streaming(path: Path): Boolean

    object System : Disks {
        private val numbers = ConcurrentHashMap<Char, Pair<Int?, Long>>()

        override fun volume(path: Path): String? = existing(path)?.let { dir ->
            runCatching {
                val store = Files.getFileStore(dir)
                runCatching { store.getAttribute("volume:vsn")?.toString() }.getOrNull() ?: store.toString()
            }.getOrNull()
        }

        override fun free(path: Path): Long? = existing(path)?.let { runCatching { Files.getFileStore(it).usableSpace }.getOrNull() }

        override fun fileSystem(path: Path): String? = existing(path)?.let { runCatching { Files.getFileStore(it).type() }.getOrNull() }

        /** The Drive app labels its disk "<account> - Google Drive", cut short at times. */
        override fun streaming(path: Path): Boolean = existing(path)?.let {
            runCatching { Files.getFileStore(it).name().contains("Googl", ignoreCase = true) }.getOrNull()
        } == true

        /** Windows only: `Get-Partition` for the drive letter, asked at most once an hour per letter. */
        override fun physical(path: Path): Int? {
            val letter = path.toAbsolutePath().root?.toString()?.takeIf { DRIVE.matches(it) }?.first()?.uppercaseChar() ?: return null
            val now = java.lang.System.currentTimeMillis()
            numbers[letter]?.takeIf { now - it.second < HOUR }?.let { return it.first }
            val number = runCatching {
                val process = ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "(Get-Partition -DriveLetter $letter).DiskNumber",
                ).redirectErrorStream(true).start()
                if (process.waitFor(15, TimeUnit.SECONDS)) {
                    process.inputStream.bufferedReader().readText().trim().toIntOrNull()
                } else {
                    process.destroyForcibly()
                    null
                }
            }.getOrNull()
            numbers[letter] = number to now
            return number
        }

        private fun existing(path: Path): Path? = generateSequence(path.toAbsolutePath()) { it.parent }.firstOrNull { it.exists() }

        private val DRIVE = Regex("""[A-Za-z]:\\""")
        private const val HOUR = 3_600_000L
    }
}

/**
 * Null when the server itself can write, sync, read back and remove a file in [path] (made first when missing, one level
 * only, and removed again when the probe fails); else why not, in words for the admin page (ADR-042).
 */
internal fun probeWrite(path: Path): String? {
    val made = !path.exists()
    val failure = try {
        if (made) Files.createDirectory(path)
        val probe = path.resolve(".benbow-write-test-${UUID.randomUUID()}")
        val bytes = UUID.randomUUID().toString().toByteArray()
        try {
            Files.newOutputStream(probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.write(bytes)
                (it as? java.io.FileOutputStream)?.fd?.sync()
            }
            if (!probe.readBytes().contentEquals(bytes)) "записанное туда не читается обратно" else null
        } finally {
            probe.deleteIfExists()
        }
    } catch (e: Exception) {
        "сервер не может туда писать (${e::class.simpleName}: ${e.message})"
    }
    if (failure != null && made) runCatching { Files.deleteIfExists(path) }
    return failure
}
