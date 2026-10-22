package app.benbow.server.api

import app.benbow.server.SERVER_VERSION
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The admin page's frame in Material 3 (the client's "Inn" scheme): its stylesheet and script are files under
 * `/admin/assets/`, and the page's CSP lets nothing else run or load, so no text that reached the page (a friend's note,
 * a file name) can ever act as code.
 */
internal const val ADMIN_CSP =
    "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'; " +
        "form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

private val assets = mapOf(
    "admin.css" to ContentType.Text.CSS,
    "admin.js" to ContentType.Application.JavaScript,
    "icon.svg" to ContentType.Image.SVG,
)

internal fun Route.adminAssets() {
    get("/admin/assets/{name}") {
        val name = call.parameters["name"]!!
        val type = assets[name] ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
        val bytes = AdminPageResources::class.java.getResourceAsStream("/admin/$name")?.use { it.readBytes() }
            ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
        call.response.header("Cache-Control", "no-cache")
        call.respondBytes(bytes, type)
    }
}

private object AdminPageResources

/** Responds with a whole admin page (or a part of it) under the page's CSP. */
internal suspend fun ApplicationCall.respondAdmin(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    response.header("Content-Security-Policy", ADMIN_CSP)
    response.header("Cache-Control", "no-store")
    respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)
}

/** What a POST left for the page to say once. Messages are fixed here: nothing from the address is shown as is. */
internal val doneMessages = mapOf(
    "code" to "Код готов: введите его на устройстве",
    "backup" to "Копия делается: она появится в списке",
    "mirror" to "Второй диск выбран: копия туда делается, ход виден ниже. Прежняя папка, если была, осталась как есть",
    "left-check" to "Старая папка проверяется: итог появится ниже",
    "left-delete" to "Старая папка проверяется ещё раз и удаляется, если всё в порядке: итог ниже",
    "left-forget" to "Папка убрана из списка",
    "media-move" to "Перенос начат: файлы копируются и проверяются, ход виден ниже; сервер переключится сам",
    "media-retry" to "Перенос продолжается с того места, где остановился",
    "media-cancel" to "Перенос отменён: медиафайлы в прежней папке, уже скопированное осталось в новой",
    "mirror-off" to "Второй диск отключён: копии туда больше не идут, уже сделанные остались в папке",
    "speech" to "Модель скачивается",
    "revoked" to "Устройство отключено",
    "mail" to "Ящик сохранён: подключаюсь",
    "drive" to "Папка Google Диска сохранена",
    "friend" to "Друг добавлен",
    "unfriend" to "Контакт убран",
    "note" to "Сообщение отправлено",
    "file" to "Файл зашифрован и положен в папку Диска",
    "check" to "Проверяю ящик",
    "undone" to "Удаление отменено: файлы и вложения вернулись",
    "offsite" to "Копия вне дома включена: первая копия пошла в фоне",
    "offsite-off" to "Копия вне дома выключена; её папка и ключ остались как есть",
    "offsite-pass" to "Пароль записан: key.age в копии закрыт новым паролем",
    "freed" to "Место освобождено: файлы удалены навсегда",
)

internal class Flash(val text: String, val error: Boolean)

/** One section of the page: an item of the drawer; [alert] puts a red dot on it. */
internal class Tab(val id: String, val title: String, val icon: String, val alert: Boolean = false)

/**
 * The page: a navigation drawer on the left and one section at a time (`.page`, shown by `:target`, the first one when
 * the address has no section), so it also works before its script runs. [extra] goes after the sections (dialogs).
 * Without the [drawer] it is one narrow column (the public install page, read on a phone).
 */
internal fun adminShell(
    tabs: List<Tab>,
    body: String,
    flash: Flash?,
    subtitle: String,
    extra: String = "",
    drawer: Boolean = true,
): String = buildString {
    append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><title>Benbow — сервер</title>")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<link rel=\"icon\" href=\"/admin/assets/icon.svg\" type=\"image/svg+xml\">")
    append("<link rel=\"stylesheet\" href=\"/admin/assets/admin.css\"><script src=\"/admin/assets/admin.js\" defer></script></head><body>")
    append(if (drawer) "<div class=\"layout\">" else "<div class=\"layout bare\">")
    if (drawer) {
        append("<nav class=\"drawer\" aria-label=\"Разделы\"><div class=\"brand\">")
        append("<img class=\"logo\" src=\"/admin/assets/icon.svg\" alt=\"\">")
        append("<div><div class=\"title\">Адмирал Бенбоу</div><div class=\"subtitle\">${esc(subtitle)}</div></div></div>")
        append("<div class=\"nav\">")
        tabs.forEach {
            append("<a href=\"#${it.id}\">${icon(it.icon)}<span>${esc(it.title)}</span>")
            if (it.alert) append("<span class=\"dot\" title=\"Требует внимания\"></span>")
            append("</a>")
        }
        append("</div><div class=\"foot\"><a class=\"btn tonal\" href=\"/app/\" title=\"Открыть Benbow в браузере\">${icon("open")}")
        append("<span class=\"btn-label\">Открыть Benbow</span></a></div></nav>")
    }
    append("<div class=\"content\"><main>")
    append(body)
    append("</main></div></div>")
    append(extra)
    flash?.let { append("<div class=\"snackbar${if (it.error) " err" else ""}\" role=\"status\">${esc(it.text)}</div>") }
    append("</body></html>")
}

/** The heading of a section, with a line under it about what the section is for. */
internal fun pageTitle(title: String, iconName: String, lead: String? = null) =
    "<h1 class=\"page-title\">${icon(iconName, "big")}${esc(title)}</h1>" + lead?.let { "<p class=\"page-lead\">${esc(it)}</p>" }.orEmpty()

internal fun chip(kind: String, iconName: String?, text: String) =
    "<span class=\"chip $kind\">${iconName?.let { icon(it) }.orEmpty()}${esc(text)}</span>"

/** A POST button in a form of its own; [confirm] asks first, [busy] replaces its label while it goes. */
internal fun postButton(
    action: String,
    label: String,
    style: String = "",
    iconName: String? = null,
    confirm: String? = null,
    busy: String? = null,
): String = buildString {
    append("<form class=\"inline\" method=\"post\" action=\"${esc(action)}\"")
    confirm?.let { append(" data-confirm=\"${esc(it)}\"") }
    busy?.let { append(" data-busy=\"${esc(it)}\"") }
    append("><button class=\"btn $style\">${iconName?.let { icon(it) }.orEmpty()}${esc(label)}</button></form>")
}

/** Material icons (Apache 2.0), inline so the CSP needs no fonts or images. */
internal fun icon(name: String, extra: String = ""): String =
    "<svg class=\"icon $extra\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"${iconPaths.getValue(name)}\"/></svg>"

private val iconPaths = mapOf(
    "chat" to "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 9h12v2H6V9zm8 5H6v-2h8v2zm4-6H6V6h12v2z",
    "sync" to "M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 " +
        "14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z",
    "folder_open" to "M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z",
    "up" to "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z",
    "close" to "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    "wifi" to
        "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z",
    "disk" to "M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z",
    "star" to "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
    "check" to "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    "done_all" to "M18 7l-1.41-1.41-6.34 6.34 1.41 1.41L18 7zm4.24-1.41L11.66 16.17 7.48 12l-1.41 1.41L11.66 19l12-12-1.42-1.41zM.41 " +
        "13.41L6 19l1.41-1.41L1.83 12 .41 13.41z",
    "schedule" to "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 " +
        "8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
    "error" to "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z",
    "ok" to "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z",
    "send" to "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z",
    "attach" to
        "M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5c0-1.38 1.12-2.5 2.5-2.5s2.5 1.12 2.5 2.5v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6H10" +
        "v9.5c0 1.38 1.12 2.5 2.5 2.5s2.5-1.12 2.5-2.5V5c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z",
    "copy" to "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 " +
        "16H8V7h11v14z",
    "delete" to "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
    "person_add" to "M15 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm-9-2V7H4v3H1v2h3v3h2v-3h3v-2H6zm9 4c-2.67 0-8 " +
        "1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
    "refresh" to "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 " +
        "2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
    "phone" to "M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z",
    "computer" to "M20 18c1.1 0 1.99-.9 1.99-2L22 6c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2H0v2h24v-2h-4zM4 6h16v10H4V6z",
    "backup" to "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 " +
        "5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z",
    "mic" to "M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 " +
        "5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z",
    "group" to "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 " +
        "5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 " +
        "1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z",
    "mail" to "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z",
    "folder" to "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z",
    "open" to "M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 " +
        "6.41V10h2V3h-7z",
    "key" to "M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 " +
        "0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z",
    "download" to "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
    "server" to "M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 " +
        ".9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 " +
        "2 .9 2 2-.9 2-2 2z",
    "file" to "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5" +
        "L18.5 9H13z",
    "edit" to "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 " +
        "0l-1.83 1.83 3.75 3.75 1.83-1.83z",
)

internal fun versionLine() = "Сервер Benbow $SERVER_VERSION"
