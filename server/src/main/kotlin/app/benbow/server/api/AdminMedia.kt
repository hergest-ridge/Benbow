package app.benbow.server.api

import app.benbow.server.storage.MediaMove
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * Moving the media folder on the admin page (ADR-042, as built 7.19 P5): a folder is checked and what it takes is shown
 * first; only «Начать перенос» starts the copy, and the switch comes by itself once everything checks (P4).
 */
internal fun Route.mediaMoveRoutes(ctx: ServerContext) {
    post("/admin/media/check") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val move = ctx.mediaMove ?: return@post call.respondText("Перенос недоступен", status = HttpStatusCode.ServiceUnavailable)
        val text = call.receiveParameters()["media-path"].orEmpty()
        when (val verdict = withContext(Dispatchers.IO) { move.preview(text) }) {
            is MediaMove.Verdict.Refused -> call.respondAdmin(
                adminPage(ctx, admin = true, Flash("Эта папка не подходит: ${verdict.reason}", error = true)),
                HttpStatusCode.BadRequest,
            )

            is MediaMove.Verdict.Fine -> call.respondAdmin(adminPage(ctx, admin = true, plan = verdict))
        }
    }

    post("/admin/media/move") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val move = ctx.mediaMove ?: return@post call.respondText("Перенос недоступен", status = HttpStatusCode.ServiceUnavailable)
        val text = call.receiveParameters()["media-path"].orEmpty()
        when (val verdict = withContext(Dispatchers.IO) { move.start(text) }) {
            is MediaMove.Verdict.Refused -> call.respondAdmin(
                adminPage(ctx, admin = true, Flash("Перенос не начат: ${verdict.reason}", error = true)),
                HttpStatusCode.BadRequest,
            )

            is MediaMove.Verdict.Fine -> call.respondRedirect("/?done=media-move#backups")
        }
    }

    post("/admin/media/retry") {
        admin(ctx, "media-retry#backups") { withContext(Dispatchers.IO) { ctx.mediaMove?.retry() } }
    }

    post("/admin/media/cancel") {
        admin(ctx, "media-cancel#backups") { withContext(Dispatchers.IO) { ctx.mediaMove?.cancel() } }
    }
}

/** «Медиафайлы»: where they are, a move under way (live), and moving them; with [plan], the question before a move. */
internal fun mediaCard(ctx: ServerContext, plan: MediaMove.Verdict.Fine?): String = buildString {
    if (ctx.blobs == null) return@buildString
    append("<div class=\"card\"><h3>${icon("folder")}Медиафайлы</h3>")
    append("<div data-live=\"media\">${mediaInner(ctx)}</div>")
    val move = ctx.mediaMove
    when {
        move == null -> Unit

        plan != null -> {
            append("<div class=\"banner info\">${icon("folder_open")}<div><b>Перенести в <code>${esc(plan.path.toString())}</code>?</b> ")
            append("Скопируется ${plan.files} ${files(plan.files)}, ${gb(plan.bytes)} ГБ; там свободно ${gb(plan.free)} ГБ. ")
            append("Сервер копирует и проверяет каждый файл, не останавливаясь, а потом за несколько секунд переключается сам. ")
            append("Прежняя папка останется как есть.</div></div>")
            plan.warnings.forEach { append("<div class=\"banner info\">${icon("disk")}<div>${esc(it)}</div></div>") }
            append("<form method=\"post\" action=\"/admin/media/move#backups\" class=\"row end\" data-busy=\"Начинается…\">")
            append("<input type=\"hidden\" name=\"media-path\" value=\"${esc(plan.path.toString())}\">")
            append("<a class=\"btn text\" href=\"/#backups\">Отмена</a>")
            append("<button class=\"btn\">${icon("folder_open")}<span>Начать перенос</span></button></form>")
        }

        move.moves().current == null -> {
            append("<details class=\"edit\"><summary class=\"btn text\">${icon("edit")}Перенести в другую папку</summary>")
            append("<form method=\"post\" action=\"/admin/media/check#backups\" class=\"stack\"><div class=\"path-row\">")
            append(field("media-path", "Новая папка на этом компьютере", "", "D:\\Benbow-media"))
            append("<button class=\"btn outlined\" type=\"button\" data-pick-folder=\"media-path\" data-no-drive>")
            append("${icon("folder_open")}Выбрать…</button></div>")
            append(
                "<p class=\"hint\">Пустая папка или новая (сервер её создаст) на локальном диске с NTFS, не там, где второй диск. " +
                    "Сначала сервер покажет, сколько скопирует и хватит ли места; перенос начнётся только по вашей кнопке.</p>",
            )
            append("<div class=\"row end\"><button class=\"btn\">Проверить</button></div></form></details>")
        }
    }
    append("</div>")
}

/** The live part of «Медиафайлы»: the folder, a move under way or stopped, the folders earlier moves left. */
internal fun mediaInner(ctx: ServerContext): String = buildString {
    val store = ctx.blobs ?: return@buildString
    mediaBanner(store)?.let(::append)
    val (count, bytes) = store.stats()
    val free = runCatching { Files.getFileStore(store.root).usableSpace }.getOrNull()
    append("<dl class=\"kv\"><dt>Папка</dt><dd><code>${esc(store.root.toString())}</code></dd>")
    append("<dt>Файлы</dt><dd>$count, ${gb(bytes)} ГБ</dd>")
    free?.let { append("<dt>Свободно на диске</dt><dd>${gb(it)} ГБ</dd>") }
    append("</dl>")
    val move = ctx.mediaMove ?: return@buildString
    val moves = move.moves()
    moves.current?.let { current ->
        val to = esc(current.to)
        if (current.error != null) {
            append("<div class=\"banner err\">${icon("error")}<div><b>Перенос в <code>$to</code> остановлен:</b> ${esc(current.error)}. ")
            append("Медиафайлы по-прежнему в прежней папке, всё работает.</div></div>")
            append("<div class=\"row end\">")
            append(cancelButton())
            append(postButton("/admin/media/retry#backups", "Продолжить", "tonal", "refresh", busy = "Продолжается…"))
            append("</div>")
        } else {
            val p = move.progress
            val share = if (p != null && p.totalBytes > 0) (p.bytes * 10 / p.totalBytes).toInt().coerceIn(0, 10) * 10 else 0
            append("<div class=\"progress w$share\"><span></span></div>")
            append(
                "<p class=\"muted\">Перенос в <code>$to</code>: " +
                    (
                        p?.let { "${it.files} из ${it.totalFiles} ${files(it.totalFiles)}, ${gb(it.bytes)} из ${gb(it.totalBytes)} ГБ…" }
                            ?: "сверка и переключение…"
                        ) +
                    "</p>",
            )
            append("<div class=\"row end\">${cancelButton()}</div>")
        }
    }
    for (left in moves.left.reversed()) {
        append("<div class=\"banner ok\">${icon("ok")}<div>Перенесено ${time(left.at)} из <code>${esc(left.path)}</code>. ")
        append("Прежняя папка оставлена как была и сервером больше не используется.</div></div>")
    }
}

private fun cancelButton() = postButton(
    "/admin/media/cancel#backups",
    "Отменить перенос",
    "outlined",
    confirm = "Отменить перенос? Медиафайлы останутся в прежней папке; то, что уже скопировано, останется в новой.",
)

private fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)

private fun files(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "файл"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "файла"
    else -> "файлов"
}
