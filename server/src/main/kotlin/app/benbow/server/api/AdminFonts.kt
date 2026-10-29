package app.benbow.server.api

import app.benbow.core.api.FontInfo
import app.benbow.server.fonts.Fonts
import app.benbow.server.fonts.OwnFonts
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * «Шрифты» on the admin page (ADR-052, 7.20 L5): the owner's own fonts (TTF, OTF or WOFF, checked before they are kept)
 * and the pack the package carries, each shown in its own letters. The page loads them from this server only: its CSP
 * allows fonts from 'self', and the faces come from a stylesheet made here, since the page takes no inline styles.
 */

internal fun Route.fontRoutes(ctx: ServerContext) {
    post("/admin/fonts") {
        if (!adminAllowed(call, ctx)) return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val fonts = ctx.fonts ?: return@post call.respondText("Шрифтов нет", status = HttpStatusCode.ServiceUnavailable)
        val refused = ArrayList<String>()
        var added = 0
        var replaced = false
        call.receiveMultipart(formFieldLimit = FONT_LIMIT).forEachPart { part ->
            val name = (part as? PartData.FileItem)?.originalFileName?.substringAfterLast('\\')?.substringAfterLast('/')
            if (part is PartData.FileItem && !name.isNullOrBlank()) {
                val bytes = part.provider().toByteArray()
                when (val result = withContext(Dispatchers.IO) { fonts.own.add(name, bytes, System.currentTimeMillis()) }) {
                    is OwnFonts.Added.Done -> {
                        added++
                        replaced = replaced || result.replaced
                    }

                    is OwnFonts.Added.Refused -> refused += "$name: ${result.reason}"
                }
            }
            part.dispose()
        }
        when {
            refused.isNotEmpty() -> call.respondAdmin(
                adminPage(ctx, admin = true, Flash("Не добавлено — " + refused.joinToString("; "), error = true)),
                HttpStatusCode.BadRequest,
            )

            added == 0 -> call.respondAdmin(
                adminPage(ctx, admin = true, Flash("Выберите файл шрифта", error = true)),
                HttpStatusCode.BadRequest,
            )

            else -> call.respondRedirect("/?done=${if (replaced) "font-replaced" else "font"}#fonts")
        }
    }

    post("/admin/fonts/remove") {
        val token = call.receiveParameters()["token"].orEmpty()
        admin(ctx, "font-removed#fonts") { withContext(Dispatchers.IO) { ctx.fonts?.own?.remove(token) } }
    }

    post("/admin/fonts/scale") {
        val form = call.receiveParameters()
        val token = form["token"].orEmpty()
        val scale = form["scale"]?.toIntOrNull()?.takeIf { it in SCALES } ?: 100
        admin(ctx, "font-size#fonts") { withContext(Dispatchers.IO) { ctx.fonts?.own?.setScale(token, scale / 100f) } }
    }

    // The faces for the page's samples, and their files: from this PC only, like the page itself.
    get("/admin/fonts/faces.css") {
        if (!ctx.isLoopback(call)) return@get call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val css = withContext(Dispatchers.IO) { ctx.fonts?.catalog()?.fonts.orEmpty() }.joinToString("\n") { font ->
            font.files.joinToString("\n") { file ->
                "@font-face { font-family: \"${faceName(font)}\"; src: url(\"/admin/fonts/files/${file.sha256}\"); " +
                    "font-weight: ${file.weight}; font-display: swap; }"
            } +
                "\n.${faceName(
                    font,
                )} { font-family: \"${faceName(font)}\", system-ui, sans-serif; font-size: ${(font.scale * 20).toInt()}px; }"
        }
        call.response.header("Cache-Control", "no-cache")
        call.respondText(css, ContentType.Text.CSS)
    }

    get("/admin/fonts/files/{sha256}") {
        if (!ctx.isLoopback(call)) return@get call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val file = withContext(Dispatchers.IO) { ctx.fonts?.file(call.parameters["sha256"]!!) }
            ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
        call.respond(LocalPathContent(file, ContentType("font", if (file.fileName.toString().endsWith(".otf")) "otf" else "ttf")))
    }
}

/** The section: the owner's fonts with the form that adds one, then the pack by its groups. */
internal fun fontsSection(fonts: Fonts): String = buildString {
    append("<section id=\"fonts\" class=\"page\">")
    append(
        pageTitle(
            "Шрифты",
            "font",
            "Шрифты, которые записки берут с этого сервера: ваши и набор Benbow. Устройство скачивает шрифт, когда он " +
                "впервые нужен, и хранит у себя.",
        ),
    )
    append("<link rel=\"stylesheet\" href=\"/admin/fonts/faces.css\">")
    append("<div class=\"card\"><h3>${icon("font")}Свои шрифты</h3>")
    append("<p class=\"lead\">TTF, OTF или WOFF, до 12 МБ. Прямое и жирное начертание одного шрифта становятся одним шрифтом.</p>")
    append("<form method=\"post\" action=\"/admin/fonts\" enctype=\"multipart/form-data\" data-busy=\"Проверяю…\" class=\"row\">")
    append("<input type=\"file\" name=\"font\" accept=\".ttf,.otf,.woff\" multiple required>")
    append("<button class=\"btn\">${icon("up")}Добавить</button></form>")
    val own = fonts.own.all()
    if (own.isEmpty()) append("<p class=\"muted\">Своих шрифтов пока нет.</p>")
    append("<div class=\"list\">")
    for (font in own) {
        val info = font.info()
        append("<div class=\"item font\"><div class=\"body\">")
        append("<div class=\"font-sample ${faceName(info)}\">${esc(SAMPLE)}</div>")
        append("<div class=\"headline\">${esc(font.name)}</div><div class=\"support\">")
        append(font.files.joinToString(", ") { (if (it.weight >= 600) "жирное" else "прямое") + " — " + esc(it.original) })
        append("</div>")
        if (!font.cyrillic) append(chip("err", "error", "нет русских букв: русские слова будут обычным шрифтом"))
        append("</div><form class=\"row scale\" method=\"post\" action=\"/admin/fonts/scale\">")
        append("<input type=\"hidden\" name=\"token\" value=\"${esc(font.token)}\"><label>Размер <select name=\"scale\">")
        val now = (font.scale * 100).toInt()
        for (s in SCALES) append("<option value=\"$s\"${if (s == nearest(now)) " selected" else ""}>$s%</option>")
        append("</select></label><button class=\"btn text\">Сохранить</button></form>")
        append("<form class=\"inline\" method=\"post\" action=\"/admin/fonts/remove\" data-confirm=\"")
        append(esc("Убрать «${font.name}»? Записки с ним покажутся обычным шрифтом; файл останется в папке fonts\\removed."))
        append("\"><input type=\"hidden\" name=\"token\" value=\"${esc(font.token)}\">")
        append("<button class=\"btn outlined danger\">${icon("delete")}Убрать</button></form></div>")
    }
    append("</div></div>")
    val pack = fonts.pack.fonts.map { it.info }
    append("<div class=\"card\"><h3>${icon("font")}Набор Benbow</h3>")
    if (pack.isEmpty()) {
        append("<p class=\"muted\">В этом пакете сервера нет набора шрифтов.</p>")
    } else {
        append("<p class=\"lead\">${pack.size} шрифтов с кириллицей, которые вы выбрали на странице образцов (лицензия OFL). ")
        append("Ещё восемь встроены в само приложение.</p>")
        for ((group, title) in GROUPS) {
            val inGroup = pack.filter { it.group == group }.takeIf { it.isNotEmpty() } ?: continue
            append("<h4>${esc(title)}</h4><div class=\"list\">")
            inGroup.forEach { info ->
                append("<div class=\"item font\"><div class=\"body\"><div class=\"font-sample ${faceName(info)}\">${esc(SAMPLE)}</div>")
                append("<div class=\"support\">${esc(info.name)}</div></div></div>")
            }
            append("</div>")
        }
    }
    append("</div></section>")
}

/** A CSS name for [font]'s face: the token as letters and digits only, made unique by its hash. */
private fun faceName(font: FontInfo) =
    "bf-" + font.token.filter { it in 'a'..'z' || it in '0'..'9' } + "-" + font.token.hashCode().toUInt().toString(16)

private fun nearest(percent: Int) = SCALES.minBy { kotlin.math.abs(it - percent) }

private const val SAMPLE = "Утро у моря. Съешь же ещё этих мягких французских булок, да выпей чаю."

private val SCALES = (80..160 step 5).toList()

private const val FONT_LIMIT = 13L * 1024 * 1024

private val GROUPS = listOf(
    "diary" to "Рукописные",
    "serif" to "С засечками",
    "sans" to "Без засечек",
    "accent" to "Акцентные",
    "mono" to "Моноширинные",
)
