package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.benbow.app.resources.Res
import app.benbow.app.resources.comments
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.not_on_device
import app.benbow.app.resources.weekdays
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.HtmlExporter
import app.benbow.core.content.HtmlExporter.Companion.escape
import app.benbow.core.content.STYLE_NORMAL
import app.benbow.core.content.TextBlock
import app.benbow.ui.theme.BenbowPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray

/** A page to print or save as PDF: its title and full HTML. */
internal class PrintPage(val title: String, val html: String)

/**
 * One entry as a printable page: date, mood and tags on top, the text (HtmlExporter, light palette), then its pictures
 * and its comments. Pictures not on this device are fetched from the server; if that fails the page says so.
 */
internal suspend fun AppServices.entryPage(id: String): PrintPage? = withContext(Dispatchers.IO) {
    val entry = repo.entry(id).first() ?: return@withContext null
    val document = try {
        DocumentCodec.decode(entry.body)
    } catch (_: DocumentFormatException) {
        return@withContext null
    }
    val months = getStringArray(Res.array.months_genitive)
    val dayText = { day: String -> LocalDate.parse(day).let { "${it.day} ${months[it.month.number - 1]} ${it.year}" } }
    val weekday = getStringArray(Res.array.weekdays)[LocalDate.parse(entry.day).dayOfWeek.isoDayNumber - 1]
    val moment = "$weekday, ${dayText(entry.day)}, ${timeLabel(entry.createdAt)}"
    val looks = repo.tagLooks().first()
    val head = buildString {
        append("<p class=\"meta\">").append(escape(moment)).append(moodEmoji(entry.mood)?.let { " · $it" }.orEmpty()).append("</p>\n")
        if (entry.tags.isNotEmpty()) {
            append("<p class=\"tags\">")
            entry.tags.forEach { tag ->
                val background = lightSwatch(looks.color(tag))?.let { " style=\"background:$it\"" }.orEmpty()
                append("<span").append(background).append(">").append(escape(tag)).append("</span>")
            }
            append("</p>\n")
        }
    }
    val body = HtmlExporter(
        color = { BenbowPalette.text(it, dark = false)?.css() },
        highlight = { BenbowPalette.highlight(it, dark = false)?.css() },
        tint = { BenbowPalette.tint(it, dark = false)?.css() },
    ).export(
        // A note's first line reads as its title on paper, as it does in the lists.
        document.copy(
            blocks = document.blocks.mapIndexed { i, block ->
                if (i == 0 && block is TextBlock && block.style == STYLE_NORMAL &&
                    block.listItem == null
                ) {
                    block.copy(style = "h1")
                } else {
                    block
                }
            },
        ),
    )
    val missing = getString(Res.string.not_on_device)
    val tail = buildString {
        for (a in entry.attachments) {
            val label = escape(a.caption ?: a.meta.name ?: a.meta.kind)
            val file = media.localFile(a.meta.sha256) ?: try {
                media.file(a.meta.sha256)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            when {
                file == null -> append("<p class=\"file\">📎 $label — ").append(escape(missing)).append("</p>\n")

                a.meta.kind == "image" -> append("<figure><img src=\"").append(file.toPath().toUri()).append("\" alt=\"$label\">")
                    .append(a.caption?.let { "<figcaption>${escape(it)}</figcaption>" }.orEmpty()).append("</figure>\n")

                else -> append("<p class=\"file\">📎 $label</p>\n")
            }
        }
        val thread = repo.comments(id).first()
        if (thread.isNotEmpty()) {
            append("<h3>").append(escape(getString(Res.string.comments))).append("</h3>\n")
            thread.forEach { c ->
                append(
                    "<div class=\"comment\"><p class=\"meta\">",
                ).append(escape(dayText(c.summary.day) + ", " + timeLabel(c.summary.createdAt)))
                append("</p><p>").append(escape(c.text).replace("\n", "<br>")).append("</p></div>\n")
            }
        }
    }
    val title = document.blocks.firstNotNullOfOrNull { (it as? TextBlock)?.children?.joinToString("") { s -> s.text } }
        ?.takeIf { it.isNotBlank() } ?: moment
    PrintPage(title.take(80), HtmlExporter.page(title, head, body, tail))
}

private fun Color.css(): String {
    val argb = toArgb()
    val (r, g, b) = Triple(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
    return if (alpha >= 1f) "rgb($r,$g,$b)" else "rgba($r,$g,$b,${(alpha * 100).toInt() / 100.0})"
}

/** Shows a page for printing: Android's print dialog (which also saves a PDF), the default browser on desktop. */
@Composable
internal expect fun rememberPrint(): (PrintPage) -> Unit
