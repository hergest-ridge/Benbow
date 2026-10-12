package app.benbow.server.backup

import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.MarkdownExporter
import app.benbow.core.content.TextBlock
import app.benbow.core.content.toPlainText
import app.benbow.core.sync.EntryState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Human-readable disaster-recovery copy (data-safety.md#backups): one `.md` per entry with YAML
 * front matter, plus a `.json` sidecar holding the canonical document and metadata losslessly.
 * File names depend only on day and id, so a changed title never orphans an old file.
 */
class MarkdownMirror(private val root: Path) {
    private val exporter = MarkdownExporter()
    private val json = Json { prettyPrint = true }

    fun write(entry: EntryState, body: String?) {
        val (year, month) = entry.day.split("-").let { it.getOrElse(0) { "0000" } to it.getOrElse(1) { "00" } }
        val dir = root.resolve(year).resolve(month).createDirectories()
        val base = "${entry.day}_${entry.id.takeLast(12).replace(Regex("[^A-Za-z0-9-]"), "_")}"
        val document = body?.let {
            try {
                DocumentCodec.decode(it)
            } catch (_: DocumentFormatException) {
                null
            }
        }
        val title = (document?.blocks?.firstOrNull() as? TextBlock)?.let { block -> block.children.joinToString("") { it.text } }.orEmpty()
        val frontMatter = buildString {
            appendLine("---")
            appendLine("id: ${yaml(entry.id)}")
            appendLine("kind: ${yaml(entry.kind)}")
            appendLine("day: ${yaml(entry.day)}")
            appendLine("title: ${yaml(title.take(120))}")
            entry.head?.let { appendLine("revision: ${yaml(it)}") }
            if (entry.conflictHeads.isNotEmpty()) appendLine("conflict: [${entry.conflictHeads.joinToString(", ") { yaml(it) }}]")
            entry.fields.toSortedMap().forEach { (k, v) -> v.value?.let { appendLine("$k: ${yaml(it)}") } }
            appendLine("---")
        }
        val markdown = when {
            body == null -> ""
            document == null -> "<!-- body could not be decoded; see the .json sidecar -->"
            else -> exporter.export(document)
        }
        atomicWrite(dir.resolve("$base.md"), frontMatter + "\n" + markdown + "\n")
        val sidecar: JsonObject = buildJsonObject {
            put("entry", Json.encodeToJsonElement(EntryState.serializer(), entry))
            put("body", body?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
            put("plain_text", document?.toPlainText() ?: "")
        }
        atomicWrite(dir.resolve("$base.json"), json.encodeToString(JsonObject.serializer(), sidecar) + "\n")
    }

    private fun yaml(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun atomicWrite(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        tmp.writeText(text)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
