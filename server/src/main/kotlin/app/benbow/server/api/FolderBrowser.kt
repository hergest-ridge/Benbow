package app.benbow.server.api

import kotlinx.serialization.Serializable
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

@Serializable
internal data class FolderEntry(val name: String, val path: String)

/** One step of the folder picker: [path] null is "this computer" (its disks and the folders that look like Drive). */
@Serializable
internal data class FolderList(
    val path: String? = null,
    val parent: String? = null,
    val folders: List<FolderEntry> = emptyList(),
    val disks: List<FolderEntry> = emptyList(),
    val drive: List<FolderEntry> = emptyList(),
    val error: String? = null,
)

/**
 * Folders of the server PC for the admin page's folder picker (the browser can't tell a page a folder's full path).
 * Only names of folders, only for the server PC itself; hidden and system folders are left out.
 */
internal object FolderBrowser {
    private const val MAX_FOLDERS = 500

    fun list(path: String?): FolderList {
        if (path.isNullOrBlank()) return computer()
        val dir = runCatching { Path.of(path) }.getOrNull()?.takeIf { it.isAbsolute && it.isDirectory() }
            ?: return computer().copy(error = "Папки $path нет")
        val folders = runCatching {
            children(dir)
        }.getOrElse { return FolderList(dir.toString(), dir.parent?.toString(), error = "Нет доступа к этой папке") }
        return FolderList(dir.toString(), dir.parent?.toString(), folders.map { FolderEntry(it.name, it.toString()) })
    }

    private fun computer() = FolderList(
        disks = disks().map {
            FolderEntry(if (streaming(it)) "$it — потоковый Google Диск, не подходит" else it.toString(), it.toString())
        },
        drive = driveMirrors(),
    )

    /** The Drive app's own virtual disk ("Потоковая передача", labelled "<account> - Google Drive", cut short at times). */
    private fun streaming(disk: Path) =
        runCatching { Files.getFileStore(disk).name().contains("Googl", ignoreCase = true) }.getOrDefault(false)

    private fun disks(): List<Path> = FileSystems.getDefault().rootDirectories.filter {
        runCatching { it.isDirectory() }.getOrDefault(false)
    }

    private fun children(dir: Path): List<Path> = Files.newDirectoryStream(dir).use { stream ->
        stream.filter(::visible).sortedBy { it.name.lowercase() }.take(MAX_FOLDERS)
    }

    private fun visible(p: Path): Boolean = runCatching {
        val name = p.fileName?.toString().orEmpty()
        p.isDirectory() && !Files.isHidden(p) && !name.startsWith("$") && !name.startsWith(".") && name != "System Volume Information"
    }.getOrDefault(false)

    /**
     * Folders that look like the Google Drive desktop app's mirror: named so (Google, My Drive, «Мой диск»), or holding the
     * `.tmp.drivedownload` / `.tmp.driveupload` some versions keep. Looked for one level under each real disk and two
     * under each user's profile; the app's own streaming disks are left out.
     */
    fun driveMirrors(): List<FolderEntry> {
        val candidates = buildList {
            for (disk in disks().filterNot(::streaming)) {
                runCatching { children(disk) }.getOrDefault(emptyList()).forEach(::add)
                val users = disk.resolve("Users")
                runCatching { children(users) }.getOrDefault(emptyList()).forEach { home ->
                    runCatching { children(home) }.getOrDefault(emptyList()).forEach(::add)
                }
            }
        }
        return candidates.filter { looksLikeDrive(it.name) || mirrored(it) }.distinct().take(10).map { FolderEntry(it.name, it.toString()) }
    }

    internal fun looksLikeDrive(name: String): Boolean {
        val n = name.lowercase()
        return "google" in n || "my drive" in n || "мой диск" in n
    }

    internal fun mirrored(p: Path) = runCatching {
        p.resolve(".tmp.drivedownload").isDirectory() ||
            p.resolve(".tmp.driveupload").isDirectory()
    }
        .getOrDefault(false)
}
