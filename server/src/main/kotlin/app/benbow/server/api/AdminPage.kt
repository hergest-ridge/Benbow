package app.benbow.server.api

import app.benbow.core.api.SpeechModelSpec
import app.benbow.server.SERVER_VERSION
import app.benbow.server.SpeechModel
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Status page for everyone; pairing codes, devices, backups and friends only for the server PC itself. Admin POSTs also
 * require a same-origin request, so a web page open in the PC browser can't trigger them (CSRF). Live parts of the page
 * (`?part=`) are refreshed by its script while they change.
 */
internal fun Route.adminRoutes(ctx: ServerContext) {
    adminAssets()

    get("/") {
        val admin = ctx.isLoopback(call)
        val part = call.request.queryParameters["part"]
        if (part != null) {
            if (!admin) return@get call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return@get when (part) {
                "friends" -> call.respondAdmin(peersInner(ctx.peers))
                "speech" -> call.respondAdmin(ctx.speech?.let(::speechInner).orEmpty())
                else -> call.respondText("Not found", status = HttpStatusCode.NotFound)
            }
        }
        val flash = call.request.queryParameters["done"]?.let(doneMessages::get)?.let { Flash(it, error = false) }
        call.respondAdmin(adminPage(ctx, admin, flash))
    }

    post("/admin/pairing-code") {
        admin(ctx, "code#devices") { ctx.pairing.issue() }
    }

    post("/admin/backup") {
        admin(ctx, "backup#backups") { ctx.backupNow() }
    }

    post("/admin/speech-model") {
        admin(ctx, "speech#speech") { ctx.speech?.download() }
    }

    post("/admin/devices/{id}/revoke") {
        admin(ctx, "revoked#devices") { ctx.devices.revoke(call.parameters["id"]!!) }
    }
}

private suspend fun RoutingContext.admin(ctx: ServerContext, done: String, action: suspend () -> Unit) {
    if (!adminAllowed(call, ctx)) return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
    action()
    call.respondRedirect("/?done=$done")
}

internal fun adminAllowed(call: ApplicationCall, ctx: ServerContext): Boolean {
    if (!ctx.isLoopback(call)) return false
    val fetchSite = call.request.headers["Sec-Fetch-Site"]
    if (fetchSite != null && fetchSite != "same-origin" && fetchSite != "none") return false
    val origin = call.request.headers["Origin"] ?: return true
    return origin in listOf("https://localhost:${ctx.port}", "https://127.0.0.1:${ctx.port}", "https://[::1]:${ctx.port}")
}

private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

internal fun time(ms: Long?) = ms?.let { timeFormat.format(Instant.ofEpochMilli(it)) } ?: "—"

internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private val adminTabs = listOf(
    Tab("overview", "Сервер", "server"),
    Tab("devices", "Устройства", "phone"),
    Tab("backups", "Копии", "backup"),
    Tab("speech", "Голос", "mic"),
    Tab("friends", "Друзья", "group"),
)

/** The whole page; [flash] is what the last action has to say. */
internal fun adminPage(ctx: ServerContext, admin: Boolean, flash: Flash? = null): String {
    val body = buildString {
        append(overview(ctx, admin))
        if (admin) {
            append(devicesSection(ctx))
            append(backupsSection(ctx))
            ctx.speech?.let { append(speechSection(it)) }
            append(peersSection(ctx.peers))
        }
    }
    return adminShell(if (admin) adminTabs else emptyList(), body, flash, "${versionLine()} · хранилище ${ctx.db.storeId.take(8)}")
}

private fun overview(ctx: ServerContext, admin: Boolean): String = buildString {
    append("<section id=\"overview\">${sectionTitle("overview", "Сервер", "server")}<div class=\"card\">")
    append("<div class=\"row\"><h3>Сервер работает</h3>${chip("ok", "ok", "в сети")}</div>")
    append("<dl class=\"kv\"><dt>Версия</dt><dd>$SERVER_VERSION</dd>")
    append("<dt>Хранилище</dt><dd class=\"mono\">${esc(ctx.db.storeId)}</dd>")
    append("<dt>Сертификат</dt><dd><span class=\"mono\">${esc(ctx.tlsFingerprint)}</span> ")
    append(
        "<button class=\"icon-btn\" type=\"button\" title=\"Скопировать отпечаток\" data-copy=\"${esc(
            ctx.tlsFingerprint,
        )}\">${icon("copy")}</button></dd>",
    )
    if (admin) ctx.dataDir?.let { append("<dt>Данные</dt><dd class=\"mono\">${esc(it.toString())}</dd>") }
    append("</dl>")
    append(
        "<p class=\"muted\">Benbow в браузере открывается на любом компьютере домашней сети: адрес этого компьютера, порт " +
            "<code>${ctx.port}</code>, <code>/app/</code>. Браузер один раз предупредит о самоподписанном сертификате; " +
            "сверьте его отпечаток с тем, что выше.</p>",
    )
    append("<div class=\"row\"><a class=\"btn text\" href=\"/api/docs\">Документация API</a></div>")
    if (!admin) {
        append("<div class=\"banner info\">${icon("key")}<div>Управление сервером открыто только на этом компьютере: ")
        append("<code>https://localhost:${ctx.port}</code></div></div>")
    }
    append("</div></section>")
}

private fun devicesSection(ctx: ServerContext): String = buildString {
    append("<section id=\"devices\">${sectionTitle("devices", "Устройства", "phone")}")
    append("<div class=\"card\"><h3>${icon("key")}Подключить устройство</h3>")
    val active = ctx.pairing.active()
    if (active != null) {
        append("<p class=\"lead\">Введите на телефоне адрес этого компьютера, порт <code>${ctx.port}</code> и этот код ")
        append("(в браузере — только код):</p><div class=\"code\">${esc(active.first)}</div>")
        append("<p class=\"muted\">Действует до ${time(active.second)} и сработает один раз.</p>")
        append(postButton("/admin/pairing-code", "Новый код", "tonal", "refresh"))
    } else {
        append(
            "<p class=\"lead\">Код из шести цифр связывает новое устройство с этим сервером. Он живёт пять минут и срабатывает один раз.</p>",
        )
        append(postButton("/admin/pairing-code", "Получить код", "", "key"))
    }
    append("</div><div class=\"card\"><h3>Подключённые</h3>")
    val devices = ctx.devices.list()
    if (devices.isEmpty()) append("<p class=\"muted\">Пока нет подключённых устройств.</p>")
    append("<div class=\"list\">")
    for (d in devices) {
        val revoked = d.revokedAt != null
        append("<div class=\"item${if (revoked) " off" else ""}\"><div class=\"avatar\">")
        append(icon(if (d.platform.contains("android", ignoreCase = true)) "phone" else "computer"))
        append("</div><div class=\"body\"><div class=\"headline\">${esc(d.name)}</div><div class=\"support\">${esc(d.platform)} · ")
        append("подключено ${time(d.createdAt)} · последний раз ${time(d.lastSeenAt)}</div></div>")
        if (revoked) {
            append(chip("", null, "отключено ${time(d.revokedAt)}"))
        } else {
            append(
                postButton(
                    "/admin/devices/${d.id}/revoke",
                    "Отключить",
                    "outlined danger",
                    confirm = "Отключить «${d.name}»? Устройство перестанет синхронизироваться, пока его не подключат заново.",
                ),
            )
        }
        append("</div>")
    }
    append("</div></div></section>")
}

private fun backupsSection(ctx: ServerContext): String = buildString {
    append("<section id=\"backups\">${sectionTitle("backups", "Резервные копии", "backup")}<div class=\"card\">")
    val status = ctx.backupStatus()
    val backups = ctx.db.backups?.history().orEmpty().takeLast(5).reversed()
    if (status != null) {
        val stale = status.lastSnapshotAt == null || System.currentTimeMillis() - status.lastSnapshotAt > 48 * 3_600_000L
        if (stale) {
            append("<div class=\"banner err\">${icon("error")}<div><b>Свежей проверенной копии нет больше 48 часов.</b></div></div>")
        } else {
            append("<div class=\"banner ok\">${icon("ok")}<div>Последняя проверенная копия ${time(status.lastSnapshotAt)}</div></div>")
        }
        status.lastError?.let { append("<div class=\"banner err\">${icon("error")}<div>Ошибка последнего запуска: ${esc(it)}</div></div>") }
        append(
            "<dl class=\"kv\"><dt>Второй диск</dt><dd>${status.lastMirrorAt?.let {
                "копия ${time(it)}"
            } ?: "не настроен или ещё не копировал (<code>backup.mirror</code> в <code>benbow.properties</code>)"}</dd>",
        )
        append("<dt>Календарь</dt><dd>")
        append(
            if (ctx.calendar == null) {
                "<b>не открылся</b> (дневник работает; причина в журнале сервера)"
            } else {
                "отдельный файл, последняя копия ${status.calendarSnapshotAt?.let { time(it) } ?: "ещё не делалась"}"
            },
        )
        status.calendarError?.let { append(" · ошибка: ${esc(it)}") }
        append("</dd>")
        ctx.blobs?.let { store ->
            val (count, bytes) = store.stats()
            append(
                "<dt>Медиафайлы</dt><dd>$count, ${"%.1f".format(
                    bytes / 1_073_741_824.0,
                )} ГБ в <code>${esc(store.root.toString())}</code></dd>",
            )
        }
        append("</dl>")
    }
    if (backups.isEmpty()) {
        append("<p class=\"muted\">Резервных копий пока нет.</p>")
    } else {
        append("<div class=\"list\">")
        for (b in backups) {
            append(
                "<div class=\"item\"><div class=\"avatar\">${icon(
                    "backup",
                )}</div><div class=\"body\"><div class=\"headline\">${time(b.createdAt)}</div>",
            )
            append("<div class=\"support\">${esc(b.reason)}</div></div>")
            append(if (b.verified) chip("ok", "check", "проверена") else chip("err", "error", "НЕ проверена"))
            append("</div>")
        }
        append("</div>")
    }
    append("<div class=\"row end\">${postButton("/admin/backup", "Сделать копию сейчас", "tonal", "backup", busy = "Делается…")}</div>")
    append("</div></section>")
}

/**
 * The one-time model download for voice transcription, on a click. Besides it the server goes to the internet only to
 * read a page for a link card the web client asks about (ADR-038) and to reach the friends' mailbox (ADR-039).
 */
private fun speechSection(model: SpeechModel): String {
    val live = if (model.state is SpeechModel.State.Downloading) " data-live=\"speech\"" else ""
    return "<section id=\"speech\">${sectionTitle(
        "speech",
        "Расшифровка голосовых",
        "mic",
    )}<div class=\"card\"$live>${speechInner(model)}</div></section>"
}

internal fun speechInner(model: SpeechModel): String = buildString {
    val mb = { bytes: Long -> "%.0f".format(bytes / 1_000_000.0) }
    append(
        "<p class=\"lead\">Телефоны расшифровывают голосовые сами, без интернета, моделью GigaAM v3 (лицензия MIT). Взять её они " +
            "могут только отсюда. Сервер скачивает её один раз по этой кнопке с официальной страницы sherpa-onnx на GitHub и " +
            "сверяет каждый файл с отпечатком, записанным в программе.</p>",
    )
    when (val state = model.state) {
        SpeechModel.State.Ready -> append(chip("ok", "ok", "Модель на месте (${mb(SpeechModelSpec.totalSize)} МБ)"))

        is SpeechModel.State.Downloading -> {
            val share = if (state.total > 0) (state.done * 10 / state.total).coerceIn(0, 10) * 10 else 0
            append(
                "<div class=\"progress w$share\"><span></span></div><p class=\"muted\">Скачивается: ${mb(
                    state.done,
                )} из ${mb(state.total)} МБ…</p>",
            )
        }

        SpeechModel.State.Missing -> append(
            postButton("/admin/speech-model", "Скачать модель (${mb(SpeechModelSpec.archive.size)} МБ)", "", "download"),
        )

        is SpeechModel.State.Failed -> {
            append("<div class=\"banner err\">${icon("error")}<div>Не получилось: ${esc(state.message)}</div></div>")
            append(postButton("/admin/speech-model", "Попробовать ещё раз", "tonal", "refresh"))
        }
    }
}
