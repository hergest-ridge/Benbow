package app.benbow.server.offsite

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `README.txt` in the copy outside the house (ADR-043, as built 7.7 E6): what the folder is, how to stand the server up
 * again from it, and how to open it with the stock `age` alone. The only plain file there besides the mark; it names the
 * store and the public key, never the secret one. Written again only when its text changes.
 */
internal object OffsiteReadme {
    const val FILE = "README.txt"

    fun write(root: Path, storeId: String, recipient: String) {
        val file = root.resolve(FILE)
        val text = text(storeId, recipient)
        if (file.isRegularFile() && file.readText() == text) return
        val tmp = root.resolve("$FILE.part")
        tmp.writeText(text)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun text(storeId: String, recipient: String) = """
        |Benbow: копия вне дома (зашифрована)
        |====================================
        |
        |Эту папку пишет сервер Benbow (хранилище $storeId). Всё в ней, кроме этого файла и метки, зашифровано
        |программой age (age-encryption.org) для ключа $recipient.
        |Без ключа файлы не прочитает никто, в том числе облако.
        |
        |  db\               снимки базы (*.sqlite.age), их список и журнал удалений навсегда
        |  calendar\         снимки календаря
        |  media\            все фото, видео, звуки и файлы; имена скрыты, настоящие знает база
        |  export.zip.age    все записи в Markdown и JSON: читаются без программы
        |  config.zip.age    настройки сервера, его сертификат и ключи для друзей
        |  key.age           ключ копии под паролем (есть, если копии задан пароль)
        |  .benbow-offsite   метка: по ней сервер узнаёт свою папку. Не удаляйте её.
        |
        |Ключ — это код восстановления (AGE-SECRET-KEY-1…), записанный на бумаге или скачанный файлом
        |benbow-offsite-code.txt, или пароль копии, если он задан: им открывается key.age.
        |
        |Если дома не осталось ничего
        |  1. Дождитесь, пока программа облака вернёт эту папку на новый компьютер (или подключите диск с ней).
        |  2. Возьмите пакет сервера Benbow (папку benbow-server).
        |  3. От имени администратора: restore-from-offsite.cmd "<эта папка>" [папка данных] [папка медиа]
        |     Скрипт спросит код восстановления или пароль. Он только читает эту папку и соберёт папку данных
        |     (по умолчанию C:\ProgramData\Benbow), проверяя каждый файл.
        |  4. Затем setup.exe или install-service.cmd с той же папкой данных.
        |  5. Устройства увидят, что сервер восстановлен, и сами дошлют то, чего нет в снимке.
        |
        |Если программы нет вовсе
        |  Возьмите age с age-encryption.org (для Windows age.exe) и откройте нужное, например записи:
        |    age -d -i benbow-offsite-code.txt -o export.zip export.zip.age
        |  С паролем вместо кода: age -d -i key.age -o export.zip export.zip.age (age спросит пароль).
        |  В export.zip все записи в Markdown. Снимок базы открывается так же: это обычный файл SQLite.
        |  Медиафайлы расшифровываются по одному; их имена и даты знает таблица blobs в снимке базы
        |  (по SHA-256 расшифрованного файла).
        |
    """.trimMargin().replace("\n", "\r\n")
}
