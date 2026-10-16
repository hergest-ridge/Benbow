package app.benbow.core.content

import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.EntryState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * One entry as export files (data-safety.md#backups): rich Markdown with YAML front matter, and a `.json` sidecar
 * holding the canonical document and metadata losslessly. The server's mirror and the app's archive both use it,
 * so the two read the same. Names depend only on day and id, so a changed title never orphans an old file.
 */
object EntryExport {
    private val json = Json { prettyPrint = true }

    /** "YYYY/MM" of the entry's day. */
    fun folder(entry: EntryState): String = entry.day.split("-").let { "${it.getOrElse(0) { "0000" }}/${it.getOrElse(1) { "00" }}" }

    /** "2026-10-13_<last 12 of the id>", without the extension. */
    fun baseName(entry: EntryState): String = "${entry.day}_${entry.id.takeLast(12).replace(Regex("[^A-Za-z0-9-]"), "_")}"

    /**
     * The `.md` file. [link] gives an attachment's path relative to the note, or null when the file isn't in this
     * export; [missing] then says why in an HTML comment ("not uploaded yet", "not on this device"). [tags] reads
     * the tag registry ([TagNames.of]): tags list by the names they show as, never by id.
     */
    fun markdown(
        entry: EntryState,
        body: String?,
        missing: String,
        tags: TagNames = TagNames(),
        link: (AttachmentState) -> String?,
    ): String {
        val document = decode(body)
        val title = (document?.blocks?.firstOrNull() as? TextBlock)?.let { block -> block.children.joinToString("") { it.text } }.orEmpty()
        val frontMatter = buildString {
            appendLine("---")
            appendLine("id: ${yaml(entry.id)}")
            appendLine("kind: ${yaml(entry.kind)}")
            appendLine("day: ${yaml(entry.day)}")
            appendLine("title: ${yaml(title.take(120))}")
            entry.head?.let { appendLine("revision: ${yaml(it)}") }
            if (entry.conflictHeads.isNotEmpty()) appendLine("conflict: [${entry.conflictHeads.joinToString(", ") { yaml(it) }}]")
            // Namespaced fields (ADR-021) read as lists; everything else as plain keys.
            val fields = entry.fields.mapNotNull { (k, v) -> v.value?.let { k to it } }.sortedBy { it.first }.toMap()
            val names = tags.on(fields).map { it.second }
            val reactions = fields.keys.filter { it.startsWith("reaction:") }.map { it.removePrefix("reaction:") }
            fields.filterKeys { !it.startsWith("tag:") && !it.startsWith("reaction:") && !it.startsWith(TRANSCRIPT) }
                .forEach { (k, v) -> appendLine("$k: ${yaml(v)}") }
            if (names.isNotEmpty()) appendLine("tags: [${names.joinToString(", ") { yaml(it) }}]")
            if (reactions.isNotEmpty()) appendLine("reactions: [${reactions.joinToString(", ") { yaml(it) }}]")
            appendLine("---")
        }
        val markdown = when {
            body == null -> ""

            document == null -> "<!-- body could not be decoded; see the .json sidecar -->"

            // A picture placed in the text links to its file right there; the list below names the rest.
            else -> MarkdownExporter(attachmentUrl = { ref ->
                entry.attachments[ref]?.let(link)?.let { "<$it>" } ?: "benbow:attachment/$ref"
            })
                .export(document)
        }
        val inline = document?.blocks.orEmpty().filterIsInstance<AttachmentBlock>().mapTo(HashSet()) { it.ref }
        return frontMatter + "\n" + markdown + "\n" + attachments(entry, missing, inline, link)
    }

    /** The `.json` sidecar: the entry state, its tags as they read (id and name, ADR-032), the canonical body and its plain text. */
    fun sidecar(entry: EntryState, body: String?, tags: TagNames = TagNames()): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("entry", Json.encodeToJsonElement(EntryState.serializer(), entry))
            val read = tags.on(entry.fields.mapValues { it.value.value })
            if (read.isNotEmpty()) {
                val list = read.map { (id, name) ->
                    buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("name", JsonPrimitive(name))
                    }
                }
                put("tags", JsonArray(list))
            }
            put("body", JsonPrimitive(body.orEmpty()))
            put("plain_text", JsonPrimitive(decode(body)?.toPlainText().orEmpty()))
        },
    ) + "\n"

    /**
     * Present attachments as links relative to the note, so the folder stays browsable without the app. A voice note's
     * transcript follows its link as a quote; one placed in the text is linked again here for its transcript.
     */
    private fun attachments(entry: EntryState, missing: String, inline: Set<String>, link: (AttachmentState) -> String?): String {
        val transcripts = entry.fields.mapNotNull { (k, v) ->
            v.value?.takeIf { k.startsWith(TRANSCRIPT) }?.let {
                k.removePrefix(TRANSCRIPT) to
                    it
            }
        }.toMap()
        val visible = entry.attachments.values.filter {
            it.present && (it.meta.id !in inline || it.meta.id in transcripts)
        }.sortedBy { it.position }
        if (visible.isEmpty()) return ""
        return visible.joinToString("\n", prefix = "\n", postfix = "\n") { a ->
            val label = (a.caption ?: a.meta.name ?: a.meta.kind).replace('[', '(').replace(']', ')')
            val path = link(a)
            val line = when {
                path == null -> "<!-- ${a.meta.kind} ${a.meta.sha256} $missing -->"
                a.meta.kind == "image" || a.meta.kind == "drawing" -> "![$label](<$path>)"
                else -> "[$label](<$path>)"
            }
            line + transcripts[a.meta.id]?.let { text -> "\n" + text.lines().joinToString("\n") { "> $it" } }.orEmpty()
        }
    }

    /** `transcript:<attachment id>`: what was said in a voice note (7.6). */
    private const val TRANSCRIPT = "transcript:"

    private fun decode(body: String?): Document? = body?.let {
        try {
            DocumentCodec.decode(it)
        } catch (_: DocumentFormatException) {
            null
        }
    }

    private fun yaml(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
