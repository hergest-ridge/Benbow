package app.benbow.server.backup

import app.benbow.server.storage.readMark
import app.benbow.server.storage.writeMark
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The second disk's folder knows whose it is too (ADR-042): `<mirror>/.benbow-mirror` names the store. A pass writes only
 * into a folder with this store's mark, or marks one that is empty or that this store filled before ([filledBefore]: a
 * snapshot of the same name and size, a media file the database knows). A folder a pass once filled is never made again
 * when it is gone (the disk unplugged, another disk under its letter): the pass skips it and the admin page says so. Its
 * last passes are kept in `backups/mirror.json`, so a restart doesn't forget them (as built 7.19 P1). A folder on the
 * same volume as the database or the media ([apart]) is no second copy and gets nothing (P2).
 */
internal class MirrorFolder(
    val root: Path,
    private val storeId: String,
    private val passesFile: Path,
    private val filledBefore: (Path) -> Boolean,
    private val apart: () -> List<Pair<String, Path>> = { emptyList() },
    private val disks: Disks = Disks.System,
) {
    sealed interface State {
        data object Ready : State

        data object NotAbsolute : State

        /** On the same volume as [what] (the database or the media): no protection against a dead disk. */
        data class SameVolume(val what: String) : State

        /** Filled before and gone now. */
        data object Missing : State

        data class Foreign(val storeId: String) : State

        data object NotOurs : State
    }

    /** When a pass last copied everything there ([lastAt]) and the snapshots at least ([dbAt]). */
    @Serializable
    data class Passes(val path: String, val lastAt: Long? = null, val dbAt: Long? = null)

    fun passes(): Passes = runCatching { Json.decodeFromString(Passes.serializer(), passesFile.readText()) }
        .getOrNull()?.takeIf { it.path == root.toString() } ?: Passes(root.toString())

    @Synchronized
    fun check(): State {
        if (!root.isAbsolute) return State.NotAbsolute
        val volume = disks.volume(root)
        if (volume != null) apart().firstOrNull { disks.volume(it.second) == volume }?.let { return State.SameVolume(it.first) }
        val mark = readMark(root, MARK)
        if (mark != null) return if (mark == storeId) State.Ready else State.Foreign(mark)
        if (!root.isDirectory()) {
            if (passes().dbAt != null) return State.Missing
            runCatching { root.createDirectories() }.getOrElse { return State.Missing }
            return marked()
        }
        val empty = Files.list(root).use { it.findAny().isEmpty }
        return if (empty || filledBefore(root)) marked() else State.NotOurs
    }

    /** A pass copied the snapshots at [dbAt] and, when [lastAt] is set, the media too. */
    @Synchronized
    fun record(dbAt: Long, lastAt: Long?) {
        val passes = passes().copy(dbAt = dbAt).let { if (lastAt != null) it.copy(lastAt = lastAt) else it }
        passesFile.parent.createDirectories()
        val tmp = passesFile.resolveSibling("${passesFile.fileName}.tmp")
        tmp.writeText(Json.encodeToString(Passes.serializer(), passes))
        Files.move(tmp, passesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun describe(state: State): String = when (state) {
        State.Ready -> "на месте"

        State.NotAbsolute -> "путь $root не полный (в benbow.properties пишется с /, например H:/Benbow-mirror)"

        is State.SameVolume ->
            "папка $root на том же диске, что и ${state.what}: от поломки диска такая копия не защищает, она не делается. " +
                "Укажите папку на другом диске"

        State.Missing -> "папки $root нет: диск не подключён или сменил букву. Копия не делается, пока она не вернётся"

        is State.Foreign -> "папка $root принадлежит другому хранилищу Benbow (${state.storeId.ifEmpty { "метка повреждена" }})"

        State.NotOurs -> "в папке $root лежат чужие файлы: копия туда не пишется. Укажите пустую папку"
    }

    private fun marked(): State {
        writeMark(root, MARK, "Benbow backup mirror", storeId)
        return State.Ready
    }

    companion object {
        const val MARK = ".benbow-mirror"
        const val PASSES = "mirror.json"

        /** Whether [file] is there with [size] bytes. */
        fun holds(file: Path, size: Long): Boolean = file.isRegularFile() && Files.size(file) == size
    }
}
