package app.benbow.server.backup

import app.benbow.core.content.EntryExport
import app.benbow.core.content.TagBook
import app.benbow.core.content.TagNames
import app.benbow.core.sync.EntryState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Human-readable disaster-recovery copy (data-safety.md#backups): one `.md` per entry with YAML
 * front matter, plus a `.json` sidecar holding the canonical document and metadata losslessly
 * (the format is [EntryExport], shared with the app's archive export).
 * [media] finds the placed file of a SHA-256 (null while it hasn't been uploaded).
 */
class MarkdownMirror(private val root: Path, private val media: (String) -> Path? = { null }) {
    /** [tags]: the tag registry (`TagNames.of`), so the note lists its tags as the app shows them. */
    fun write(entry: EntryState, body: String?, tags: TagNames = TagNames()) {
        if (entry.kind == "system") return // registries (e.g. tag categories) are app data, not notes
        val folder = EntryExport.folder(entry)
        val dir = root.resolve(folder).createDirectories()
        val base = EntryExport.baseName(entry)
        val markdown = EntryExport.markdown(entry, body, missing = "not uploaded yet", tags = tags) { a ->
            media(a.meta.sha256)?.let { file ->
                runCatching { dir.relativize(file).joinToString("/") }.getOrElse { file.toUri().toString() }
            }
        }
        atomicWrite(dir.resolve("$base.md"), markdown)
        atomicWrite(dir.resolve("$base.json"), EntryExport.sidecar(entry, body, tags))
        // A note moved between the journal and the board (or the board's archive): its copy there goes, once this one
        // is written, so the mirror holds every entry exactly once.
        EntryExport.folders(entry).filter { it != folder }.forEach { other ->
            root.resolve(other).resolve("$base.md").deleteIfExists()
            root.resolve(other).resolve("$base.json").deleteIfExists()
        }
    }

    /** `tags.json` and `tags.md` beside the notes (7.9 T5): every tag and category, to read without the app. */
    fun writeTags(registry: Map<String, String?>, written: Map<String, String>) {
        root.createDirectories()
        val book = TagBook.read(registry, written)
        atomicWrite(root.resolve(TagBook.JSON_FILE), TagBook.json(book))
        atomicWrite(root.resolve(TagBook.MARKDOWN_FILE), TagBook.markdown(book))
    }

    private fun atomicWrite(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        tmp.writeText(text)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
