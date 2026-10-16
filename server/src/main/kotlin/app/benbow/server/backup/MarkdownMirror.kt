package app.benbow.server.backup

import app.benbow.core.content.EntryExport
import app.benbow.core.sync.EntryState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Human-readable disaster-recovery copy (data-safety.md#backups): one `.md` per entry with YAML
 * front matter, plus a `.json` sidecar holding the canonical document and metadata losslessly
 * (the format is [EntryExport], shared with the app's archive export).
 * [media] finds the placed file of a SHA-256 (null while it hasn't been uploaded).
 */
class MarkdownMirror(private val root: Path, private val media: (String) -> Path? = { null }) {
    /** [tagNames]: the tag registry's names by id (`TagNames.of`), so the note lists its tags as the app shows them. */
    fun write(entry: EntryState, body: String?, tagNames: Map<String, String> = emptyMap()) {
        if (entry.kind == "system") return // registries (e.g. tag categories) are app data, not notes
        val dir = root.resolve(EntryExport.folder(entry)).createDirectories()
        val base = EntryExport.baseName(entry)
        val markdown = EntryExport.markdown(entry, body, missing = "not uploaded yet", tagNames = tagNames) { a ->
            media(a.meta.sha256)?.let { file ->
                runCatching { dir.relativize(file).joinToString("/") }.getOrElse { file.toUri().toString() }
            }
        }
        atomicWrite(dir.resolve("$base.md"), markdown)
        atomicWrite(dir.resolve("$base.json"), EntryExport.sidecar(entry, body, tagNames))
    }

    private fun atomicWrite(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        tmp.writeText(text)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
