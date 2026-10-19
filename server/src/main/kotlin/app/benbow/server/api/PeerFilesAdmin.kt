package app.benbow.server.api

import app.benbow.server.peer.Contact
import app.benbow.server.peer.PeerLink
import app.benbow.server.peer.driveFolder
import app.benbow.server.peer.filesIn
import app.benbow.server.peer.filesOut
import app.benbow.server.peer.saveDriveFolder
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.isDirectory

private const val MAX_FILE = 4L * 1024 * 1024 * 1024

/** Files through the shared Google Drive folder on the admin page (ADR-039): the folder, sending a file, both ways. */
internal fun Route.peerFileRoutes(ctx: ServerContext) {
    post("/admin/peers/drive") {
        peerAdmin(ctx) { peers ->
            val text = call.receiveParameters()["path"].orEmpty().trim().trim('"')
            require(text.isNotEmpty()) { "впишите путь к папке" }
            val path = runCatching { Path.of(text) }.getOrNull()
            require(path != null && path.isAbsolute && path.isDirectory()) { "папки $text нет" }
            peers.db.saveDriveFolder(path.toString())
            null
        }
    }

    post("/admin/peers/contacts/{id}/file") {
        peerAdmin(ctx) { peers ->
            val id = call.parameters["id"]!!
            var sent = false
            call.receiveMultipart(formFieldLimit = MAX_FILE).forEachPart { part ->
                val name = (part as? PartData.FileItem)?.originalFileName?.substringAfterLast('\\')?.substringAfterLast('/')
                if (!sent && !name.isNullOrBlank()) {
                    // Sealed as it streams in: no plain copy of the file is written anywhere.
                    withContext(Dispatchers.IO) {
                        (part as PartData.FileItem).provider().toInputStream().use { peers.sendFile(id, name, it) }
                    }
                    sent = true
                }
                part.dispose()
            }
            require(sent) { "выберите файл" }
            null
        }
    }
}

private fun size(bytes: Long) = if (bytes < 1_000_000) "${(bytes + 999) / 1000} КБ" else "%.1f МБ".format(bytes / 1_000_000.0)

internal fun driveSection(peers: PeerLink, input: String): String = buildString {
    append("<h3>Папка Google Диска</h3>")
    val folder = peers.db.driveFolder()
    append(
        when {
            folder == null -> "<p>Не указана: файлы не ходят.</p>"
            folder.isDirectory() -> "<p>Файлы кладутся в <code>${esc(folder.resolve("Benbow").toString())}</code>.</p>"
            else -> "<p style=\"color:#b3261e\"><b>Папки <code>${esc(folder.toString())}</code> сейчас нет</b>: запущен ли Google Диск?</p>"
        },
    )
    append(
        """<form method="post" action="/admin/peers/drive" style="display:flex;gap:.5rem">
        <input name="path" $input placeholder="C:\Users\…\My Drive" value="${esc(
            folder?.toString().orEmpty(),
        )}"><button>Сохранить</button></form>
        <p><small>Папка, которую ведёт приложение «Google Диск для компьютера» в режиме «Зеркалирование» (не «Потоковая передача»),
        вошедшее в тот же аккаунт на обоих компьютерах. Диск видит только зашифрованные файлы; ключи к ним идут письмами.</small></p>""",
    )
}

internal fun filesBlock(peers: PeerLink, contact: Contact): String = buildString {
    val out = peers.db.filesOut(contact.id, limit = 10)
    val `in` = peers.db.filesIn(contact.id, limit = 10)
    if (out.isNotEmpty() || `in`.isNotEmpty()) {
        append("<table><tr><th></th><th>Файл</th><th>Когда</th><th>Что с ним</th></tr>")
        val rows =
            out.map {
                it.createdAt to
                    "<tr><td>→</td><td>${esc(it.name)} <small>${size(it.size)}</small></td><td>${stamp(it.createdAt)}</td><td>${
                        if (it.doneAt == null) "в папке Диска, ждёт сервер друга" else "друг получил ${stamp(it.doneAt)}, убран из папки"
                    }</td></tr>"
            } + `in`.map { f ->
                val state = when {
                    f.receivedAt != null -> "получен ${stamp(f.receivedAt)}, отпечаток совпал: <code>${esc(f.path.orEmpty())}</code>"
                    f.lastError != null -> "ждёт, пока Диск его докачает <small>(${esc(f.lastError)})</small>"
                    else -> "ждёт, пока Диск его принесёт"
                }
                f.announcedAt to
                    "<tr><td>←</td><td>${esc(
                        f.name,
                    )} <small>${size(f.size)}</small></td><td>${stamp(f.announcedAt)}</td><td>$state</td></tr>"
            }
        rows.sortedByDescending { it.first }.forEach { append(it.second) }
        append("</table>")
    }
    if (peers.files != null && peers.db.driveFolder() != null) {
        append(
            "<form method=\"post\" action=\"/admin/peers/contacts/${esc(contact.id)}/file\" enctype=\"multipart/form-data\" " +
                "style=\"display:flex;gap:.5rem;margin:.5rem 0\"><input type=\"file\" name=\"file\"><button>Отправить файл</button></form>",
        )
    }
}
