package app.benbow.server.fonts

import app.benbow.core.api.FontCatalog
import app.benbow.core.api.FontFileInfo
import app.benbow.core.api.FontInfo
import app.benbow.server.storage.BlobStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * The fonts this server offers notes beyond the app's own eight (ADR-052): the [pack] its package carries and the
 * owner's [own]. Devices read the catalog and fetch a font's files by SHA-256 the first time they draw it.
 */
class Fonts(val pack: FontPack, val own: OwnFonts) {
    fun catalog(): FontCatalog = FontCatalog(pack.fonts.map { it.info } + own.all().map { it.info() })

    /** A file of a font in the catalog, by its SHA-256; null for anything else. */
    fun file(sha256: String): Path? = pack.file(sha256) ?: own.file(sha256)
}

/**
 * The pack (ADR-052, 7.20 L4): fonts the owner picked on the specimen page, in the package's `fonts/` (`pack.json`
 * names them; `<token>.ttf` and `<token>-bold.ttf`; their licenses in `licenses/`). Read once at start; a font
 * whose file is missing or broken is left out and logged.
 */
class FontPack(dir: Path?) {
    class Packed(val info: FontInfo, val files: Map<String, Path>)

    @Serializable
    private class Entry(val token: String, val name: String, val group: String, val scale: Float? = null)

    val fonts: List<Packed> = dir?.resolve(LIST)?.takeIf { it.isRegularFile() }?.let { list ->
        runCatching { Json.decodeFromString<List<Entry>>(list.readText()) }
            .onFailure { log.warn("The font pack's {} doesn't read: {}", list, it.message) }
            .getOrNull()
            ?.mapNotNull { entry -> load(dir, entry) }
    }.orEmpty()

    private val byHash = fonts.flatMap { it.files.entries }.associate { it.key to it.value }

    fun file(sha256: String): Path? = byHash[sha256]

    private fun load(dir: Path, entry: Entry): Packed? {
        val files = listOf(400 to "${entry.token}.ttf", 700 to "${entry.token}-bold.ttf").mapNotNull { (weight, name) ->
            dir.resolve(name).takeIf { it.isRegularFile() }?.let { weight to it }
        }
        val regular = files.firstOrNull { it.first == 400 }?.second ?: return null.also { log.warn("No {} in the font pack", entry.token) }
        val read = FontFile.read(regular.readBytes())
        if (read !is FontFile.Read.Font) {
            log.warn("The font pack's {} doesn't read: {}", entry.token, (read as FontFile.Read.Refused).reason)
            return null
        }
        val hashed = files.map { (weight, path) -> Triple(weight, BlobStore.sha256(path), path) }
        return Packed(
            FontInfo(
                token = entry.token,
                name = entry.name,
                group = entry.group,
                scale = entry.scale ?: read.font.scale,
                files = hashed.map { (weight, sha, path) -> FontFileInfo(weight, sha, path.fileSize()) },
                cyrillic = read.font.cyrillic,
            ),
            hashed.associate { it.second to it.third },
        )
    }

    private companion object {
        const val LIST = "pack.json"
        val log = LoggerFactory.getLogger("benbow.fonts")
    }
}
