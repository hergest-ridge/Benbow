package app.benbow.server.storage

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * The media folder knows whose it is (ADR-042): `<media>/.benbow-media` names the store. The server works in it only
 * while it carries this store's mark, and once the database knows files it never makes the folder itself, so a missing
 * disk, a drive letter now on another disk or a folder of someone else's photos can't take uploads, be adopted or lose
 * anything. Old folders get the mark once, when they hold a file the database knows (as built 7.19 P1).
 */
class MediaFolder(val root: Path, private val storeId: String, private val known: () -> List<KnownFile>) {
    /** A file the database expects in the folder, by its path there. */
    data class KnownFile(val path: String, val size: Long)

    sealed interface State {
        data object Ready : State

        /** The folder or its disk isn't there, or holds none of the files the database knows. */
        data object Missing : State

        /** Marked by another store. */
        data class Foreign(val storeId: String) : State

        /** Neither empty nor this store's: someone else's files. */
        data object NotOurs : State

        /** This store's folder from before a move to [to] (P4): left as it was, never used again. */
        data class Moved(val to: String) : State
    }

    /** Where the folder stands now; marks a new or an old folder of this store on the way (see the class). */
    @Synchronized
    fun check(): State {
        val mark = readMark(root, MARK)
        if (mark != null && mark == storeId) return markValue(root, MARK, MOVED_TO)?.let { State.Moved(it) } ?: State.Ready
        if (mark != null) return State.Foreign(mark)
        val expected = known()
        if (!root.isDirectory()) {
            if (expected.isNotEmpty()) return State.Missing
            // Nothing known yet, nothing to lose: a new store's folder.
            runCatching { root.createDirectories() }.getOrElse { return State.Missing }
            return marked()
        }
        if (expected.isEmpty()) return if (empty(root)) marked() else State.NotOurs
        val ours = expected.any { root.resolve(it.path).let { file -> file.isRegularFile() && file.fileSize() == it.size } }
        return when {
            ours -> marked()
            empty(root) -> State.Missing
            else -> State.NotOurs
        }
    }

    fun ready(): Boolean = check() == State.Ready

    /** Throws [MediaUnavailable] unless the folder is ready. */
    fun require() {
        val state = check()
        if (state != State.Ready) throw MediaUnavailable(describe(state))
    }

    /** The folder [relative] under the root, made one level at a time: a root that is gone stays gone. */
    fun directory(relative: String): Path = createUnder(root, relative)

    /** What the admin page says about [state]. */
    fun describe(state: State = check()): String = when (state) {
        State.Ready -> "на месте"
        State.Missing -> "папки $root нет или в ней нет файлов, которые знает база (диск не подключён или сменил букву?)"
        is State.Foreign -> "папка $root принадлежит другому хранилищу Benbow (${state.storeId.ifEmpty { "метка повреждена" }})"
        State.NotOurs -> "в папке $root лежат чужие файлы, сервер её не использует"
        is State.Moved -> "папка $root перенесена в ${state.to}: сервер её больше не использует"
    }

    private fun marked(): State {
        writeMark(root, MARK, "Benbow media folder", storeId)
        return State.Ready
    }

    private fun empty(dir: Path): Boolean = Files.list(dir).use { entries -> entries.allMatch { it.name == UPLOADS && it.isDirectory() } }

    companion object {
        const val MARK = ".benbow-media"
        const val UPLOADS = ".uploads"

        /** The mark's line naming where a moved folder went (P4). */
        const val MOVED_TO = "moved-to"
    }
}

class MediaUnavailable(message: String) : Exception(message)

/** The store id in [root]'s mark [name]: null without one, empty when it names none. */
internal fun readMark(root: Path, name: String): String? {
    if (!root.resolve(name).isRegularFile()) return null
    return markValue(root, name, "store") ?: ""
}

/** The value of [key] in [root]'s mark [name], or null. */
internal fun markValue(root: Path, name: String, key: String): String? = runCatching {
    root.resolve(name).readLines().firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")?.trim()
}.getOrNull()

/** Writes [root]'s mark [name] for [storeId] (and [extra] lines) in one step (temporary file, fsync, atomic rename). */
internal fun writeMark(root: Path, name: String, title: String, storeId: String, extra: Map<String, String> = emptyMap()) {
    val lines = extra.entries.joinToString("") { "${it.key}=${it.value}\n" }
    val text = "# $title. Keep this file: the Benbow server uses this folder only while it names its store.\nstore=$storeId\n$lines"
    val tmp = Files.createTempFile(root, "$name-", ".tmp")
    Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
        it.write(text.toByteArray())
        (it as? java.io.FileOutputStream)?.fd?.sync()
    }
    Files.move(tmp, root.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/**
 * The folder [relative] (`/`-separated) under [root], made one level at a time and never [root] itself, so a root that
 * vanished (an unplugged disk) fails here instead of being made again empty.
 */
internal fun createUnder(root: Path, relative: String): Path {
    if (!root.isDirectory()) throw NoSuchFileException(root.toString(), null, "the folder is gone")
    var dir = root
    for (part in relative.split('/').filter { it.isNotEmpty() }) {
        dir = dir.resolve(part)
        if (!dir.exists()) runCatching { Files.createDirectory(dir) }.onFailure { if (!dir.isDirectory()) throw it }
    }
    return dir
}
