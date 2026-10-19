package app.benbow.server.api

import app.benbow.server.peer.PeerLink
import app.benbow.server.peer.driveFolder
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

/** Files through the shared Google Drive folder on the admin page (ADR-039): the folder and sending a file. */
internal fun Route.peerFileRoutes(ctx: ServerContext) {
    post("/admin/peers/drive") {
        peerAdmin(ctx, "drive") { peers ->
            val text = call.receiveParameters()["path"].orEmpty().trim().trim('"')
            require(text.isNotEmpty()) { "впишите путь к папке" }
            val path = runCatching { Path.of(text) }.getOrNull()
            require(path != null && path.isAbsolute && path.isDirectory()) { "папки $text нет" }
            peers.db.saveDriveFolder(path.toString())
        }
    }

    post("/admin/peers/contacts/{id}/file") {
        peerAdmin(ctx, "file") { peers ->
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
        }
    }
}

internal fun driveCard(peers: PeerLink): String = buildString {
    val folder = peers.db.driveFolder()
    val there = folder?.isDirectory() == true
    val state = when {
        folder == null -> chip("", null, "не указана")
        there -> chip("ok", "ok", "на месте")
        else -> chip("err", "error", "папки нет")
    }
    append("<div class=\"card\"><div class=\"row\"><h3>${icon("folder")}Google Диск</h3><span class=\"spacer\"></span>$state</div>")
    when {
        folder == null -> append("<p class=\"muted\">Не указана: файлы не ходят, только сообщения.</p>")

        there -> append("<p>Файлы кладутся в <code>${esc(folder.resolve("Benbow").toString())}</code></p>")

        else -> append(
            "<div class=\"banner err\">${icon(
                "error",
            )}<div>Папки <code>${esc(folder.toString())}</code> сейчас нет: запущен ли Google Диск?</div></div>",
        )
    }
    append("<details class=\"edit\"${if (there) "" else " open"}><summary class=\"btn text\">${icon("edit")}Изменить</summary>")
    append("<form method=\"post\" action=\"/admin/peers/drive\" class=\"stack\">")
    append(field("path", "Папка, которую зеркалирует Диск", folder?.toString().orEmpty(), "H:\\Google Drive или C:\\Users\\…\\My Drive"))
    append("<div class=\"row end\"><button class=\"btn\">Сохранить</button></div></form>")
    append(
        "<p class=\"hint\">Приложение «Google Диск для компьютера» в режиме «Зеркалирование» (не «Потоковая передача»), " +
            "вошедшее в один и тот же аккаунт на обоих компьютерах. Диск видит только зашифрованные файлы; ключи к ним идут письмами.</p>",
    )
    append("</details></div>")
}
