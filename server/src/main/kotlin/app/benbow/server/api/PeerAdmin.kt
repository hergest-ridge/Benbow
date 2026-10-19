package app.benbow.server.api

import app.benbow.server.peer.Contact
import app.benbow.server.peer.MailSettings
import app.benbow.server.peer.PeerCard
import app.benbow.server.peer.PeerLink
import app.benbow.server.peer.driveFolder
import app.benbow.server.peer.filesIn
import app.benbow.server.peer.filesOut
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * An admin POST of the friends' section: only from the server PC. Done, it goes back to the page with [done]'s message;
 * a person's mistake comes back as the page itself with the reason in a snackbar.
 */
internal suspend fun RoutingContext.peerAdmin(ctx: ServerContext, done: String, block: suspend RoutingContext.(PeerLink) -> Unit) {
    if (!adminAllowed(call, ctx)) return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
    val peers = ctx.peers ?: return call.respondText("Связь с друзьями не открылась", status = HttpStatusCode.ServiceUnavailable)
    try {
        block(peers)
    } catch (e: IllegalArgumentException) {
        return call.respondAdmin(
            adminPage(ctx, admin = true, Flash("Не получилось: ${e.message ?: "неверные данные"}", error = true)),
            HttpStatusCode.BadRequest,
        )
    }
    call.respondRedirect("/?done=$done#friends")
}

/** The link with friends' servers on the admin page (ADR-039): the card, the mailbox, Drive, friends as conversations. */
internal fun Route.peerAdminRoutes(ctx: ServerContext) {
    peerFileRoutes(ctx)

    post("/admin/peers/mail") {
        peerAdmin(ctx, "mail") { peers ->
            val form = call.receiveParameters()
            val port = form["port"]?.trim()?.toIntOrNull()
            require(port != null && port in 1..65535) { "порт — число от 1 до 65535" }
            peers.db.saveMailSettings(
                MailSettings(form["host"].orEmpty().trim(), port, form["user"].orEmpty().trim(), form["password"].orEmpty()),
            )
            peers.restart()
        }
    }

    post("/admin/peers/contacts") {
        peerAdmin(ctx, "friend") { peers ->
            val form = call.receiveParameters()
            val name = form["name"].orEmpty().trim()
            require(name.isNotEmpty()) { "впишите имя" }
            val card = PeerCard.decode(form["card"].orEmpty())
            require(card != peers.identity.card) { "это карточка этого же сервера" }
            peers.db.addContact(name.take(80), card, System.currentTimeMillis())
            peers.check()
        }
    }

    post("/admin/peers/contacts/{id}/remove") {
        peerAdmin(ctx, "unfriend") { peers -> peers.db.removeContact(call.parameters["id"]!!, System.currentTimeMillis()) }
    }

    post("/admin/peers/contacts/{id}/note") {
        peerAdmin(ctx, "note") { peers ->
            val id = call.parameters["id"]!!
            val text = call.receiveParameters()["text"].orEmpty().trim()
            require(text.isNotEmpty()) { "сообщение пустое" }
            require(peers.db.contacts().any { it.id == id }) { "такого контакта нет" }
            peers.sendNote(id, text.take(2_000))
        }
    }

    post("/admin/peers/check") {
        peerAdmin(ctx, "check") { peers -> peers.check() }
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault())
private val hourFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dayFormat = DateTimeFormatter.ofPattern("d.MM, HH:mm").withZone(ZoneId.systemDefault())

internal fun stamp(ms: Long?) = ms?.let { clockFormat.format(Instant.ofEpochMilli(it)) } ?: "—"

/** "14:05" today, "18.10, 14:05" before. */
private fun short(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == LocalDate.now(zone)
    return (if (today) hourFormat else dayFormat).format(Instant.ofEpochMilli(ms))
}

internal fun peersSection(peers: PeerLink?): String {
    if (peers == null) {
        return "<section id=\"friends\">${sectionTitle("friends", "Друзья", "group")}<div class=\"card\"><div class=\"banner err\">" +
            "${icon("error")}<div><b>Связь с друзьями не открылась</b> (дневник работает). Причина в журнале сервера.</div></div>" +
            "</div></section>"
    }
    return "<section id=\"friends\" data-live=\"friends\">${peersInner(peers)}</section>"
}

/** The friends' section without its frame: also what the page's script fetches to refresh it. */
internal fun peersInner(peers: PeerLink?): String = buildString {
    if (peers == null) return@buildString
    append(sectionTitle("friends", "Друзья", "group"))
    append(thisServer(peers))
    append("<div class=\"grid2\">${mailCard(peers)}${driveCard(peers)}</div>")
    val drive = peers.files != null && peers.db.driveFolder() != null
    peers.db.contacts().forEach { append(friendCard(peers, it, drive)) }
    append(addFriend())
}

private fun thisServer(peers: PeerLink): String = buildString {
    val card = peers.identity.card
    append("<div class=\"card\"><h3>${icon("key")}Этот сервер</h3>")
    append(
        "<p class=\"lead\">Другу уходят запечатанные письма: прочитать их может только его сервер. Почтовый ящик и Google Диск, " +
            "через которые они идут, видят лишь зашифрованное. Порты не открываются.</p>",
    )
    append("<div class=\"row\"><span class=\"muted\">Отпечаток</span><span class=\"fingerprint\">${card.fingerprint}</span></div>")
    append(
        "<p class=\"muted small\">Пошлите карточку другу как угодно и возьмите его. Отпечатки сверьте голосом, по телефону или при встрече: ",
    )
    append("так никто посередине не подменит карточку.</p>")
    append("<div class=\"keybox\"><span class=\"mono\">${card.encode()}</span>")
    append(
        "<button class=\"icon-btn\" type=\"button\" title=\"Скопировать карточку\" data-copy=\"${card.encode()}\">" +
            "${icon("copy")}</button></div></div>",
    )
}

private fun mailCard(peers: PeerLink): String = buildString {
    val settings = peers.db.mailSettings()
    val status = peers.status
    append(
        "<div class=\"card\"><div class=\"row\"><h3>${icon("mail")}Почтовый ящик</h3><span class=\"spacer\"></span>" +
            "${mailChip(status, settings)}</div>",
    )
    append(mailDetails(status))
    val healthy = settings?.complete == true && (status is PeerLink.Status.Online || status is PeerLink.Status.Connecting)
    if (settings != null && settings.complete) append("<p><code>${esc(settings.host)}</code> · ${esc(settings.user)}</p>")
    append("<details class=\"edit\"${if (healthy) "" else " open"}><summary class=\"btn text\">${icon("edit")}Изменить</summary>")
    append("<form method=\"post\" action=\"/admin/peers/mail\" data-busy=\"Подключаюсь…\" class=\"stack\"><div class=\"form-grid\">")
    append(field("host", "Сервер IMAP", settings?.host.orEmpty(), "imap.gmail.com или imap.yandex.ru"))
    append(field("port", "Порт", "${settings?.port ?: 993}", ""))
    append(field("user", "Логин", settings?.user.orEmpty(), "адрес ящика"))
    append(
        "<div class=\"field\"><label for=\"password\">Пароль приложения</label>" +
            "<input id=\"password\" name=\"password\" type=\"password\" " +
            "autocomplete=\"new-password\" placeholder=\"${if (settings?.password.isNullOrEmpty()) "" else "сохранён; пусто — не менять"}\"></div>",
    )
    append("</div><div class=\"row end\"><button class=\"btn\">Сохранить и подключиться</button></div></form>")
    append(
        "<p class=\"hint\">Один ящик на оба сервера, только для Benbow. Нужны IMAP и пароль приложения, а не основной пароль. " +
            "В Gmail в настройках IMAP выберите «сразу удалять навсегда», иначе прочитанные письма копятся во «Всей почте».</p>",
    )
    append("</details></div>")
}

private fun mailChip(status: PeerLink.Status, settings: MailSettings?): String = when (status) {
    PeerLink.Status.Off -> chip("", null, if (settings?.complete == true) "выключено" else "не настроен")
    PeerLink.Status.Connecting -> chip("busy", "schedule", "подключаюсь")
    is PeerLink.Status.Online -> chip("ok", "ok", "на связи")
    is PeerLink.Status.Failed -> chip("err", "error", if (status.retryAt == null) "не пустил" else "нет связи")
}

private fun mailDetails(status: PeerLink.Status): String = when (status) {
    PeerLink.Status.Off -> "<p class=\"muted\">Ящик не настроен: связь выключена.</p>"

    PeerLink.Status.Connecting -> ""

    is PeerLink.Status.Online -> buildString {
        append("<p class=\"muted\">На связи с ${stamp(status.since)}. Последний обмен ${stamp(status.lastRoundAt)}.</p>")
        status.lastRound?.takeIf { it.waiting > 0 }?.let {
            append(
                "<div class=\"banner info\">${icon("mail")}<div>" +
                    "В ящике ждут письма от серверов, которых нет в контактах: ${it.waiting}.</div></div>",
            )
        }
        status.lastRound?.takeIf { it.refused > 0 }?.let {
            append(
                "<div class=\"banner err\">${icon("error")}<div>" +
                    "Отклонено писем: ${it.refused} (подпись или адрес не сошлись; подробности в журнале).</div></div>",
            )
        }
    }

    is PeerLink.Status.Failed -> if (status.retryAt == null) {
        "<div class=\"banner err\">${icon("error")}<div><b>Ящик не пустил:</b> неверный логин или пароль приложения, либо IMAP выключен " +
            "в настройках почты. Сервер не пробует снова, чтобы почта не заблокировала ящик: исправьте и сохраните.<br>" +
            "<span class=\"small\">${esc(status.message)}</span></div></div>"
    } else {
        "<div class=\"banner err\">${icon("error")}<div><b>Нет связи:</b> ${esc(status.message)}<br><span class=\"small\">" +
            "Следующая попытка в " +
            "${stamp(status.retryAt)}.</span></div></div>"
    }
}

internal fun field(name: String, label: String, value: String, placeholder: String) =
    "<div class=\"field\"><label for=\"$name\">${esc(label)}</label><input id=\"$name\" name=\"$name\" value=\"${esc(value)}\" " +
        "placeholder=\"${esc(placeholder)}\" autocomplete=\"off\"></div>"

private fun friendCard(peers: PeerLink, contact: Contact, drive: Boolean): String = buildString {
    append(
        "<div class=\"card outlined\"><div class=\"friend-head\"><div class=\"avatar warm\">" +
            "${esc(contact.name.take(1).uppercase())}</div>",
    )
    append(
        "<div class=\"body\"><div class=\"headline\">${esc(contact.name)}</div><div class=\"support mono\">" +
            "отпечаток ${contact.card.fingerprint}</div></div>",
    )
    append(
        postButton(
            "/admin/peers/contacts/${contact.id}/remove",
            "Убрать",
            "text danger",
            "delete",
            confirm = "Убрать «${contact.name}» из контактов? Письма от этого сервера перестанут читаться, пока карточку не добавят снова.",
        ),
    )
    append("</div>")
    append(conversation(peers, contact))
    append("<div class=\"composer\"><form method=\"post\" action=\"/admin/peers/contacts/${contact.id}/note\">")
    append("<input name=\"text\" placeholder=\"Тестовое сообщение\" autocomplete=\"off\" aria-label=\"Сообщение\">")
    append("<button class=\"icon-btn filled\" title=\"Отправить\">${icon("send")}</button></form>")
    if (drive) {
        append(
            "<form class=\"attach\" method=\"post\" action=\"/admin/peers/contacts/${contact.id}/file\" enctype=\"multipart/form-data\">",
        )
        append(
            "<label class=\"icon-btn\" title=\"Отправить файл\">${icon("attach")}<input type=\"file\" name=\"file\" data-send>" +
                "</label></form>",
        )
    }
    append("</div>")
    if (!drive) append("<p class=\"hint\">Файлы пойдут, когда будет указана папка Google Диска.</p>")
    append("</div>")
}

/** Notes and files both ways, oldest first, as bubbles. */
private fun conversation(peers: PeerLink, contact: Contact): String {
    val items = mutableListOf<Pair<Long, String>>()
    for (n in peers.db.notes(contact.id, limit = 30)) {
        val meta = if (n.outgoing) {
            when {
                n.ackedAt != null -> "${short(n.at)} · доставлено ${icon("done_all")}"
                n.sentAt != null -> "${short(n.at)} · в ящике ${icon("check")}"
                else -> "${short(n.at)} · ждёт отправки ${icon("schedule")}"
            }
        } else {
            val way = n.receivedAt?.let { " · в пути ${((it - n.at) / 1000).coerceAtLeast(0)} с" }.orEmpty()
            short(n.receivedAt ?: n.at) + way
        }
        items += n.at to "<div class=\"bubble ${if (n.outgoing) "out" else "in"}\">${esc(n.text)}<div class=\"meta\">$meta</div></div>"
    }
    for (f in peers.db.filesOut(contact.id, limit = 15)) {
        val meta = if (f.doneAt !=
            null
        ) {
            "${short(f.createdAt)} · друг получил ${icon("done_all")}"
        } else {
            "${short(f.createdAt)} · в папке Диска, ждёт сервер друга ${icon("check")}"
        }
        items += f.createdAt to fileBubble("out", f.name, f.size, meta, null)
    }
    for (f in peers.db.filesIn(contact.id, limit = 15)) {
        val (meta, where) = when {
            f.receivedAt != null -> "${short(f.receivedAt)} · получен, отпечаток совпал ${icon("ok")}" to f.path
            f.lastError != null -> "${short(f.announcedAt)} · Диск ещё не докачал ${icon("schedule")}" to null
            else -> "${short(f.announcedAt)} · ждёт, пока Диск принесёт ${icon("schedule")}" to null
        }
        items += f.announcedAt to fileBubble("in", f.name, f.size, meta, where)
    }
    if (items.isEmpty()) {
        return "<div class=\"chat\"><div class=\"empty\">" +
            "Пока ни одного сообщения. Напишите первое — оно уйдёт письмом через ящик.</div></div>"
    }
    return "<div class=\"chat\">" + items.sortedBy { it.first }.takeLast(40).joinToString("") { it.second } + "</div>"
}

private fun fileBubble(side: String, name: String, size: Long, meta: String, where: String?): String {
    val shown = if (size < 1_000_000) "${(size + 999) / 1000} КБ" else "%.1f МБ".format(size / 1_000_000.0)
    return "<div class=\"bubble $side\"><div class=\"file\">${icon("file")}<div><div class=\"name\">${esc(name)}</div>" +
        "<div class=\"small\">$shown</div></div></div>" +
        (where?.let { "<div class=\"where mono\">${esc(it)}</div>" }.orEmpty()) + "<div class=\"meta\">$meta</div></div>"
}

private fun addFriend(): String = buildString {
    append("<div class=\"card\"><h3>${icon("person_add")}Добавить друга</h3>")
    append("<form method=\"post\" action=\"/admin/peers/contacts\" class=\"stack\">")
    append(field("name", "Имя", "", "как вы его зовёте"))
    append(
        "<div class=\"field\"><label for=\"card\">Его карточка</label>" +
            "<textarea id=\"card\" name=\"card\" rows=\"3\" placeholder=\"benbow1.…\"></textarea></div>",
    )
    append("<div class=\"row end\"><button class=\"btn\">${icon("person_add")}Добавить</button></div></form>")
    append("<div class=\"row end\">${postButton("/admin/peers/check", "Проверить ящик сейчас", "text", "refresh")}</div></div>")
}
