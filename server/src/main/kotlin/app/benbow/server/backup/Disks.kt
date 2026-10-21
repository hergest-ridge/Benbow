package app.benbow.server.backup

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

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

    object System : Disks {
        private val numbers = ConcurrentHashMap<Char, Pair<Int?, Long>>()

        override fun volume(path: Path): String? = existing(path)?.let { dir ->
            runCatching {
                val store = Files.getFileStore(dir)
                runCatching { store.getAttribute("volume:vsn")?.toString() }.getOrNull() ?: store.toString()
            }.getOrNull()
        }

        override fun free(path: Path): Long? = existing(path)?.let { runCatching { Files.getFileStore(it).usableSpace }.getOrNull() }

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
