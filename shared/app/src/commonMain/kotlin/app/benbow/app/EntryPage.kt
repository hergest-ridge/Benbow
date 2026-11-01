package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.benbow.app.resources.Res
import app.benbow.app.resources.comments
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.not_on_device
import app.benbow.app.resources.weekdays
import app.benbow.client.IoDispatcher
import app.benbow.client.LocalFile
import app.benbow.client.LookFields
import app.benbow.client.tagRegistry
import app.benbow.client.uri
import app.benbow.core.content.AttachmentBlock
import app.benbow.core.content.AttachmentRowBlock
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.HtmlExporter
import app.benbow.core.content.HtmlExporter.Companion.escape
import app.benbow.core.content.LinkCardBlock
import app.benbow.core.content.STYLE_NORMAL
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.benbow.core.content.attachmentRefs
import app.benbow.core.sync.AttachmentState
import app.benbow.ui.theme.BenbowPalette
import kotlinx.coroutines.CancellationException
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
internal suspend fun AppServices.entryPage(id: String): PrintPage? = withContext(IoDispatcher) {
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
    val looks = repo.tagRegistry().first()
    val head = buildString {
        append("<p class=\"meta\">").append(escape(moment)).append(moodEmoji(entry.mood)?.let { " · $it" }.orEmpty()).append("</p>\n")
        if (entry.tags.isNotEmpty()) {
            append("<p class=\"tags\">")
            entry.tags.forEach { tag ->
                val background = lightSwatch(looks.color(tag.id))?.let { " style=\"background:$it\"" }.orEmpty()
                append("<span").append(background).append(">").append(escape(tag.name)).append("</span>")
            }
            append("</p>\n")
        }
    }
    // Each attachment's file (fetched from the server when not here); pictures by URL, a video by its preview frame.
    val files = entry.attachments.associate { a -> a.meta.id to fileOf(a.meta.sha256) }
    val pictures = entry.attachments.associate { a ->
        a.meta.id to when (a.meta.kind) {
            "image" -> files[a.meta.id]
            "video" -> a.visual?.let { fileOf(it) }
            else -> null
        }?.uri()
    }
    // Link cards show their page's picture too (ADR-038).
    val cardPictures = document.blocks.filterIsInstance<LinkCardBlock>().mapNotNull {
        it.image
    }.distinct().associateWith { fileOf(it)?.uri() }
    val missing = getString(Res.string.not_on_device)
    val label = { a: AttachmentState -> a.caption ?: a.meta.name ?: a.meta.kind }
    val body = HtmlExporter(
        color = { BenbowPalette.print.text(it, dark = false)?.css() },
        highlight = { BenbowPalette.print.highlight(it, dark = false)?.css() },
        tint = { BenbowPalette.print.tint(it, dark = false)?.css() },
        attachmentUrl = { ref -> pictures[ref].orEmpty() },
        imageUrl = { cardPictures[it] },
    ).export(
        // A note's first line reads as its title on paper, as it does in the lists.
        document.copy(
            blocks = document.blocks.flatMap { block ->
                // A row with a sound or a file in it (ADR-061) prints as its attachments one under another.
                if (block is AttachmentRowBlock && block.refs.any { pictures[it] == null }) {
                    block.refs.mapIndexed { i, ref -> AttachmentBlock(block.key + "-" + i, ref) }
                } else {
                    listOf(block)
                }
            }.map { block ->
                // In the text, a sound or a file (or a picture that couldn't be fetched) is a line naming it.
                if (block !is AttachmentBlock || pictures[block.ref] != null) return@map block
                val a = entry.attachments.firstOrNull { it.meta.id == block.ref }
                val name = a?.let(label).orEmpty() + if (a != null && files[a.meta.id] == null) " — $missing" else ""
                TextBlock(block.key, children = listOf(Span("📎 $name")))
            }.mapIndexed { i, block ->
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
    val inline = document.attachmentRefs().toHashSet()
    val tail = buildString {
        for (a in entry.attachments.filter { it.meta.id !in inline }) {
            val label = escape(label(a))
            val file = files[a.meta.id]
            when {
                file == null -> append("<p class=\"file\">📎 $label — ").append(escape(missing)).append("</p>\n")

                a.meta.kind == "image" -> append("<figure><img src=\"").append(file.uri()).append("\" alt=\"$label\">")
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
    // The note's own font on paper too (ADR-052), carried in the page.
    val font = pageFont(entry.look[LookFields.FONT], fonts)
    val style = font?.let { HtmlExporter.noteFont(it.files, it.scale) }.orEmpty()
    val words = if (font != null) "<div class=\"note\">$body</div>" else body
    PrintPage(title.take(80), HtmlExporter.page(title, head, words, tail, style))
}

private fun Color.css(): String {
    val argb = toArgb()
    val (r, g, b) = Triple(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
    return if (alpha >= 1f) "rgb($r,$g,$b)" else "rgba($r,$g,$b,${(alpha * 100).toInt() / 100.0})"
}

/** Shows a page for printing: Android's print dialog (which also saves a PDF), the default browser on desktop. */
@Composable
internal expect fun rememberPrint(): (PrintPage) -> Unit

/** The file of [sha256], from this device or the server; null when neither has it now. */
private suspend fun AppServices.fileOf(sha256: String): LocalFile? = media.localFile(sha256) ?: try {
    media.file(sha256)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
