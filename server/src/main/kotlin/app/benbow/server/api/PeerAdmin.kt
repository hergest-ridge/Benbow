package app.benbow.server.api

import app.benbow.server.peer.MailSettings
import app.benbow.server.peer.NoteView
import app.benbow.server.peer.PeerCard
import app.benbow.server.peer.PeerLink
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** An admin POST of the friends' section: only from the server PC; a person's mistake comes back as a short page. */
internal suspend fun RoutingContext.peerAdmin(ctx: ServerContext, block: suspend RoutingContext.(PeerLink) -> String?) {
    if (!adminAllowed(call, ctx)) return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
    val peers = ctx.peers ?: return call.respondText("Связь с друзьями не открылась", status = HttpStatusCode.ServiceUnavailable)
    val error = try {
        block(peers)
    } catch (e: IllegalArgumentException) {
        e.message ?: "неверные данные"
    }
    if (error == null) return call.respondRedirect("/#friends")
    call.respondText(
        "<!doctype html><meta charset=\"utf-8\"><title>Benbow</title><p>Не получилось: ${esc(
            error,
        )}</p><p><a href=\"/#friends\">Назад</a></p>",
        ContentType.Text.Html,
        HttpStatusCode.BadRequest,
    )
}

/** The link with friends' servers on the admin page (ADR-039, F1): the card, the mailbox, contacts and test notes. */
internal fun Route.peerAdminRoutes(ctx: ServerContext) {
    suspend fun RoutingContext.admin(block: suspend RoutingContext.(PeerLink) -> String?) = peerAdmin(ctx, block)

    peerFileRoutes(ctx)

    post("/admin/peers/mail") {
        admin { peers ->
            val form = call.receiveParameters()
            val port = form["port"]?.trim()?.toIntOrNull()
            require(port != null && port in 1..65535) { "порт — число от 1 до 65535" }
            peers.db.saveMailSettings(
                MailSettings(form["host"].orEmpty().trim(), port, form["user"].orEmpty().trim(), form["password"].orEmpty()),
            )
            peers.restart()
            null
        }
    }

    post("/admin/peers/contacts") {
        admin { peers ->
            val form = call.receiveParameters()
            val name = form["name"].orEmpty().trim()
            require(name.isNotEmpty()) { "впишите имя" }
            val card = PeerCard.decode(form["card"].orEmpty())
            require(card != peers.identity.card) { "это карточка этого же сервера" }
            peers.db.addContact(name.take(80), card, System.currentTimeMillis())
            peers.check()
            null
        }
    }

    post("/admin/peers/contacts/{id}/remove") {
        admin { peers ->
            peers.db.removeContact(call.parameters["id"]!!, System.currentTimeMillis())
            null
        }
    }

    post("/admin/peers/contacts/{id}/note") {
        admin { peers ->
            val id = call.parameters["id"]!!
            val text = call.receiveParameters()["text"].orEmpty().trim()
            require(text.isNotEmpty()) { "сообщение пустое" }
            require(peers.db.contacts().any { it.id == id }) { "такого контакта нет" }
            peers.sendNote(id, text.take(2_000))
            null
        }
    }

    post("/admin/peers/check") {
        admin { peers ->
            peers.check()
            null
        }
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault())

internal fun stamp(ms: Long?) = ms?.let { clockFormat.format(Instant.ofEpochMilli(it)) } ?: "—"

internal fun peersSection(peers: PeerLink?): String = buildString {
    append("<section id=\"friends\"><h2>Друзья: связь с другими серверами Benbow</h2>")
    if (peers == null) {
        append("<p style=\"color:#b3261e\"><b>Не открылась</b> (дневник работает). Причина в журнале сервера.</p></section>")
        return@buildString
    }
    append(
        "<p>Сервер друга получает от этого сервера запечатанные письма: их может прочитать только он, а почтовый ящик, " +
            "через который они идут, видит лишь зашифрованный текст. Файлы идут через Google Диск, тоже зашифрованными. " +
            "Порты не открываются. Пока это проверка связи: тестовые сообщения и файлы туда и обратно.</p>",
    )
    val card = peers.identity.card
    append("<h3>Карточка этого сервера</h3>")
    append("<p>Пошлите её другу и возьмите его. Отпечаток: <b>${card.fingerprint}</b>. Сверьте его с другом голосом ")
    append("(по телефону или при встрече): так никто посередине не подменит карточку.</p><p><code>${card.encode()}</code></p>")

    append("<h3>Почтовый ящик</h3>")
    val settings = peers.db.mailSettings()
    append("<p>${statusLine(peers.status, settings)}</p>")
    val input = "style=\"font:inherit;padding:.3rem;width:100%;box-sizing:border-box\""
    append(
        """<form method="post" action="/admin/peers/mail" style="display:grid;grid-template-columns:auto 1fr;gap:.4rem .8rem;align-items:center">
        <label>Сервер IMAP</label><input name="host" $input placeholder="imap.yandex.ru или imap.gmail.com" value="${esc(
            settings?.host.orEmpty(),
        )}">
        <label>Порт</label><input name="port" $input value="${settings?.port ?: 993}">
        <label>Логин</label><input name="user" $input autocomplete="off" value="${esc(settings?.user.orEmpty())}">
        <label>Пароль приложения</label><input name="password" type="password" $input autocomplete="new-password" placeholder="${
            if (settings?.password.isNullOrEmpty()) "" else "сохранён; пусто — не менять"
        }">
        <span></span><span><button>Сохранить и подключиться</button></span></form>
        <p><small>Один ящик на оба сервера, только для Benbow. Нужны IMAP и пароль приложения, а не основной пароль.
        В Gmail в настройках IMAP выберите «сразу удалять навсегда» для писем, удалённых по IMAP, иначе прочитанные письма
        копятся во «Всей почте».</small></p>""",
    )
    append(driveSection(peers, input))

    append("<h3>Контакты</h3>")
    val contacts = peers.db.contacts()
    if (contacts.isEmpty()) append("<p>Пока никого.</p>")
    for (contact in contacts) {
        append("<h4>${esc(contact.name)} <small style=\"font-weight:normal\">· отпечаток ${contact.card.fingerprint}</small></h4>")
        val notes = peers.db.notes(contact.id, limit = 10)
        if (notes.isNotEmpty()) {
            append("<table><tr><th></th><th>Сообщение</th><th>Когда</th><th>Что с ним</th></tr>")
            notes.forEach { append(noteRow(it)) }
            append("</table>")
        }
        append(
            "<form method=\"post\" action=\"/admin/peers/contacts/${esc(
                contact.id,
            )}/note\" style=\"display:flex;gap:.5rem;margin:.5rem 0\">" +
                "<input name=\"text\" $input placeholder=\"Тестовое сообщение\"><button>Отправить</button></form>",
        )
        append(filesBlock(peers, contact))
        append(
            "<form method=\"post\" action=\"/admin/peers/contacts/${esc(contact.id)}/remove\" " +
                "onsubmit=\"return confirm('Убрать контакт? Письма от него перестанут читаться.')\"><button>Убрать контакт</button></form>",
        )
    }
    append("<h4>Добавить друга</h4>")
    append(
        """<form method="post" action="/admin/peers/contacts" style="display:grid;gap:.4rem">
        <input name="name" $input placeholder="Имя друга">
        <textarea name="card" rows="3" $input placeholder="Его карточка: benbow1.…"></textarea>
        <span><button>Добавить контакт</button></span></form>""",
    )
    append("<form method=\"post\" action=\"/admin/peers/check\" style=\"margin-top:.8rem\"><button>Проверить ящик сейчас</button></form>")
    append("<p><a href=\"/#friends\">Обновить</a></p></section>")
}

private fun statusLine(status: PeerLink.Status, settings: MailSettings?): String = when (status) {
    PeerLink.Status.Off -> if (settings?.complete == true) "Выключено." else "Ящик не настроен: связь выключена."

    PeerLink.Status.Connecting -> "Подключаюсь…"

    is PeerLink.Status.Online -> buildString {
        append("На связи с ${stamp(status.since)}. Последний обмен ${stamp(status.lastRoundAt)}.")
        status.lastRound?.takeIf { it.waiting > 0 }?.let { append(" В ящике ждут писем от незнакомых серверов: ${it.waiting}.") }
        status.lastRound?.takeIf {
            it.refused > 0
        }?.let { append(" <b>Отклонено писем: ${it.refused}</b> (подпись или адрес не сошлись; подробности в журнале).") }
    }

    is PeerLink.Status.Failed -> if (status.retryAt == null) {
        "<span style=\"color:#b3261e\"><b>Ящик не пустил:</b> неверный логин или пароль приложения, либо IMAP выключен " +
            "в настройках почты.</span> Сервер не пробует снова, чтобы почта не заблокировала ящик: исправьте и сохраните. " +
            "<small>(${esc(status.message)})</small>"
    } else {
        "<span style=\"color:#b3261e\"><b>Нет связи:</b> ${esc(status.message)}</span>. Следующая попытка в ${stamp(status.retryAt)}."
    }
}

private fun noteRow(note: NoteView): String {
    val state = when {
        !note.outgoing -> "получено ${stamp(note.receivedAt)}" +
            (note.receivedAt?.let { " (в пути ${((it - note.at) / 1000).coerceAtLeast(0)} с)" } ?: "")

        note.ackedAt != null -> "доставлено, ответ ${stamp(note.ackedAt)}"

        note.sentAt != null -> "в ящике, ждёт сервер друга"

        else -> "ждёт отправки"
    }
    return "<tr><td>${if (note.outgoing) "→" else "←"}</td><td>${esc(note.text)}</td><td>${stamp(note.at)}</td><td>$state</td></tr>"
}
