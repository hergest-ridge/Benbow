package app.benbow.client

import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.db.BenbowDb
import app.benbow.core.content.EntryExport
import app.benbow.core.content.TagNames
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.EntryState
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.LwwValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Where an archive's files go: a ZIP stream in the apps, a ZIP built from blobs in the browser (ADR-029). */
interface ArchiveWriter {
    /** A text file (compressed where the writer can). */
    suspend fun text(path: String, text: String)

    /** A media file as it is: photos and videos are compressed already. */
    suspend fun file(path: String, file: LocalFile)
}

/**
 * The whole journal as one ZIP that needs no app (data-safety.md#backups): `entries/YYYY/MM/<day>_<id>.md` + `.json` exactly
 * like the server's mirror ([EntryExport]), the files under `media/YYYY/MM/`, the calendars under `calendars/` (ADR-028),
 * and a README. Files not on this device are fetched from the server; any that can't be are named in the README and in
 * the notes, never dropped silently.
 */
class ArchiveExport(
    private val db: BenbowDb,
    private val media: Media,
    private val calendar: CalendarRepository? = null,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun write(zip: ArchiveWriter, withMedia: Boolean, today: String, onProgress: (ExportProgress) -> Unit = {}): ExportReport =
        withContext(IoDispatcher) {
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
            // Notes list their tags by the names they show as (ADR-032).
            val tagNames = db.benbowQueries.selectEntry(TAG_REGISTRY_ID).executeAsOneOrNull()?.let {
                TagNames.of(decodeFields(it.fields))
            }.orEmpty()
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
            run {
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
                        zip.file(path, file)
                        placed[attachment.meta.sha256] = path
                    }
                    onProgress(ExportProgress(++done, total))
                }
                val why = if (withMedia) {
                    "is not in this export (not on the device, server unreachable)"
                } else {
                    "was left out (exported without files)"
                }
                for ((entry, body) in entries) {
                    val dir = "entries/${EntryExport.folder(entry)}/"
                    val markdown = EntryExport.markdown(entry, body, missing = why, tagNames = tagNames) { a ->
                        placed[a.meta.sha256]?.let { "../../../$it" }
                    }
                    zip.text(dir + EntryExport.baseName(entry) + ".md", markdown)
                    zip.text(dir + EntryExport.baseName(entry) + ".json", EntryExport.sidecar(entry, body, tagNames))
                    onProgress(ExportProgress(++done, total))
                }
                val calendars = calendar?.exportFiles(now()).orEmpty()
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
