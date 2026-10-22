package app.benbow.server.offsite

import app.benbow.server.storage.markValue
import app.benbow.server.storage.readMark
import app.benbow.server.storage.writeMark
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The folder of the copy outside the house knows whose it is and which key it holds (ADR-043, as built 7.7 E2):
 * `.benbow-offsite` names the store and the recipient. A pass writes only into a folder with both; an empty or new folder
 * is marked at its first pass. A folder a pass once filled is never made again when it is gone (a cloud app that moved
 * its folder, a disk taken away): the pass skips it and says so. The last passes stay in `backups/offsite.json`.
 */
internal class OffsiteFolder(val root: Path, private val storeId: String, private val recipient: String, private val passesFile: Path) {
    sealed interface State {
        data object Ready : State

        data object NotAbsolute : State

        /** Filled before and gone now. */
        data object Missing : State

        data class Foreign(val storeId: String) : State

        /** This store's copy, for another key: one key per copy. */
        data class OtherKey(val recipient: String) : State

        data object NotOurs : State
    }

    /**
     * When a pass last copied everything ([lastAt]), the snapshots at least ([dbAt]); the SHA-256 of the small files it
     * rewrites only when they change (the purge journal, the export and settings archives), so the cloud gets no churn.
     */
    @Serializable
    data class Passes(
        val path: String,
        val recipient: String,
        val lastAt: Long? = null,
        val dbAt: Long? = null,
        val written: Map<String, String> = emptyMap(),
        /** When a check last found everything there opening as it should (E3), and what the last one found wrong. */
        val checkedAt: Long? = null,
        val checkProblem: String? = null,
    )

    fun passes(): Passes = runCatching { Json.decodeFromString(Passes.serializer(), passesFile.readText()) }
        .getOrNull()?.takeIf { it.path == root.toString() && it.recipient == recipient } ?: Passes(root.toString(), recipient)

    @Synchronized
    fun check(): State {
        if (!root.isAbsolute) return State.NotAbsolute
        val mark = readMark(root, MARK)
        if (mark != null) {
            if (mark != storeId) return State.Foreign(mark)
            val theirs = markValue(root, MARK, "recipient").orEmpty()
            return if (theirs == recipient) State.Ready else State.OtherKey(theirs)
        }
        if (!root.isDirectory()) {
            if (passes().dbAt != null) return State.Missing
            runCatching { root.createDirectories() }.getOrElse { return State.Missing }
        }
        val empty = Files.list(root).use { it.findAny().isEmpty }
        if (!empty) return State.NotOurs
        writeMark(root, MARK, "Benbow copy outside the house, encrypted (age)", storeId, mapOf("recipient" to recipient))
        return State.Ready
    }

    @Synchronized
    fun record(passes: Passes) {
        passesFile.parent.createDirectories()
        val tmp = passesFile.resolveSibling("${passesFile.fileName}.tmp")
        tmp.writeText(Json.encodeToString(Passes.serializer(), passes))
        Files.move(tmp, passesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun describe(state: State): String = when (state) {
        State.Ready -> "на месте"
        State.NotAbsolute -> "путь $root не полный"
        State.Missing -> "папки $root нет: диск не подключён или программа облака её переместила. Копия не делается, пока она не вернётся"
        is State.Foreign -> "папка $root принадлежит другому хранилищу Benbow (${state.storeId.ifEmpty { "метка повреждена" }})"
        is State.OtherKey -> "в папке $root копия под другим ключом (${state.recipient.ifEmpty { "метка повреждена" }}): у копии один ключ"
        State.NotOurs -> "в папке $root лежат чужие файлы: копия туда не пишется. Укажите пустую папку"
    }

    companion object {
        const val MARK = ".benbow-offsite"
        const val PASSES = "offsite.json"
    }
}
