package app.benbow.server.api

import app.benbow.core.api.SpeechModelSpec
import app.benbow.server.SERVER_VERSION
import app.benbow.server.SpeechModel
import app.benbow.server.backup.BackupStatus
import app.benbow.server.backup.MirrorChoice
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.MediaMove
import app.benbow.server.storage.MediaUnavailable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                "friends" -> call.respondAdmin(friendsInner(ctx.peers))
                "link" -> call.respondAdmin(linkInner(ctx.peers))
                "speech" -> call.respondAdmin(ctx.speech?.let(::speechInner).orEmpty())
                "mirror" -> call.respondAdmin(mirrorInner(ctx))
                "media" -> call.respondAdmin(mediaInner(ctx))
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

    // The second disk (ADR-042, P3): a folder checked before anything is saved, then a first copy in the background.
    post("/admin/mirror") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val jobs = ctx.backupJobs ?: return@post call.respondText("Копии выключены", status = HttpStatusCode.ServiceUnavailable)
        val text = call.receiveParameters()["mirror-path"].orEmpty()
        when (val verdict = withContext(Dispatchers.IO) { jobs.chooseMirror(text) }) {
            is MirrorChoice.Verdict.Refused -> call.respondAdmin(
                adminPage(ctx, admin = true, Flash("Эта папка не подходит: ${verdict.reason}", error = true)),
                HttpStatusCode.BadRequest,
            )

            is MirrorChoice.Verdict.Fine -> call.respondRedirect("/?done=mirror#backups")
        }
    }

    mediaMoveRoutes(ctx)

    post("/admin/mirror/off") {
        admin(ctx, "mirror-off#backups") { withContext(Dispatchers.IO) { ctx.backupJobs?.turnOffMirror() } }
    }

    // Deleting for good (ADR-041): within the quarantine a purge can be taken back, or its space freed at once.
    post("/admin/purges/{id}/undo") {
        admin(ctx, "undone#space") { ctx.purges?.undo(call.parameters["id"]!!) }
    }

    post("/admin/purges/{id}/free") {
        admin(ctx, "freed#space") { ctx.purges?.freeNow(call.parameters["id"]!!) }
    }
}

internal suspend fun RoutingContext.admin(ctx: ServerContext, done: String, action: suspend () -> Unit) {
    if (!adminAllowed(call, ctx)) return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
    try {
        action()
    } catch (e: MediaUnavailable) { // undoing or freeing a purge needs the media folder (ADR-042)
        val flash = Flash("Не получилось: ${e.message}", error = true)
        return call.respondAdmin(adminPage(ctx, admin = true, flash), HttpStatusCode.ServiceUnavailable)
    }
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

private fun adminTabs(ctx: ServerContext): List<Tab> {
    val backups = ctx.backupStatus()
    val staleBackups = backups != null &&
        (
            backups.lastError != null || backups.lastSnapshotAt == null || backups.mirrorProblem != null ||
                backups.mirrorPath == null || mirrorStale(backups) ||
                System.currentTimeMillis() - backups.lastSnapshotAt > 48 * 3_600_000L
            )
    val mediaGone = ctx.blobs?.ready() == false || ctx.mediaMove?.moves()?.current?.error != null
    return listOfNotNull(
        Tab("overview", "Сервер", "server"),
        Tab("devices", "Устройства", "phone"),
        Tab("backups", "Копии", "backup", alert = staleBackups || mediaGone),
        ctx.storage?.let { Tab("space", "Место", "disk") },
        ctx.speech?.let { Tab("speech", "Голос", "mic") },
        Tab("friends", "Друзья", "chat"),
        Tab("link", "Связь", "sync", alert = linkNeedsAttention(ctx.peers)),
    )
}

/** The whole page; [flash] is what the last action has to say. */
internal fun adminPage(ctx: ServerContext, admin: Boolean, flash: Flash? = null, plan: MediaMove.Verdict.Fine? = null): String {
    val body = buildString {
        append(overview(ctx, admin))
        if (admin) {
            append(devicesSection(ctx))
            append(backupsSection(ctx, plan))
            ctx.storage?.let { append(spaceSection(it, ctx.purges)) }
            ctx.speech?.let { append(speechSection(it)) }
            append(friendsSection(ctx.peers))
            append(linkSection(ctx.peers))
        }
    }
    val subtitle = "${versionLine()} · хранилище ${ctx.db.storeId.take(8)}"
    return adminShell(if (admin) adminTabs(ctx) else emptyList(), body, flash, subtitle, extra = if (admin) folderDialog() else "")
}

private fun overview(ctx: ServerContext, admin: Boolean): String = buildString {
    append("<section id=\"overview\" class=\"page\">${pageTitle("Сервер", "server", "Состояние сервера и как открыть Benbow в браузере.")}")
    append("<div class=\"card\">")
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
    if (admin) ctx.blobs?.let { mediaBanner(it)?.let(::append) }
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
    append("<section id=\"devices\" class=\"page\">")
    append(pageTitle("Устройства", "phone", "Телефоны, компьютеры и браузеры, которые синхронизируются с этим сервером."))
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

private fun backupsSection(ctx: ServerContext, plan: MediaMove.Verdict.Fine?): String = buildString {
    append("<section id=\"backups\" class=\"page\">")
    append(pageTitle("Резервные копии", "backup", "Проверенные снимки базы, копия на второй диск и медиафайлы."))
    append("<div class=\"card\">")
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
        append("<dl class=\"kv\"><dt>Календарь</dt><dd>")
        append(
            if (ctx.calendar == null) {
                "<b>не открылся</b> (дневник работает; причина в журнале сервера)"
            } else {
                "отдельный файл, последняя копия ${status.calendarSnapshotAt?.let { time(it) } ?: "ещё не делалась"}"
            },
        )
        status.calendarError?.let { append(" · ошибка: ${esc(it)}") }
        append("</dd></dl>")
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
    append("</div>")
    append(mediaCard(ctx, plan))
    if (status != null) append(mirrorCard(ctx, status))
    append("</section>")
}

/** «Второй диск» (ADR-042, P3): where it stands (live, since a first copy takes a while), another folder, turning it off. */
private fun mirrorCard(ctx: ServerContext, status: BackupStatus): String = buildString {
    append("<div class=\"card\"><h3>${icon("disk")}Второй диск</h3>")
    append("<div data-live=\"mirror\">${mirrorInner(ctx)}</div>")
    if (ctx.backupJobs != null) {
        val none = status.mirrorPath == null
        append("<details class=\"edit\"${if (none) " open" else ""}><summary class=\"btn text\">${icon("edit")}")
        append("${if (none) "Выбрать папку" else "Другая папка"}</summary>")
        append("<form method=\"post\" action=\"/admin/mirror#backups\" class=\"stack\"><div class=\"path-row\">")
        append(field("mirror-path", "Папка на другом диске этого компьютера", status.mirrorPath.orEmpty(), "H:\\Benbow-mirror"))
        append(
            "<button class=\"btn outlined\" type=\"button\" data-pick-folder=\"mirror-path\" data-no-drive>${icon(
                "folder_open",
            )}Выбрать…</button></div>",
        )
        append(
            "<p class=\"hint\">Пустая папка или новая (сервер её создаст) на другом физическом диске, не сетевая. Сервер проверит " +
                "диск, место и запись, а потом скопирует туда снимки, медиафайлы, настройки и записи в Markdown. Прежняя папка, если " +
                "была, останется как есть.</p>",
        )
        append("<div class=\"row end\"><button class=\"btn\">Сохранить и скопировать</button></div></form></details>")
        if (!none) {
            append(
                "<div class=\"row end\">${postButton(
                    "/admin/mirror/off",
                    "Отключить",
                    "outlined danger",
                    confirm = "Отключить второй диск? Копии туда больше не пойдут; уже сделанные останутся в папке.",
                )}</div>",
            )
        }
    }
    append("</div>")
}

/** The live part of «Второй диск»: what stops it, what to know, the copy under way, the last one. */
internal fun mirrorInner(ctx: ServerContext): String = buildString {
    val status = ctx.backupStatus() ?: return@buildString
    status.mirrorProblem?.let { append("<div class=\"banner err\">${icon("error")}<div>${esc(it)}</div></div>") }
    append(mirrorBanners(status, ctx))
    ctx.backupJobs?.progress?.let { p ->
        val share = if (p.totalBytes > 0) (p.bytes * 10 / p.totalBytes).toInt().coerceIn(0, 10) * 10 else 0
        val gb = { bytes: Long -> "%.1f".format(bytes / 1_073_741_824.0) }
        append("<div class=\"progress w$share\"><span></span></div>")
        append("<p class=\"muted\">Копируются медиафайлы: ${p.files} из ${p.totalFiles}, ${gb(p.bytes)} из ${gb(p.totalBytes)} ГБ…</p>")
    }
    append("<p>${mirrorLine(status)}</p>")
}

/** A mirror that copied everything once and hasn't for 48 hours, with nothing said about why (ADR-042). */
private fun mirrorStale(status: BackupStatus) = status.mirrorProblem == null && status.lastMirrorAt != null &&
    System.currentTimeMillis() - status.lastMirrorAt > 48 * 3_600_000L

/** No second disk, an old copy, and what doesn't stop a pass but should be seen (ADR-042, P2). */
private fun mirrorBanners(status: BackupStatus, ctx: ServerContext): String = buildString {
    if (status.mirrorPath == null) {
        append("<div class=\"banner err\">${icon("error")}<div><b>Второго диска нет.</b> База, её снимки и медиафайлы лежат ")
        append("только на этом диске: если он сломается, останется лишь то, что есть на устройствах. Выберите папку на другом ")
        append("диске ниже.</div></div>")
    }
    if (mirrorStale(status)) {
        append("<div class=\"banner err\">${icon("schedule")}<div><b>Копия на втором диске устарела:</b> последняя полная ")
        append("${time(status.lastMirrorAt)}.</div></div>")
    }
    status.mirrorWarnings.forEach { append("<div class=\"banner info\">${icon("disk")}<div>${esc(it)}</div></div>") }
}

/** Where the second disk stands (ADR-042): its last full copy, the snapshots alone, or why there is none. */
private fun mirrorLine(status: BackupStatus): String {
    val path = status.mirrorPath ?: return "не выбран"
    val where = " в <code>${esc(path)}</code>"
    return when {
        status.lastMirrorAt != null -> "копия ${time(status.lastMirrorAt)}$where"
        status.mirrorDbAt != null -> "снимки базы ${time(status.mirrorDbAt)}, медиафайлы ещё нет$where"
        else -> "ещё не копировал$where"
    }
}

/** A red banner while the media folder isn't there (ADR-042): what the server does meanwhile. */
internal fun mediaBanner(store: BlobStore): String? {
    if (store.ready()) return null
    return "<div class=\"banner err\">${icon("error")}<div><b>Папка медиа недоступна:</b> ${esc(store.folder.describe())}. " +
        "Дневник работает; файлы не принимаются и не отдаются, устройства держат их у себя и отправят позже.</div></div>"
}

/**
 * The one-time model download for voice transcription, on a click. Besides it the server goes to the internet only to
 * read a page for a link card the web client asks about (ADR-038) and to reach the friends' mailbox (ADR-039).
 */
private fun speechSection(model: SpeechModel): String {
    val live = if (model.state is SpeechModel.State.Downloading) " data-live=\"speech\"" else ""
    val title = pageTitle("Расшифровка голосовых", "mic", "Модель, которой телефоны превращают голосовые в текст без интернета.")
    return "<section id=\"speech\" class=\"page\">$title<div class=\"card\"$live>${speechInner(model)}</div></section>"
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
