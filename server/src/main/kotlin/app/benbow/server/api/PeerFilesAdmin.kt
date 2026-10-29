package app.benbow.server.api

import app.benbow.server.peer.MailSettings
import app.benbow.server.peer.PeerLink
import app.benbow.server.peer.VIA_FOLDER
import app.benbow.server.peer.VIA_MAIL
import app.benbow.server.peer.VIA_PARTS_MAIL
import app.benbow.server.peer.driveFolder
import app.benbow.server.peer.filesVia
import app.benbow.server.peer.saveDriveFolder
import app.benbow.server.peer.saveFilesVia
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

/** Files on the admin page (ADR-055): the way they go, its mailbox or folder, and sending one. */
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
            peers.restartFiles()
        }
    }

    post("/admin/peers/files") {
        peerAdmin(ctx, "files") { peers ->
            peers.db.saveFilesVia(call.receiveParameters()["via"].orEmpty())
            peers.restartFiles()
        }
    }

    post("/admin/peers/parts-mail") {
        peerAdmin(ctx, "parts-mail") { peers ->
            val form = call.receiveParameters()
            val port = form["port"]?.trim()?.toIntOrNull()
            require(port != null && port in 1..65535) { "порт — число от 1 до 65535" }
            peers.db.savePartsMailSettings(
                MailSettings(form["host"].orEmpty().trim(), port, form["user"].orEmpty().trim(), form["password"].orEmpty()),
            )
            peers.restartFiles()
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

/** Whether files can go now the way chosen in «Связь». */
internal fun filesReady(peers: PeerLink): Boolean = peers.transfers != null && when (peers.db.filesVia()) {
    VIA_PARTS_MAIL -> peers.db.partsMailSettings()?.complete == true
    VIA_FOLDER -> peers.db.driveFolder()?.isDirectory() == true
    else -> peers.db.mailSettings()?.complete == true
}

/** «Связь → Файлы»: the way files go (ADR-055) and what each way needs. */
internal fun filesCard(peers: PeerLink): String = buildString {
    val via = peers.db.filesVia()
    val trouble = peers.filesTrouble(via)
    val state = when {
        trouble != null -> chip("err", "error", "не идут")
        filesReady(peers) -> chip("ok", "ok", "готово")
        else -> chip("", null, "не настроено")
    }
    append("<div class=\"card\"><div class=\"row\"><h3>${icon("attach")}Файлы</h3><span class=\"spacer\"></span>$state</div>")
    append("<p class=\"muted\">Файл шифруется целиком и идёт частями до 8 МБ. Друг принимает тем путём, каким вы шлёте: ")
    append("настройте у себя то же самое.</p>")
    trouble?.let { append("<div class=\"banner err\">${icon("error")}<div>Файлы не идут: ${esc(it)}</div></div>") }
    append("<form method=\"post\" action=\"/admin/peers/files\" class=\"stack\"><fieldset class=\"choice\">")
    append("<legend>Как отправлять файлы</legend>")
    fun option(value: String, title: String, text: String) = append(
        "<label><input type=\"radio\" name=\"via\" value=\"$value\"${if (value == via) " checked" else ""}>" +
            "<span><b>$title</b>. $text</span></label>",
    )
    option(
        VIA_MAIL,
        "Через этот же ящик",
        "До 150 МБ в сутки с каждого сервера: Gmail разрешает 500 МБ загрузки по IMAP в сутки на ящик, а за превышение " +
            "закрывает IMAP до суток вместе с письмами.",
    )
    option(
        VIA_PARTS_MAIL,
        "Через отдельный ящик для файлов",
        "Свой дневной лимит, и если он кончится, письма всё равно пойдут. Ящик тоже общий на оба сервера.",
    )
    option(
        VIA_FOLDER,
        "Через общую папку",
        "Google Диск для компьютера в режиме «Зеркалирование» или Syncthing, одна и та же папка у обоих. Без дневного лимита, " +
            "но файлы идут, только пока программа синхронизации работает.",
    )
    append("</fieldset><div class=\"row end\"><button class=\"btn\">Сохранить</button></div></form>")
    append(partsMailForm(peers, open = via == VIA_PARTS_MAIL && peers.db.partsMailSettings()?.complete != true))
    val folder = peers.db.driveFolder()
    append(folderForm(folder, open = (via == VIA_FOLDER && folder == null) || (folder != null && !folder.isDirectory())))
    append("</div>")
}

private fun partsMailForm(peers: PeerLink, open: Boolean): String = buildString {
    val settings = peers.db.partsMailSettings()
    val shown = settings?.takeIf { it.complete }?.let { "${esc(it.host)} · ${esc(it.user)}" } ?: "не настроен"
    append(
        "<details class=\"edit\"${if (open) " open" else ""}><summary class=\"btn text\">${icon("mail")}Ящик для файлов: $shown</summary>",
    )
    append("<form method=\"post\" action=\"/admin/peers/parts-mail\" class=\"stack\"><div class=\"form-grid\">")
    append(fieldAs("parts-host", "host", "Сервер IMAP", settings?.host.orEmpty(), "imap.gmail.com"))
    append(fieldAs("parts-port", "port", "Порт", "${settings?.port ?: 993}", ""))
    append(fieldAs("parts-user", "user", "Логин", settings?.user.orEmpty(), "адрес второго ящика"))
    append(
        "<div class=\"field\"><label for=\"parts-password\">Пароль приложения</label><input id=\"parts-password\" name=\"password\" " +
            "type=\"password\" autocomplete=\"new-password\" " +
            "placeholder=\"${if (settings?.password.isNullOrEmpty()) "" else "сохранён; пусто — не менять"}\"></div>",
    )
    append("</div><div class=\"row end\"><button class=\"btn\">Сохранить</button></div></form>")
    append("<p class=\"hint\">Отдельный ящик Gmail только для файлов, с IMAP и паролем приложения, как и первый.</p></details>")
}

private fun folderForm(folder: Path?, open: Boolean): String = buildString {
    val shown = when {
        folder == null -> "не указана"
        folder.isDirectory() -> esc(folder.toString())
        else -> "${esc(folder.toString())} — её сейчас нет"
    }
    append("<details class=\"edit\"${if (open) " open" else ""}><summary class=\"btn text\">${icon("folder")}Общая папка: $shown</summary>")
    append("<form method=\"post\" action=\"/admin/peers/drive\" class=\"stack\"><div class=\"path-row\">")
    append(field("path", "Папка, которую синхронизирует Диск или Syncthing", folder?.toString().orEmpty(), "H:\\Google Drive"))
    append("<button class=\"btn outlined\" type=\"button\" data-pick-folder=\"path\">${icon("folder_open")}Выбрать…</button></div>")
    append("<div class=\"row end\"><button class=\"btn\">Сохранить</button></div></form>")
    append(
        "<p class=\"hint\">Для Google Диска: приложение «Google Диск для компьютера» в режиме «Зеркалирование» (не «Потоковая " +
            "передача»), в одном аккаунте на обоих компьютерах. Папка видит только зашифрованные части. Из неё же берутся файлы " +
            "от необновлённого сервера друга, который шлёт их через Диск.</p>",
    )
    append("</details>")
}

private fun fieldAs(id: String, name: String, label: String, value: String, placeholder: String) =
    "<div class=\"field\"><label for=\"$id\">${esc(label)}</label><input id=\"$id\" name=\"$name\" value=\"${esc(value)}\" " +
        "placeholder=\"${esc(placeholder)}\" autocomplete=\"off\"></div>"

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
