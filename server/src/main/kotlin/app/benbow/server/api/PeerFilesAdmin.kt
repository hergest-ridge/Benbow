package app.benbow.server.api

import app.benbow.server.peer.PeerLink
import app.benbow.server.peer.driveFolder
import app.benbow.server.peer.saveDriveFolder
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.isDirectory

private const val MAX_FILE = 4L * 1024 * 1024 * 1024

/** Files on the admin page: sending one (in parts through the mailbox, ADR-055), and the Drive folder of older servers. */
internal fun Route.peerFileRoutes(ctx: ServerContext) {
    get("/admin/folders") {
        if (!ctx.isLoopback(call)) return@get call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val list = withContext(Dispatchers.IO) { FolderBrowser.list(call.request.queryParameters["path"]) }
        call.response.header("Cache-Control", "no-store")
        call.respond(list)
    }

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
        folder == null -> append(
            "<p class=\"muted\">Не нужен: файлы ходят через ящик частями. Укажите папку, только если сервер друга ещё не " +
                "обновлён и шлёт файлы через Диск.</p>",
        )

        there -> append("<p>Файлы от необновлённых серверов берутся из <code>${esc(folder.resolve("Benbow").toString())}</code></p>")

        else -> append(
            "<div class=\"banner err\">${icon(
                "error",
            )}<div>Папки <code>${esc(folder.toString())}</code> сейчас нет: запущен ли Google Диск?</div></div>",
        )
    }
    append(
        "<details class=\"edit\"${if (folder != null && !there) " open" else ""}><summary class=\"btn text\">${icon(
            "edit",
        )}Изменить</summary>",
    )
    append("<form method=\"post\" action=\"/admin/peers/drive\" class=\"stack\"><div class=\"path-row\">")
    append(field("path", "Папка, которую зеркалирует Диск", folder?.toString().orEmpty(), "H:\\Google Drive или C:\\Users\\…\\My Drive"))
    append("<button class=\"btn outlined\" type=\"button\" data-pick-folder=\"path\">${icon("folder_open")}Выбрать…</button></div>")
    append("<div class=\"row end\"><button class=\"btn\">Сохранить</button></div></form>")
    append(
        "<p class=\"hint\">Приложение «Google Диск для компьютера» в режиме «Зеркалирование» (не «Потоковая передача»), " +
            "вошедшее в один и тот же аккаунт на обоих компьютерах. Диск видит только зашифрованные файлы; ключи к ним идут письмами.</p>",
    )
    append("</details></div>")
}

/** The folder picker (admin.js fills it from `/admin/folders`); outside the live sections, so a refresh never closes it. */
internal fun folderDialog(): String = buildString {
    append("<dialog class=\"picker\" id=\"folder-picker\" aria-labelledby=\"picker-title\"><header>")
    append("<h2 id=\"picker-title\">Выберите папку</h2><div class=\"where\">")
    append("<button class=\"icon-btn\" type=\"button\" data-folder-up title=\"На уровень выше\">${icon("up")}</button>")
    append("<span class=\"mono\" data-folder-where>Этот компьютер</span></div></header>")
    append("<div class=\"folders\" data-folder-list></div>")
    append("<footer><button class=\"btn text\" type=\"button\" data-folder-cancel>Отмена</button>")
    append("<button class=\"btn\" type=\"button\" data-folder-choose>Выбрать эту папку</button></footer></dialog>")
    for ((kind, iconName) in listOf("folder" to "folder", "disk" to "disk", "drive" to "star")) {
        append("<template id=\"folder-$kind\"><button class=\"folder\" type=\"button\">${icon(iconName)}")
        append("<span><span class=\"name\"></span><span class=\"path\"></span></span></button></template>")
    }
}
