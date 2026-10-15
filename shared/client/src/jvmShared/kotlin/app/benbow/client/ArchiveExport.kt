package app.benbow.client

import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.db.BenbowDb
import app.benbow.core.content.EntryExport
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.EntryState
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.LwwValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The whole journal as one ZIP that needs no app (data-safety.md#backups): `entries/YYYY/MM/<day>_<id>.md` + `.json` exactly
 * like the server's mirror ([EntryExport]), the files under `media/YYYY/MM/`, the calendars under `calendars/` (ADR-028),
 * and a README. Files not on this device are fetched from the server; any that can't be are named in the README and in
 * the notes, never dropped silently.
 */
class ArchiveExport(private val db: BenbowDb, private val media: Media, private val calendar: CalendarRepository? = null) {
    suspend fun write(out: OutputStream, withMedia: Boolean, today: String, onProgress: (ExportProgress) -> Unit = {}): ExportReport =
        withContext(Dispatchers.IO) {
            val entries = db.benbowQueries.exportEntries().executeAsList().map { row ->
                val values = decodeFields(row.fields)
                val clocks = decodeClocks(row.field_clocks)
                EntryState(
                    id = row.id,
                    kind = row.kind,
                    day = row.day,
                    createdAt = row.created_at,
                    head = row.head_rev,
                    conflictHeads = ClientJson.decodeFromString(row.conflict_heads),
                    fields = values.mapValues { (field, value) -> LwwValue(value, clocks[field] ?: Hlc.ZERO) },
                    attachments = decodeAttachments(row.attachments),
                ) to row.body
            }
            val wanted = if (withMedia) {
                entries.flatMap { (entry, _) ->
                    entry.attachments.values.filter { it.present }.map { entry to it }
                }.distinctBy { it.second.meta.sha256 }
            } else {
                emptyList()
            }
            val total = wanted.size + entries.size
            var done = 0
            val placed = HashMap<String, String>() // sha256 -> path inside the archive
            val missing = mutableListOf<AttachmentState>()
            ZipOutputStream(out.buffered()).use { zip ->
                val names = HashSet<String>()
                for ((entry, attachment) in wanted) {
                    val file = media.localFile(attachment.meta.sha256) ?: try {
                        media.file(attachment.meta.sha256)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (file == null) {
                        missing += attachment
                    } else {
                        val path = "media/${EntryExport.folder(entry)}/" + uniqueName(attachment, names)
                        zip.setLevel(Deflater.NO_COMPRESSION) // photos and videos are compressed already
                        zip.putNextEntry(ZipEntry(path))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        placed[attachment.meta.sha256] = path
                    }
                    onProgress(ExportProgress(++done, total))
                }
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                val why = if (withMedia) {
                    "is not in this export (not on the device, server unreachable)"
                } else {
                    "was left out (exported without files)"
                }
                for ((entry, body) in entries) {
                    val dir = "entries/${EntryExport.folder(entry)}/"
                    val markdown = EntryExport.markdown(entry, body, missing = why) { a -> placed[a.meta.sha256]?.let { "../../../$it" } }
                    zip.text(dir + EntryExport.baseName(entry) + ".md", markdown)
                    zip.text(dir + EntryExport.baseName(entry) + ".json", EntryExport.sidecar(entry, body))
                    onProgress(ExportProgress(++done, total))
                }
                val calendars = calendar?.exportFiles(System.currentTimeMillis()).orEmpty()
                calendars.forEach { zip.text("calendars/" + it.name, it.text) }
                zip.text(
                    "README.md",
                    readme(
                        today,
                        entries.size,
                        placed.size,
                        withMedia,
                        missing,
                        calendars.count {
                            it.name.endsWith(".ics")
                        },
                    ),
                )
            }
            ExportReport(entries.size, placed.size, missing.size)
        }

    private fun ZipOutputStream.text(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.encodeToByteArray())
        closeEntry()
    }

    /** The original file name where there is one, made safe and unique; otherwise kind and the start of the hash. */
    private fun uniqueName(a: AttachmentState, taken: MutableSet<String>): String {
        val ext = a.meta.mime.substringAfter('/', "bin").substringBefore(';').substringBefore('+').takeIf { it.length in 1..5 } ?: "bin"
        val name = a.meta.name?.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${a.meta.kind}-${a.meta.sha256.take(8)}.$ext"
        if (taken.add(name.lowercase())) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return (name.substring(0, dot) + "-" + a.meta.sha256.take(8) + name.substring(dot)).also { taken.add(it.lowercase()) }
    }

    private fun readme(today: String, entries: Int, files: Int, withMedia: Boolean, missing: List<AttachmentState>, calendars: Int) =
        buildString {
            appendLine("# Benbow export, $today")
            appendLine()
            appendLine("Every entry is a Markdown file under `entries/YEAR/MONTH/` with its metadata in the front matter, plus a `.json`")
            appendLine("file next to it with the exact text (Benbow Document, docs/content-format.md) and metadata. Pictures and files")
            appendLine("are under `media/` and linked from the notes. Nothing here needs the app to be read.")
            appendLine()
            appendLine("- Entries: $entries")
            appendLine("- Files: $files" + if (withMedia) "" else " (exported without files)")
            if (calendars > 0) {
                appendLine(
                    "- Calendars: $calendars, one `.ics` each under `calendars/` (any calendar app opens them; times are local, as written);",
                )
                appendLine("  `calendars/calendar.json` has every calendar and event as stored, deleted ones included")
            }
            if (missing.isNotEmpty()) {
                appendLine()
                appendLine("## Not included (${missing.size})")
                appendLine("These were neither on the device nor reachable on the server at export time; the server still keeps them.")
                missing.forEach { appendLine("- ${it.meta.name ?: it.meta.kind} (${it.meta.sha256})") }
            }
        }
}
