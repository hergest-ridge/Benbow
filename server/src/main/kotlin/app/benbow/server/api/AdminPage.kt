package app.benbow.server.api

import app.benbow.server.SERVER_VERSION
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Status page for everyone; pairing codes, devices and backups only for the server PC itself.
 * Admin POSTs also require a same-origin request, so a web page open in the PC browser can't
 * trigger them (CSRF).
 */
internal fun Route.adminRoutes(ctx: ServerContext) {
    get("/") { call.respondText(statusPage(ctx, admin = ctx.isLoopback(call)), ContentType.Text.Html) }

    post("/admin/pairing-code") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        ctx.pairing.issue()
        call.respondRedirect("/")
    }

    post("/admin/backup") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        ctx.backupNow()
        call.respondRedirect("/")
    }

    post("/admin/devices/{id}/revoke") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        ctx.devices.revoke(call.parameters["id"]!!)
        call.respondRedirect("/")
    }
}

private fun adminAllowed(call: ApplicationCall, ctx: ServerContext): Boolean {
    if (!ctx.isLoopback(call)) return false
    val fetchSite = call.request.headers["Sec-Fetch-Site"]
    if (fetchSite != null && fetchSite != "same-origin" && fetchSite != "none") return false
    val origin = call.request.headers["Origin"] ?: return true
    return origin in listOf("https://localhost:${ctx.port}", "https://127.0.0.1:${ctx.port}", "https://[::1]:${ctx.port}")
}

private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

private fun time(ms: Long?) = ms?.let { timeFormat.format(Instant.ofEpochMilli(it)) } ?: "—"

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun statusPage(ctx: ServerContext, admin: Boolean): String = buildString {
    append(
        """
        <!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Benbow</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:system-ui,sans-serif;max-width:44rem;margin:2.5rem auto;padding:0 1rem;background:#fbf6ee;color:#1f1b16}
        code{background:#f0e0cf;padding:.1rem .3rem;border-radius:.3rem;word-break:break-all}
        .code{font-size:2.4rem;letter-spacing:.4rem;font-weight:700} table{border-collapse:collapse;width:100%}
        td,th{padding:.35rem .5rem;border-bottom:1px solid #e3d3c2;text-align:left} button{font:inherit;padding:.35rem .8rem;border-radius:.5rem;border:1px solid #8a5100;background:#ffdcbe;cursor:pointer}
        section{margin:1.8rem 0}</style></head><body>
        <h1>⚓ Адмирал Бенбоу</h1>
        <p>Сервер работает. Версия <code>$SERVER_VERSION</code>, хранилище <code>${ctx.db.storeId}</code>.</p>
        <p>Отпечаток сертификата (SHA-256):<br><code>${ctx.tlsFingerprint}</code></p>
        <p><a href="/api/docs">Документация API</a></p>
        """.trimIndent(),
    )
    if (!admin) {
        append("<p><i>Управление доступно только на этом компьютере: https://localhost:${ctx.port}</i></p></body></html>")
        return@buildString
    }
    append("<section><h2>Подключить устройство</h2>")
    val active = ctx.pairing.active()
    if (active != null) {
        append("<p>Введите на телефоне адрес этого компьютера, порт <code>${ctx.port}</code> и код:</p>")
        append("<p class=\"code\">${active.first}</p><p>Код действует до ${time(active.second)} и сработает один раз.</p>")
    }
    append(
        "<form method=\"post\" action=\"/admin/pairing-code\"><button>${if (active == null) "Получить код" else "Новый код"}</button></form></section>",
    )

    append("<section><h2>Устройства</h2>")
    val devices = ctx.devices.list()
    if (devices.isEmpty()) {
        append("<p>Пока нет подключённых устройств.</p>")
    } else {
        append("<table><tr><th>Устройство</th><th>Подключено</th><th>Последний раз</th><th></th></tr>")
        for (d in devices) {
            val action = if (d.revokedAt == null) {
                "<form method=\"post\" action=\"/admin/devices/${esc(d.id)}/revoke\"><button>Отключить</button></form>"
            } else {
                "отключено ${time(d.revokedAt)}"
            }
            append(
                "<tr><td>${esc(
                    d.name,
                )} <small>(${esc(
                    d.platform,
                )})</small></td><td>${time(d.createdAt)}</td><td>${time(d.lastSeenAt)}</td><td>$action</td></tr>",
            )
        }
        append("</table>")
    }
    append("</section><section><h2>Резервные копии</h2>")
    val status = ctx.backupStatus()
    if (status != null) {
        val stale = status.lastSnapshotAt == null || System.currentTimeMillis() - status.lastSnapshotAt > 48 * 3_600_000L
        if (stale) append("<p style=\"color:#b3261e\"><b>Внимание:</b> свежей проверенной копии нет больше 48 часов.</p>")
        status.lastError?.let { append("<p style=\"color:#b3261e\"><b>Ошибка последнего запуска:</b> ${esc(it)}</p>") }
        append(
            "<p>Копия на второй диск: ${status.lastMirrorAt?.let {
                "последняя ${time(it)}"
            } ?: "не настроена или ещё не выполнялась (backup.mirror в benbow.properties)"}</p>",
        )
    }
    val backups = ctx.db.backups?.history().orEmpty().takeLast(5).reversed()
    if (backups.isEmpty()) {
        append("<p>Резервных копий пока нет.</p>")
    } else {
        append("<table><tr><th>Когда</th><th>Причина</th><th>Проверена</th></tr>")
        backups.forEach {
            append("<tr><td>${time(it.createdAt)}</td><td>${esc(it.reason)}</td><td>${if (it.verified) "да" else "НЕТ"}</td></tr>")
        }
        append("</table>")
    }
    if (admin) append("<form method=\"post\" action=\"/admin/backup\"><button>Сделать копию сейчас</button></form>")
    ctx.dataDir?.let { append("<p>Данные: <code>${esc(it.toString())}</code></p>") }
    append("</section></body></html>")
}
