package app.benbow.server.offsite

import app.benbow.server.ServerConfig
import app.benbow.server.backup.Disks
import app.benbow.server.backup.probeWrite
import app.benbow.server.storage.markValue
import app.benbow.server.storage.readMark
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes

/**
 * Turning the copy outside the house on and off, and its passphrase (ADR-043, as built 7.7 E4). Turning it on checks the
 * folder, makes the key once (it stays when the copy is turned off, so the code and the passphrase stay good), and
 * starts the copy only after the owner has typed the end of the code back or given a passphrase twice.
 */
class OffsiteSetup(
    private val copy: OffsiteCopy,
    private val dataDir: Path,
    private val storeId: String,
    /** Folders the copy must not be inside of nor hold, by what the owner calls them: the data, the media, the second disk. */
    private val apart: () -> List<Pair<String, Path>>,
    private val disks: Disks = Disks.System,
    /** A pass in the background (the backup thread's). */
    private val runSoon: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** scrypt's for `key.age` (tests use a light one). */
    private val workFactor: Int = OffsiteKey.WORK_FACTOR,
) {
    sealed interface Verdict {
        data class Fine(val path: Path) : Verdict

        data class Refused(val reason: String) : Verdict
    }

    /** Between the two steps of turning it on: the folder checked, the key to show (made now when [fresh]). */
    data class Pending(val id: String, val path: Path, val key: OffsiteKey, val fresh: Boolean, val passphrase: Boolean, val at: Long)

    sealed interface Outcome {
        data object Done : Outcome

        data class Refused(val reason: String) : Outcome
    }

    @Volatile
    var pending: Pending? = null
        private set

    /** Where the copy may go: a full path to a local folder, empty or this copy's own, apart from home's folders, writable. */
    fun check(text: String): Verdict {
        val typed = text.trim().trim('"').trim()
        if (typed.isEmpty()) return Verdict.Refused("впишите путь к папке")
        if (typed.startsWith("\\\\") || typed.startsWith("//")) {
            return Verdict.Refused(
                "сетевые папки не подходят: служба сервера их не видит. Нужна папка программы облака или диска на этом компьютере",
            )
        }
        val path = runCatching { Path.of(typed) }.getOrNull()?.takeIf { it.isAbsolute }?.normalize()
            ?: return Verdict.Refused("нужен полный путь, например C:\\Users\\Имя\\Google Drive\\Benbow")
        val parent = path.parent ?: return Verdict.Refused("укажите папку, а не весь диск")
        if (path.exists() && !path.isDirectory()) return Verdict.Refused("$path — это файл, а не папка")
        if (!path.exists() && !parent.isDirectory()) return Verdict.Refused("папки $parent нет")
        if (disks.streaming(path)) {
            return Verdict.Refused(
                "это потоковый диск Google Диска: файлы на нём только в облаке. Выберите папку, которую программа облака зеркалирует на компьютер",
            )
        }
        apart().firstOrNull { (_, other) -> path.startsWith(other) || other.startsWith(path) }?.let {
            return Verdict.Refused("папка копии не может быть внутри папки, где ${it.first}, или содержать её")
        }
        if (path.isDirectory() && path.listDirectoryEntries().isNotEmpty()) {
            when (readMark(path, OffsiteFolder.MARK)) {
                null -> return Verdict.Refused(
                    "в папке уже что-то лежит: выберите пустую или впишите новую, например ${path.resolve("Benbow")}",
                )

                storeId -> {
                    val key = copy.key()
                    if (key == null || markValue(path, OffsiteFolder.MARK, "recipient") != key.recipient) {
                        return Verdict.Refused("в папке копия этого хранилища под другим ключом: выберите другую папку")
                    }
                }

                else -> return Verdict.Refused("папка принадлежит другому хранилищу Benbow")
            }
        }
        // A look makes nothing: a folder the write test made goes again (the copy makes it when it is turned on).
        val made = !path.exists()
        probeWrite(path)?.let { return Verdict.Refused(it) }
        if (made) Files.deleteIfExists(path)
        return Verdict.Fine(path)
    }

    /** The first step: the folder checked, the key (the one there is, or a new one) put aside to be shown. */
    fun begin(text: String, passphrase: Boolean): Verdict = when (val verdict = check(text)) {
        is Verdict.Refused -> verdict

        is Verdict.Fine -> {
            val existing = copy.key()
            pending =
                Pending(UUID.randomUUID().toString(), verdict.path, existing ?: OffsiteKey.generate(), existing == null, passphrase, now())
            verdict
        }
    }

    /** The pending step [id], while it is fresh. */
    fun pending(id: String): Pending? = pending?.takeIf { it.id == id && now() - it.at < PENDING_MILLIS }

    /** The code's last [TAIL] characters typed back: the owner has it written down. */
    fun confirmCode(id: String, typed: String): Outcome {
        val p = pending(id) ?: return Outcome.Refused("шаг устарел: начните заново")
        val tail = typed.filterNot(Char::isWhitespace).uppercase()
        if (tail.length < TAIL || !p.key.secret.endsWith(tail)) return Outcome.Refused("это не конец кода: сверьте его с тем, что записали")
        return enable(p, null)
    }

    fun confirmPassphrase(id: String, first: String, second: String): Outcome {
        val p = pending(id) ?: return Outcome.Refused("шаг устарел: начните заново")
        weak(first, second)?.let { return Outcome.Refused(it) }
        return enable(p, first)
    }

    /** No copy from now on; its folder and the key stay. */
    fun turnOff() {
        ServerConfig.update(dataDir, mapOf("backup.offsite" to ""))
        copy.root = null
    }

    /** A new passphrase for the copy that is on: `key.age` written again (the code stays as it was). */
    fun setPassphrase(first: String, second: String): Outcome {
        val root = copy.root ?: return Outcome.Refused("копия вне дома выключена")
        val key = copy.key() ?: return Outcome.Refused("ключа нет")
        weak(first, second)?.let { return Outcome.Refused(it) }
        val sealed = sealed(key, first) ?: return memory()
        return place(root, key, sealed, first) ?: Outcome.Done
    }

    /** Whether [passphrase] opens the copy's `key.age` to this key; a match counts as the passphrase remembered. */
    fun checkPassphrase(passphrase: String): Boolean? {
        val root = copy.root ?: return null
        val key = copy.key() ?: return null
        val sealed = root.resolve(KEY_AGE).takeIf { it.exists() } ?: return null
        val opened = try {
            OffsiteKey.unseal(sealed.readBytes(), passphrase)
        } catch (e: OffsiteKey.WrongPassphrase) {
            return false
        }
        if (opened != key) return false
        copy.notePassphrase(now())
        return true
    }

    /** Whether the copy that is on has a passphrase (its `key.age`). */
    fun hasPassphrase(): Boolean = copy.root?.resolve(KEY_AGE)?.exists() == true

    private fun enable(p: Pending, passphrase: String?): Outcome {
        // scrypt first: when the server can't afford it, nothing has changed yet.
        val sealed = passphrase?.let { sealed(p.key, it) ?: return memory() }
        val existing = copy.key()
        when {
            !p.fresh || existing == p.key -> Unit
            existing != null -> return Outcome.Refused("ключ уже есть: начните заново")
            else -> p.key.save(copy.keyFile)
        }
        val folder = OffsiteFolder(p.path, storeId, p.key.recipient, dataDir.resolve("backups").resolve(OffsiteFolder.PASSES))
        val state = folder.check()
        if (state != OffsiteFolder.State.Ready) return Outcome.Refused(folder.describe(state))
        if (passphrase != null && sealed != null) place(p.path, p.key, sealed, passphrase)?.let { return it }
        ServerConfig.update(dataDir, mapOf("backup.offsite" to p.path.toString().replace('\\', '/')))
        copy.root = p.path
        pending = null
        runSoon()
        return Outcome.Done
    }

    private fun sealed(key: OffsiteKey, passphrase: String): ByteArray? = try {
        key.seal(passphrase, workFactor)
    } catch (e: IllegalStateException) {
        null
    }

    private fun memory() = Outcome.Refused("серверу не хватает памяти для пароля: обновите его установщиком (он даёт службе 1 ГБ)")

    /** `key.age` in [root] through a temporary file, read back and opened before it takes its name. */
    private fun place(root: Path, key: OffsiteKey, sealed: ByteArray, passphrase: String): Outcome? {
        val tmp = root.resolve("$KEY_AGE.part")
        Files.write(tmp, sealed)
        if (OffsiteKey.unseal(tmp.readBytes(), passphrase) != key) {
            Files.deleteIfExists(tmp)
            return Outcome.Refused("key.age не открылся после записи: ничего не изменено")
        }
        Files.move(tmp, root.resolve(KEY_AGE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        copy.notePassphrase(now())
        return null
    }

    /** Why a passphrase won't do, or null. */
    private fun weak(first: String, second: String): String? = when {
        first != second -> "пароли не совпадают"

        first.codePointCount(
            0,
            first.length,
        ) < MIN_LENGTH -> "пароль короче $MIN_LENGTH знаков: его подберут, если копия попадёт в чужие руки"

        first.isBlank() || first.trim() != first -> "пароль не должен начинаться или кончаться пробелом"

        else -> null
    }

    companion object {
        const val KEY_AGE = "key.age"
        const val TAIL = 6
        const val MIN_LENGTH = 12
        const val PENDING_MILLIS = 30L * 60_000

        /** A passphrase not checked for this long gets a reminder on the admin page. */
        const val REMIND_MILLIS = 90L * 24 * 3_600_000
    }
}
