package app.benbow.server.api

import app.benbow.server.offsite.OffsiteCopy
import app.benbow.server.offsite.OffsiteKey
import app.benbow.server.offsite.OffsiteSetup
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Копия вне дома» on the admin page (ADR-043, as built 7.7 E4): turned on in two steps (the folder and the way in, then
 * the code typed back or a passphrase given twice), its state live, the code to show, download or print, the passphrase
 * set, changed and checked, and turning it off. The code page and its file are for the server PC only, as all of admin.
 */
internal fun Route.offsiteRoutes(ctx: ServerContext) {
    post("/admin/offsite/begin") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val setup = ctx.offsiteSetup ?: return@post call.respondText("Недоступно", status = HttpStatusCode.ServiceUnavailable)
        val form = call.receiveParameters()
        when (val verdict = withContext(Dispatchers.IO) { setup.begin(form["offsite-path"].orEmpty(), form["way"] == "passphrase") }) {
            is OffsiteSetup.Verdict.Refused -> call.respondAdmin(
                adminPage(ctx, admin = true, Flash("Эта папка не подходит: ${verdict.reason}", error = true)),
                HttpStatusCode.BadRequest,
            )

            is OffsiteSetup.Verdict.Fine -> call.respondAdmin(adminPage(ctx, admin = true, offsiteStep = setup.pending))
        }
    }

    post("/admin/offsite/confirm") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val setup = ctx.offsiteSetup ?: return@post call.respondText("Недоступно", status = HttpStatusCode.ServiceUnavailable)
        val form = call.receiveParameters()
        val id = form["step"].orEmpty()
        val outcome = withContext(Dispatchers.IO) {
            if (form["way"] == "passphrase") {
                setup.confirmPassphrase(id, form["pass1"].orEmpty(), form["pass2"].orEmpty())
            } else {
                setup.confirmCode(id, form["tail"].orEmpty())
            }
        }
        when (outcome) {
            OffsiteSetup.Outcome.Done -> call.respondRedirect("/?done=offsite#backups")

            is OffsiteSetup.Outcome.Refused -> call.respondAdmin(
                adminPage(ctx, admin = true, Flash("Не включено: ${outcome.reason}", error = true), offsiteStep = setup.pending(id)),
                HttpStatusCode.BadRequest,
            )
        }
    }

    post("/admin/offsite/off") {
        admin(ctx, "offsite-off#backups") { withContext(Dispatchers.IO) { ctx.offsiteSetup?.turnOff() } }
    }

    post("/admin/offsite/passphrase") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val setup = ctx.offsiteSetup ?: return@post call.respondText("Недоступно", status = HttpStatusCode.ServiceUnavailable)
        val form = call.receiveParameters()
        when (val outcome = withContext(Dispatchers.IO) { setup.setPassphrase(form["pass1"].orEmpty(), form["pass2"].orEmpty()) }) {
            OffsiteSetup.Outcome.Done -> call.respondRedirect("/?done=offsite-pass#backups")

            is OffsiteSetup.Outcome.Refused -> call.respondAdmin(
                adminPage(ctx, admin = true, Flash("Пароль не сменён: ${outcome.reason}", error = true)),
                HttpStatusCode.BadRequest,
            )
        }
    }

    post("/admin/offsite/check-passphrase") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val setup = ctx.offsiteSetup ?: return@post call.respondText("Недоступно", status = HttpStatusCode.ServiceUnavailable)
        val typed = call.receiveParameters()["pass"].orEmpty()
        val flash = when (withContext(Dispatchers.IO) { runCatching { setup.checkPassphrase(typed) }.getOrNull() }) {
            true -> Flash("Пароль подходит: key.age в копии открывается им", error = false)
            false -> Flash("Пароль не подходит к key.age в копии", error = true)
            null -> Flash("Проверить нечем: у копии нет пароля или папки нет на месте", error = true)
        }
        call.respondAdmin(adminPage(ctx, admin = true, flash))
    }

    // The code: on a page of its own (to print) and as a file (an age identity file with comments).
    get("/admin/offsite/code") {
        if (!adminAllowed(call, ctx)) return@get call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val key =
            codeKey(ctx, call.request.queryParameters["step"]) ?: return@get call.respondText("Кода нет", status = HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondAdmin(codePage(ctx, key, call.request.queryParameters["step"]))
    }

    get("/admin/offsite/code.txt") {
        if (!adminAllowed(call, ctx)) return@get call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val key =
            codeKey(ctx, call.request.queryParameters["step"]) ?: return@get call.respondText("Кода нет", status = HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "benbow-offsite-code.txt").toString(),
        )
        call.respondText(codeFile(ctx, key), ContentType.Text.Plain.withParameter("charset", "utf-8"))
    }
}

/** The key being set up ([step]) or the one in use. */
private fun codeKey(ctx: ServerContext, step: String?): OffsiteKey? = step?.let { ctx.offsiteSetup?.pending(it)?.key } ?: ctx.offsite?.key()

private fun codeFile(ctx: ServerContext, key: OffsiteKey): String = buildString {
    append("# Benbow: код восстановления копии вне дома\n")
    append("# Хранилище: ${ctx.db.storeId}\n")
    (ctx.offsite?.root ?: ctx.offsiteSetup?.pending?.path)?.let { append("# Копия: $it\n") }
    append("# Открытый ключ: ${key.recipient}\n#\n")
    append("# Это файл ключа age. Без Benbow копия открывается программой age (age-encryption.org), например:\n")
    append("#   age -d -i benbow-offsite-code.txt -o snapshot.sqlite db\\<снимок>.sqlite.age\n")
    append("# Храните его там, где он переживёт этот компьютер: в менеджере паролей, на бумаге.\n")
    append(key.secret).append('\n')
}

private fun codePage(ctx: ServerContext, key: OffsiteKey, step: String?): String {
    val query = step?.let { "?step=${esc(it)}" }.orEmpty()
    val body = buildString {
        append("<section id=\"code\" class=\"page\">")
        append(pageTitle("Код восстановления", "key", "Ключ копии вне дома. Без него эту копию не открыть, если дома не останется ничего."))
        append("<div class=\"card\"><pre class=\"code-key\">${esc(key.grouped())}</pre>")
        append("<div class=\"qr\">${qrSvg(key.secret)}</div>")
        append("<dl class=\"kv\"><dt>Хранилище</dt><dd class=\"mono\">${esc(ctx.db.storeId)}</dd>")
        append("<dt>Открытый ключ</dt><dd class=\"mono break\">${esc(key.recipient)}</dd></dl>")
        append("<p class=\"muted\">Без Benbow копия открывается программой age с сайта age-encryption.org: этот код — её ключ. ")
        append("Храните его там, где он переживёт этот компьютер.</p>")
        append("<div class=\"row end no-print\"><a class=\"btn tonal\" href=\"/admin/offsite/code.txt$query\" download>${icon("download")}")
        append(
            "<span>Скачать файл</span></a><button class=\"btn\" type=\"button\" data-print>${icon(
                "file",
            )}<span>Распечатать</span></button></div>",
        )
        append("</div></section>")
    }
    return adminShell(emptyList(), body, null, versionLine(), drawer = false)
}

/** «Копия вне дома» in «Копии»: off, a step of turning it on ([step]), or on with its state. */
internal fun offsiteCard(ctx: ServerContext, step: OffsiteSetup.Pending?): String = buildString {
    val copy = ctx.offsite ?: return@buildString
    val setup = ctx.offsiteSetup ?: return@buildString
    // The step lands in view: the section's address is #backups, and the card is far down it.
    append("<div class=\"card\" id=\"offsite\"${if (step != null) " data-focus" else ""}><h3>${icon("backup")}Копия вне дома</h3>")
    when {
        step != null -> append(stepForm(step))

        copy.root == null -> {
            append("<p class=\"lead\">Зашифрованная копия в папке, которая уходит из дома: в папке программы облака (Google Диск, ")
            append("Яндекс Диск) или на диске, который хранится не здесь. Она нужна, когда дома не уцелело ничего. ")
            append("Включать не обязательно.</p>")
            append(beginForm("Включить…", open = false))
        }

        else -> {
            append("<div data-live=\"offsite\">${offsiteInner(ctx)}</div>")
            append("<div class=\"row end\">")
            append("<a class=\"btn text\" href=\"/admin/offsite/code\" target=\"_blank\">${icon("key")}<span>Показать код</span></a>")
            append("</div>")
            if (setup.hasPassphrase()) {
                append(passphraseCheck())
                append(passphraseForm("Сменить пароль", "Новый пароль записывается в key.age; прежний может остаться в истории облака."))
            } else {
                append(passphraseForm("Задать пароль", "Тогда копию откроет и пароль: в папке появится key.age, ключ под паролем."))
            }
            append(beginForm("Другая папка", open = false))
            append(
                "<div class=\"row end\">${postButton(
                    "/admin/offsite/off#backups",
                    "Отключить",
                    "outlined danger",
                    confirm = "Отключить копию вне дома? Новые копии туда не пойдут; папка и ключ останутся как есть.",
                )}</div>",
            )
        }
    }
    append("</div>")
}

/** The live part of an «on» copy: where, how far, when last checked, what is wrong. */
internal fun offsiteInner(ctx: ServerContext): String = buildString {
    val copy = ctx.offsite ?: return@buildString
    val root = copy.root ?: return@buildString
    val key = copy.key()
    val passes = key?.let { copy.folder(it)?.passes() }
    val now = System.currentTimeMillis()
    when (val last = copy.last) {
        OffsiteCopy.Outcome.NoKey -> append(banner("err", "Ключа копии нет в папке данных: копия не делается."))
        is OffsiteCopy.Outcome.Skipped -> append(banner("err", "Копия не сделана: ${last.why}."))
        else -> Unit
    }
    ctx.backupStatus()?.offsiteError?.let { append(banner("err", "Последний проход не удался: $it")) }
    passes?.checkProblem?.let { append(banner("err", "Проверка копии: $it.")) }
    val checked = passes?.checkedAt
    val since = checked ?: passes?.dbAt
    if (since != null && now - since > STALE) {
        append(
            banner(
                "err",
                if (checked ==
                    null
                ) {
                    "Копия вне дома ещё ни разу не проверилась."
                } else {
                    "Копия вне дома не проверялась с ${time(checked)}."
                },
            ),
        )
    }
    if (ctx.offsiteSetup?.hasPassphrase() == true) {
        val at = copy.passphraseAt()
        if (at == null || now - at > OffsiteSetup.REMIND_MILLIS) {
            append(banner("info", "Проверьте, что помните пароль копии${at?.let { ": последний раз ${time(it)}" }.orEmpty()}."))
        }
    }
    copy.progress?.let { p ->
        val share = if (p.totalBytes > 0) (p.bytes * 10 / p.totalBytes).toInt().coerceIn(0, 10) * 10 else 0
        append("<div class=\"progress w$share\"><span></span></div>")
        append("<p class=\"muted\">Шифруются медиафайлы: ${p.files} из ${p.totalFiles}, ")
        append("${"%.1f".format(p.bytes / GB)} из ${"%.1f".format(p.totalBytes / GB)} ГБ…</p>")
    }
    append("<dl class=\"kv\"><dt>Папка</dt><dd><code>${esc(root.toString())}</code></dd>")
    append(
        "<dt>Открывается</dt><dd>${if (ctx.offsiteSetup?.hasPassphrase() == true) "кодом и паролем (key.age в папке)" else "кодом восстановления"}</dd>",
    )
    append("<dt>Копия</dt><dd>")
    append(
        when {
            passes?.lastAt != null -> "полная ${time(passes.lastAt)}"
            passes?.dbAt != null -> "снимки ${time(passes.dbAt)}, медиафайлы ещё нет"
            else -> "ещё не делалась"
        },
    )
    append("</dd><dt>Проверена</dt><dd>${checked?.let { "${time(it)}: всё открылось ключом и совпало с домом" } ?: "ещё нет"}</dd></dl>")
}

private fun banner(kind: String, text: String) =
    "<div class=\"banner $kind\">${icon(if (kind == "err") "error" else "key")}<div>${esc(text)}</div></div>"

private fun beginForm(label: String, open: Boolean): String = buildString {
    append("<details class=\"edit\"${if (open) " open" else ""}><summary class=\"btn text\">${icon("edit")}$label</summary>")
    append("<form method=\"post\" action=\"/admin/offsite/begin#backups\" class=\"stack\"><div class=\"path-row\">")
    append(field("offsite-path", "Папка, которую увозит облако или диск", "", "C:\\Users\\Имя\\Google Drive\\Benbow"))
    append("<button class=\"btn outlined\" type=\"button\" data-pick-folder=\"offsite-path\">${icon("folder_open")}Выбрать…</button></div>")
    append("<fieldset class=\"choice\"><legend>Чем открыть копию, если дома ничего не останется</legend>")
    append("<label><input type=\"radio\" name=\"way\" value=\"code\" checked><span><b>Кодом восстановления</b> (лучше всего): ")
    append("сервер покажет его один раз, запишите или распечатайте.</span></label>")
    append("<label><input type=\"radio\" name=\"way\" value=\"passphrase\"><span><b>Своим паролем</b>: в копии будет ключ под паролем. ")
    append("Слабый пароль подберут, если копия попадёт в чужие руки.</span></label></fieldset>")
    append("<p class=\"hint\">Пустая или новая папка. Копия идёт после домашних и ничего не замедляет; каждый файл в ней ")
    append("проверяется расшифровкой.</p>")
    append("<div class=\"row end\"><button class=\"btn\">Дальше</button></div></form></details>")
}

/** The second step: the code to save and its end typed back, or the passphrase twice. */
private fun stepForm(step: OffsiteSetup.Pending): String = buildString {
    append("<p>Папка: <code>${esc(step.path.toString())}</code></p>")
    append("<form method=\"post\" action=\"/admin/offsite/confirm#backups\" class=\"stack\" data-busy=\"Включается…\">")
    append("<input type=\"hidden\" name=\"step\" value=\"${esc(step.id)}\">")
    if (step.passphrase) {
        append("<input type=\"hidden\" name=\"way\" value=\"passphrase\">")
        append("<p class=\"lead\">Придумайте пароль не короче ${OffsiteSetup.MIN_LENGTH} знаков. Им откроется ключ копии, ")
        append("если дома не останется ничего. Латиница удобнее, если открывать копию программой age на чужом компьютере.</p>")
        append(password("pass1", "Пароль"))
        append(password("pass2", "Ещё раз"))
    } else {
        append("<input type=\"hidden\" name=\"way\" value=\"code\">")
        append(
            "<div class=\"banner info\">${icon("key")}<div><b>Запишите этот код.</b> Без него копию не открыть, если дома не " +
                "останется ничего: ни этого компьютера, ни второго диска.${if (step.fresh) "" else " Это тот же код, что вы сохранили раньше."}</div></div>",
        )
        append("<pre class=\"code-key\">${esc(step.key.grouped())}</pre>")
        append(
            "<div class=\"row\"><a class=\"btn tonal\" href=\"/admin/offsite/code.txt?step=${esc(step.id)}\" download>${icon("download")}",
        )
        append("<span>Скачать файл</span></a><a class=\"btn text\" href=\"/admin/offsite/code?step=${esc(step.id)}\" target=\"_blank\">")
        append("${icon("file")}<span>Распечатать</span></a></div>")
        append(field("tail", "Последние ${OffsiteSetup.TAIL} знаков кода, с бумаги или из файла", "", ""))
    }
    append("<div class=\"row end\"><a class=\"btn text\" href=\"/#backups\">Отмена</a>")
    append("<button class=\"btn\">${icon("backup")}<span>Включить копию</span></button></div></form>")
}

private fun passphraseForm(label: String, hint: String): String = buildString {
    append("<details class=\"edit\"><summary class=\"btn text\">${icon("key")}$label</summary>")
    append("<form method=\"post\" action=\"/admin/offsite/passphrase#backups\" class=\"stack\" data-busy=\"Записывается…\">")
    append(password("pass1", "Пароль, не короче ${OffsiteSetup.MIN_LENGTH} знаков"))
    append(password("pass2", "Ещё раз"))
    append("<p class=\"hint\">${esc(hint)}</p><div class=\"row end\"><button class=\"btn\">Записать</button></div></form></details>")
}

private fun passphraseCheck(): String = "<details class=\"edit\"><summary class=\"btn text\">${icon("check")}Проверить пароль</summary>" +
    "<form method=\"post\" action=\"/admin/offsite/check-passphrase#backups\" class=\"stack\" data-busy=\"Проверяется…\">" +
    password("pass", "Пароль копии") + "<div class=\"row end\"><button class=\"btn\">Проверить</button></div></form></details>"

private fun password(name: String, label: String) =
    "<div class=\"field\"><label for=\"$name\">${esc(label)}</label><input id=\"$name\" name=\"$name\" type=\"password\" " +
        "autocomplete=\"new-password\"></div>"

private const val GB = 1_073_741_824.0
private const val STALE = 48L * 3_600_000
