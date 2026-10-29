package app.benbow.server.fonts

import app.benbow.core.api.FontFileInfo
import app.benbow.core.api.FontInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * The owner's own fonts (ADR-052, 7.20 L5), added on the admin page: `<data>/fonts/` with `fonts.json` and each file
 * by its SHA-256 (a WOFF is kept as the font it holds). One font is one family, its regular and bold; its token is
 * `own:<family>`, so adding the bold later keeps the notes that took it. Nothing is deleted: a replaced or removed file
 * goes to `removed/<day>/`, and the second disk keeps them all (RecoveryKit).
 */
class OwnFonts(private val dir: Path, private val today: () -> LocalDate = LocalDate::now) {
    @Serializable
    data class Own(
        val token: String,
        val name: String,
        val scale: Float,
        val cyrillic: Boolean,
        val files: List<OwnFile>,
        val added: Long,
    ) {
        fun info() = FontInfo(token, name, OWN_GROUP, scale, files.map { FontFileInfo(it.weight, it.sha256, it.size) }, cyrillic)

        fun file(weight: Int) = files.firstOrNull { it.weight == weight }
    }

    @Serializable
    data class OwnFile(val weight: Int, val sha256: String, val size: Long, val italic: Boolean, val original: String, val ext: String)

    sealed interface Added {
        /** [font] now has the file; [replaced] when it took the place of one of the same weight. */
        data class Done(val font: Own, val replaced: Boolean) : Added

        data class Refused(val reason: String) : Added
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val registry = dir.resolve(REGISTRY)

    @Synchronized
    fun all(): List<Own> = if (registry.isRegularFile()) json.decodeFromString(registry.readText()) else emptyList()

    fun file(sha256: String): Path? = all().flatMap { it.files }.firstOrNull { it.sha256 == sha256 }?.let { dir.resolve(it.stored) }

    /** Adds a font file as the owner sent it ([original] is its name), or says why not. */
    @Synchronized
    fun add(original: String, bytes: ByteArray, now: Long): Added {
        val font = when (val read = FontFile.read(bytes)) {
            is FontFile.Read.Refused -> return Added.Refused(read.reason)
            is FontFile.Read.Font -> read.font
        }
        val weight = if (font.weight >= 600) 700 else 400
        val token = TOKEN_PREFIX + slug(font.family)
        val fonts = all()
        val before = fonts.firstOrNull { it.token == token }
        val there = before?.file(weight)
        if (before != null && there != null && !there.italic && font.italic) {
            return Added.Refused("у «${before.name}» уже есть прямое начертание: курсив записки пока не берут")
        }
        val sha = sha256(font.bytes)
        if (before != null && there?.sha256 == sha) return Added.Done(before, replaced = false)
        val file = OwnFile(weight, sha, font.bytes.size.toLong(), font.italic, original.take(200), if (font.openType) "otf" else "ttf")
        dir.createDirectories()
        val stored = dir.resolve(file.stored)
        if (!stored.exists()) {
            val tmp = dir.resolve("${file.stored}.tmp")
            tmp.writeBytes(font.bytes)
            Files.move(tmp, stored, StandardCopyOption.ATOMIC_MOVE)
        }
        val updated = (before ?: Own(token, font.family.trim(), font.scale, font.cyrillic, emptyList(), now)).let { own ->
            own.copy(
                files = (own.files.filter { it.weight != weight } + file).sortedBy { it.weight },
                // The regular one decides how large it is set and whether it has Russian letters.
                scale = if (weight == 400 || before == null) font.scale else own.scale,
                cyrillic = if (weight == 400 || before == null) font.cyrillic else own.cyrillic,
            )
        }
        save(fonts.filter { it.token != token } + updated)
        there?.let(::putAway)
        return Added.Done(updated, replaced = there != null)
    }

    /** Takes [token] off the catalog; its files go to `removed/`. Notes that took it draw in the app's font. */
    @Synchronized
    fun remove(token: String): Boolean {
        val fonts = all()
        val font = fonts.firstOrNull { it.token == token } ?: return false
        save(fonts - font)
        font.files.forEach(::putAway)
        return true
    }

    /** «Размер»: how large notes set it, from 80 to 160 percent. */
    @Synchronized
    fun setScale(token: String, scale: Float): Boolean {
        val fonts = all()
        if (fonts.none { it.token == token }) return false
        save(fonts.map { if (it.token == token) it.copy(scale = scale.coerceIn(0.8f, 1.6f)) else it })
        return true
    }

    private fun putAway(file: OwnFile) {
        val source = dir.resolve(file.stored)
        if (!source.exists() || all().any { font -> font.files.any { it.sha256 == file.sha256 } }) return
        val folder = dir.resolve(REMOVED).resolve(today().toString()).createDirectories()
        Files.move(source, folder.resolve(file.stored), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun save(fonts: List<Own>) {
        dir.createDirectories()
        val tmp = dir.resolve("$REGISTRY.tmp")
        tmp.writeText(json.encodeToString(fonts.sortedBy { it.added }))
        Files.move(tmp, registry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val DIR = "fonts"
        const val REGISTRY = "fonts.json"
        const val REMOVED = "removed"
        const val OWN_GROUP = "own"
        const val TOKEN_PREFIX = "own:"

        /** Every file in [dir] (the data folder's `fonts/`) the copies keep: the registry, the fonts, the removed ones. */
        fun keptFiles(dir: Path): List<Path> = if (!dir.isDirectory()) {
            emptyList()
        } else {
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".tmp") }.sorted().toList()
            }
        }

        /** The family's name as a token's part: lower case, letters and digits, dashes between. */
        fun slug(family: String): String {
            val words = family.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }
            return words.joinToString("-").take(60).trimEnd('-').ifEmpty { "font" }
        }

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

private val OwnFonts.OwnFile.stored get() = "$sha256.$ext"
