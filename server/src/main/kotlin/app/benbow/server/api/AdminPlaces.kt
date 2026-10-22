package app.benbow.server.api

import app.benbow.server.setup.NetworkAdvice
import app.benbow.server.setup.PlaceAdvice
import app.benbow.server.setup.PlaceAdvice.State
import app.benbow.server.setup.Survey

/**
 * «Где хранить» (ADR-042, as built 7.19 I1), where a new server starts: on which disks the database, the media and the
 * second disk are, what would be better and why, the disks of this computer, and whether phones at home can reach it
 * (the network's profile, the firewall rule). A suggestion fills in the forms of «Копии»: the same checks run there
 * before anything is saved, copied or moved.
 */
internal fun placesSection(ctx: ServerContext): String = "<section id=\"places\" class=\"page\">" +
    pageTitle("Где хранить", "folder", "На каких дисках база, медиафайлы и копия, что лучше поменять и видят ли сервер телефоны.") +
    "<div data-live=\"places\">${placesInner(ctx)}</div></section>"

/** The live part: the survey comes in a few seconds after the server starts and is read again once a minute. */
internal fun placesInner(ctx: ServerContext): String = buildString {
    val survey = ctx.survey?.current()
    if (survey == null) {
        val why = when {
            ctx.survey == null -> "Сведения о дисках и сети есть только в Windows."
            ctx.survey.tried -> "Windows не рассказал о дисках и сети; подробности в журнале сервера."
            else -> "Смотрю диски и сеть…"
        }
        append("<div class=\"card\"><p class=\"muted\">$why</p></div>")
        return@buildString
    }
    placeAdvice(ctx, survey)?.let { advice ->
        val database = "Папку данных выбирают при установке. Перенести её можно только переустановкой: restore-from-mirror.cmd " +
            "с новой папкой (README пакета)."
        append(placeCard("База и снимки", "server", advice.database, hint = database))
        val moving = "Медиафайлы уже переносятся: ход в «Копиях».".takeIf { ctx.mediaMove?.moves()?.current != null }
        append(placeCard("Медиафайлы", "folder", advice.media, busy = moving, form = ::mediaForm))
        val copying = "Идёт копия на второй диск: ход в «Копиях».".takeIf { ctx.backupJobs?.progress != null }
        append(placeCard("Второй диск", "backup", advice.mirror, busy = copying, form = ::mirrorForm))
        append(offsiteHint(ctx))
        append(disksCard(advice))
    }
    append(networkCard(NetworkAdvice.advise(survey, ctx.lanAddresses().firstOrNull(), ctx.port)))
}

/** What needs fixing, for the overview's banner and the tab's dot: nothing until the survey is in. */
internal fun placesProblems(ctx: ServerContext): List<String> {
    val survey = ctx.survey?.current() ?: return emptyList()
    val places = placeAdvice(ctx, survey)
    val network = NetworkAdvice.advise(survey, ctx.lanAddresses().firstOrNull(), ctx.port)
    return listOfNotNull(
        "база".takeIf { places?.database?.state == State.Problem },
        "медиафайлы".takeIf { places?.media?.state == State.Problem },
        "второй диск".takeIf { places?.mirror?.state == State.Problem },
        "сеть".takeIf { network.network.state == State.Problem },
        "брандмауэр".takeIf { network.firewall.state == State.Problem },
    )
}

/** The overview's line while something in «Где хранить» needs fixing (a new server: no second disk yet). */
internal fun placesBanner(problems: List<String>): String? {
    if (problems.isEmpty()) return null
    return "<div class=\"banner err\">${icon("error")}<div><b>Стоит поправить: ${problems.joinToString(", ")}.</b> " +
        "Что и как, сказано в <a href=\"#places\">«Где хранить»</a>.</div></div>"
}

private fun placeAdvice(ctx: ServerContext, survey: Survey): PlaceAdvice.Advice? {
    val dataDir = ctx.dataDir ?: return null
    val blobs = ctx.blobs ?: return null
    return PlaceAdvice.advise(
        survey,
        dataDir = dataDir.toAbsolutePath().toString(),
        media = blobs.root.toAbsolutePath().toString(),
        mediaBytes = blobs.stats().second,
        mirror = ctx.backupStatus()?.mirrorPath,
    )
}

/** A place with its verdict; [form] acts on a better folder unless [busy] says why not now. */
private fun placeCard(
    title: String,
    iconName: String,
    place: PlaceAdvice.Place,
    hint: String? = null,
    busy: String? = null,
    form: ((String) -> String)? = null,
): String = buildString {
    append("<div class=\"card\"><div class=\"row\"><h3>${icon(iconName)}${esc(title)}</h3>${stateChip(place.state)}</div>")
    append("<dl class=\"kv\"><dt>Папка</dt><dd>${place.path?.let { "<code>${esc(it)}</code>" } ?: "не выбрана"}</dd>")
    place.volume?.let { append("<dt>Диск</dt><dd>${esc(volumeLine(it, place.disk))}</dd>") }
    append("</dl><p>${esc(place.says)}</p>")
    hint?.let { append("<p class=\"hint\">${esc(it)}</p>") }
    val better = place.better
    when {
        better == null || form == null -> Unit

        busy != null -> append("<p class=\"muted\">${esc(busy)}</p>")

        else -> {
            append("<div class=\"banner info\">${icon("folder_open")}<div>Лучше: <code>${esc(better.path)}</code>")
            if (better.volume != place.volume) append(" · ${esc(volumeLine(better.volume, better.disk, withLetter = false))}")
            append("</div></div>")
            append(form(better.path))
        }
    }
    append("</div>")
}

/** The copy outside the house (ADR-043): optional, so «можно лучше» at most, never «надо поправить». */
private fun offsiteHint(ctx: ServerContext): String = buildString {
    val copy = ctx.offsite ?: return@buildString
    val root = copy.root
    append(
        "<div class=\"card\"><div class=\"row\"><h3>${icon(
            "backup",
        )}Копия вне дома</h3>${stateChip(if (root != null) State.Good else State.Note)}</div>",
    )
    if (root != null) {
        append("<dl class=\"kv\"><dt>Папка</dt><dd><code>${esc(root.toString())}</code></dd></dl>")
        append("<p>Зашифрованная копия уходит из дома; когда она делалась и проверялась, видно в «Копиях».</p>")
    } else {
        append("<p>Её нет. Второй диск спасает от поломки диска, но не от пожара или кражи: тогда выручит копия в папке облака ")
        append("или на диске, который хранится не дома. Она зашифрована и не обязательна.</p>")
        append(
            "<div class=\"row end\"><a class=\"btn tonal\" href=\"#backups\">${icon("backup")}<span>Включить в «Копиях»</span></a></div>",
        )
    }
    append("</div>")
}

/** Through «Копии»: the folder is checked and what the move takes is shown; it starts only on «Начать перенос». */
private fun mediaForm(path: String) =
    "<form method=\"post\" action=\"/admin/media/check#backups\" class=\"row end\" data-busy=\"Проверяю…\">" +
        "<input type=\"hidden\" name=\"media-path\" value=\"${esc(path)}\">" +
        "<button class=\"btn tonal\">${icon("folder_open")}<span>Проверить перенос</span></button></form>"

/** Like «Другая папка» in «Копии»: checked, saved, then a first copy in the background; the old folder stays. */
private fun mirrorForm(path: String): String {
    val question = "Сделать вторым диском $path? Сервер проверит папку и начнёт первую копию; прежняя папка, если была, " +
        "останется как есть."
    return "<form method=\"post\" action=\"/admin/mirror#backups\" class=\"row end\" data-busy=\"Проверяю…\" " +
        "data-confirm=\"${esc(question)}\"><input type=\"hidden\" name=\"mirror-path\" value=\"${esc(path)}\">" +
        "<button class=\"btn tonal\">${icon("backup")}<span>Копировать туда</span></button></form>"
}

private fun disksCard(advice: PlaceAdvice.Advice): String = buildString {
    append("<div class=\"card\"><h3>${icon("disk")}Диски этого компьютера</h3><div class=\"list\">")
    for (row in advice.disks) {
        val v = row.volume
        append("<div class=\"item\"><div class=\"avatar\">${icon("disk")}</div><div class=\"body\">")
        append("<div class=\"headline\">${esc(v.letter)}:${v.label?.takeIf { it.isNotBlank() }?.let { " «${esc(it)}»" }.orEmpty()}</div>")
        append("<div class=\"support\">${esc(volumeLine(v, row.disk, withLetter = false))}</div></div>")
        if (row.system) append(chip("", null, "системный"))
        row.holds.forEach { append(chip("ok", null, it)) }
        row.disk?.health?.takeIf { it.isNotEmpty() && !it.equals("Healthy", true) }?.let { append(chip("err", "error", it)) }
        append("</div>")
    }
    append("</div></div>")
}

private fun networkCard(advice: NetworkAdvice.Advice): String = buildString {
    append("<div class=\"card\"><h3>${icon("wifi")}Домашняя сеть</h3>")
    listOf("Сеть" to advice.network, "Брандмауэр" to advice.firewall).forEach { (title, verdict) ->
        append("<div class=\"row\"><b>$title</b>${stateChip(verdict.state)}</div><p>${esc(verdict.says)}</p>")
        verdict.command?.let {
            append("<div class=\"row command\"><pre>${esc(it)}</pre>")
            append(
                "<button class=\"icon-btn\" type=\"button\" title=\"Скопировать\" data-copy=\"${esc(it)}\">${icon("copy")}</button></div>",
            )
        }
    }
    append("</div>")
}

private fun stateChip(state: State) = when (state) {
    State.Good -> chip("ok", "ok", "хорошо")
    State.Note -> chip("busy", null, "можно лучше")
    State.Problem -> chip("err", "error", "надо поправить")
}

/** «D: · KINGSTON SNV3S2000G · SSD, NVMe · свободно 169 из 1863 ГБ». */
private fun volumeLine(v: Survey.Volume, disk: Survey.PhysicalDisk?, withLetter: Boolean = true): String = listOfNotNull(
    "${v.letter}:".takeIf { withLetter },
    disk?.name?.trim()?.takeIf { it.isNotEmpty() },
    listOfNotNull(disk?.media?.takeIf { it == "SSD" || it == "HDD" }, disk?.bus?.takeIf { it.isNotEmpty() })
        .joinToString(", ").takeIf { it.isNotEmpty() },
    v.fs?.takeIf { it.isNotEmpty() && it != "NTFS" },
    "свободно ${PlaceAdvice.gb(v.free)} из ${PlaceAdvice.gb(v.size)} ГБ",
).joinToString(" · ")
