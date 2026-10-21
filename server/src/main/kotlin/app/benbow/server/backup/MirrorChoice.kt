package app.benbow.server.backup

import app.benbow.server.storage.readMark
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes

/**
 * Whether a folder may become the second disk (ADR-042, as built 7.19 P3), checked before anything is saved: a full path
 * to a folder (not a whole disk) on a local disk of this PC (not a network share, not Google Drive's streaming disk), NTFS,
 * ReFS or exFAT, not inside the data or media folder nor holding one, not on their volume, empty or this store's mirror
 * already, with room for what is to be copied, and writable by the server itself: a test file written, synced, read back
 * and removed. A folder that doesn't exist yet is made, one level only; it is removed again when the check fails.
 */
class MirrorChoice internal constructor(
    private val storeId: String,
    /** What the second disk must not share a disk with, by what the owner calls it: the database, the media. */
    private val apart: List<Pair<String, Path>>,
    private val disks: Disks,
    /** Bytes a first pass would copy: the media, the snapshots, the export. */
    private val needed: () -> Long,
    private val filledBefore: (Path) -> Boolean,
) {
    sealed interface Verdict {
        data class Fine(val path: Path, val warnings: List<String>) : Verdict

        data class Refused(val reason: String) : Verdict
    }

    fun check(text: String): Verdict {
        val typed = text.trim().trim('"').trim()
        if (typed.isEmpty()) return Verdict.Refused("впишите путь к папке")
        if (typed.startsWith("\\\\") || typed.startsWith("//")) {
            return Verdict.Refused("сетевые папки не подходят: служба сервера их не видит, а копия зависела бы от сети")
        }
        val path = runCatching { Path.of(typed).toAbsolutePath().normalize() }.getOrNull()
            ?.takeIf { Path.of(typed).isAbsolute } ?: return Verdict.Refused("нужен полный путь, например H:\\Benbow-mirror")
        val parent = path.parent ?: return Verdict.Refused("укажите папку, а не весь диск: например ${path}Benbow-mirror")
        if (path.exists() && !path.isDirectory()) return Verdict.Refused("$path — это файл, а не папка")
        if (!path.exists() && !parent.isDirectory()) return Verdict.Refused("папки $parent нет: диск не подключён?")
        if (disks.streaming(path)) return Verdict.Refused("это потоковый диск Google Диска: файлы на нём живут в облаке")
        val fileSystem = disks.fileSystem(path).orEmpty()
        if (fileSystem.uppercase() !in FILE_SYSTEMS) {
            return Verdict.Refused("файловая система ${fileSystem.ifEmpty { "неизвестна" }} не подходит: нужна NTFS, ReFS или exFAT")
        }
        apart.firstOrNull { (_, other) -> path.startsWith(other) || other.startsWith(path) }?.let {
            return Verdict.Refused("папка второго диска не может быть внутри папки, где ${it.first}, или содержать её")
        }
        val volume = disks.volume(path)
        apart.firstOrNull { volume != null && disks.volume(it.second) == volume }?.let {
            return Verdict.Refused("это тот же диск, где ${it.first}: от поломки диска такая копия не защищает")
        }
        val ours = when (val mark = readMark(path, MirrorFolder.MARK)) {
            null -> path.exists() && path.listDirectoryEntries().isNotEmpty() && filledBefore(path)
            storeId -> true
            else -> return Verdict.Refused("папка принадлежит другому хранилищу Benbow (${mark.ifEmpty { "метка повреждена" }})")
        }
        if (!ours && path.exists() && path.listDirectoryEntries().isNotEmpty()) {
            return Verdict.Refused("в папке уже что-то лежит: выберите пустую или впишите новую, например ${path.resolve("Benbow-mirror")}")
        }
        val free = disks.free(path) ?: 0
        val need = needed()
        if (!ours && free < need + RESERVE) {
            return Verdict.Refused("не хватит места: нужно около ${gb(need + RESERVE)} ГБ, свободно ${gb(free)} ГБ")
        }
        writable(path)?.let { return Verdict.Refused(it) }
        return Verdict.Fine(path, warnings(path, free - if (ours) 0 else need))
    }

    /** Null when the server could write, sync, read back and remove a file there; else why not. */
    private fun writable(path: Path): String? {
        val made = !path.exists()
        return try {
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
            if (made) runCatching { Files.deleteIfExists(path) }
            "сервер не может туда писать (${e::class.simpleName}: ${e.message})"
        }
    }

    private fun warnings(path: Path, freeAfter: Long): List<String> = buildList {
        if (freeAfter < LOW_SPACE) add("после первой копии там останется около ${gb(freeAfter.coerceAtLeast(0))} ГБ")
        val disk = disks.physical(path)
        if (disk != null) {
            apart.firstOrNull { disks.physical(it.second) == disk }?.let {
                add("это другой раздел того же физического диска, где ${it.first}: от поломки диска это не защищает")
            }
        }
    }

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)

    private companion object {
        val FILE_SYSTEMS = setOf("NTFS", "REFS", "EXFAT")
        const val RESERVE = 1_073_741_824L
        const val LOW_SPACE = 5L * 1_073_741_824
    }
}
