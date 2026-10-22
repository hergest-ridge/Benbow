package app.benbow.server.backup

import app.benbow.server.ServerConfig
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.createUnder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * What the mirror needs besides snapshots and media to stand the server up again on another PC (ADR-042, as built 7.19
 * P2): the settings and the keys under `config/` (the TLS keystore, so devices know the server by the same certificate;
 * the peer identity), the export as it is now (readable without the app), and a README. Each file is copied when it
 * changed, through a temporary file, checked by SHA-256. Only `export/` there follows deletions: it is derived.
 */
internal class RecoveryKit(private val dataDir: Path, private val mirror: Path, private val storeId: String) {
    fun copy() {
        for (relative in CONFIG) {
            val source = dataDir.resolve(relative)
            if (!source.isRegularFile()) continue
            val folder = createUnder(mirror, "$CONFIG_DIR/" + relative.substringBeforeLast('/', ""))
            copyChecked(source, folder.resolve(source.fileName.toString()))
        }
        syncExport()
        val readme = mirror.resolve(README)
        val text = readme(storeId)
        if (!readme.isRegularFile() || readme.readText() != text) {
            val tmp = readme.resolveSibling("$README.tmp")
            tmp.writeText(text)
            Files.move(tmp, readme, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** `export/` as it is: changed files copied (by size and time), files gone from it removed there too. */
    private fun syncExport() {
        val source = dataDir.resolve(EXPORT)
        if (!source.isDirectory()) return
        val copy = createUnder(mirror, EXPORT)
        val wanted = HashSet<String>()
        for (file in Files.walk(source).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }) {
            val relative = source.relativize(file).joinToString("/")
            wanted += relative
            val there = copy.resolve(relative)
            val same = there.isRegularFile() && there.fileSize() == file.fileSize() &&
                Files.getLastModifiedTime(there) == Files.getLastModifiedTime(file)
            if (!same) {
                copyChecked(
                    file,
                    createUnder(copy, relative.substringBeforeLast('/', "")).resolve(file.fileName.toString()),
                    always = true,
                )
            }
        }
        Files.walk(copy).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
            .filter { copy.relativize(it).joinToString("/") !in wanted }
            .forEach(Files::deleteIfExists)
    }

    companion object {
        const val CONFIG_DIR = "config"
        const val EXPORT = "export"
        const val README = "README.txt"

        /** The data folder's files the kit keeps under `config/`, by their place in the data folder. */
        val CONFIG = listOf(ServerConfig.FILE_NAME, "tls/server.keystore", "peer/identity.json", "keys/offsite-key.txt")

        /** [source] to [copy] through a temporary file with its time, checked by SHA-256; skipped when [copy] holds the same. */
        fun copyChecked(source: Path, copy: Path, always: Boolean = false) {
            val sha = BlobStore.sha256(source)
            if (!always && copy.exists() && copy.fileSize() == source.fileSize() && BlobStore.sha256(copy) == sha) return
            val tmp = copy.resolveSibling("${copy.fileName}.tmp")
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            check(BlobStore.sha256(tmp) == sha) { "Mirror copy of ${source.fileName} is corrupt" }
            Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        fun readme(storeId: String) = """
            |Benbow: копия на втором диске
            |=============================
            |
            |Эту папку пишет сервер Benbow (хранилище $storeId). В ней всё, чтобы поднять его заново на другом компьютере:
            |
            |  db\              проверенные снимки базы и журнал удалений навсегда (purge-journal.jsonl)
            |  calendar\        снимки календаря
            |  media\           все фото, видео, звуки и файлы, как на сервере (год\месяц\...)
            |  export\          все записи в Markdown и JSON: читаются без программы
            |  config\          настройки сервера, его сертификат (по нему устройства узнают сервер) и ключи для друзей
            |  .benbow-mirror   метка: по ней сервер узнаёт свою папку. Не удаляйте её.
            |
            |В config\ лежат пароль и ключи сервера: берегите эту папку так же, как сам компьютер.
            |
            |Если компьютер с сервером сломался
            |  1. Возьмите пакет сервера Benbow (папку benbow-server) и откройте её на новом компьютере.
            |  2. От имени администратора: restore-from-mirror.cmd "<эта папка>" [папка данных] [папка медиа]
            |     Скрипт только читает эту папку. Он соберёт папку данных (по умолчанию C:\ProgramData\Benbow),
            |     поставит последний проверенный снимок и скопирует медиафайлы, проверяя каждый.
            |  3. Затем: install-service.cmd [папка данных]
            |  4. Устройства увидят, что сервер восстановлен, и сами дошлют то, чего нет в снимке. Проще всего дать
            |     новому компьютеру тот же адрес в сети, что был у старого; иначе подключите устройства заново.
            |Старый сервер к этому времени должен быть выключен: два сервера с одними ключами работать не должны.
            |Сервер для друга ставится с нуля, а не из этой папки.
            |
            |Если программы нет вовсе: записи читаются в export\, файлы лежат в media\.
            |
        """.trimMargin().replace("\n", "\r\n")
    }
}
