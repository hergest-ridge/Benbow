package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.amatic_sc
import app.benbow.app.resources.amatic_sc_bold
import app.benbow.app.resources.cormorant_infant
import app.benbow.app.resources.cormorant_infant_bold
import app.benbow.app.resources.jetbrains_mono
import app.benbow.app.resources.jetbrains_mono_bold
import app.benbow.app.resources.marmelad
import app.benbow.app.resources.neucha
import app.benbow.app.resources.pangolin
import app.benbow.app.resources.philosopher
import app.benbow.app.resources.philosopher_bold
import app.benbow.app.resources.poiret_one
import app.benbow.client.IoDispatcher
import app.benbow.client.LocalFile
import app.benbow.client.ServerFonts
import app.benbow.client.readAllBytes
import app.benbow.core.api.FontInfo
import app.benbow.ui.editor.NoteFont
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.getFontResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.jetbrains.compose.resources.Font as ResourceFont

/*
 * The fonts a note can take (ADR-047, ADR-052): the app's own eight the owner picked on the specimen page (bundled, all
 * OFL, Cyrillic and Latin, licenses in docs/licenses), then those of the server it is paired with: the pack in its
 * package and the owner's own, fetched the first time one is drawn and kept on the device. The diary's hands come first.
 * A token neither knows (a font taken off the server, one not fetched yet) draws in the app's own font and stays.
 */

/** The fonts the server offers notes (ADR-052); none in previews and most tests. */
internal val LocalServerFonts = staticCompositionLocalOf<ServerFonts?> { null }

/** A font «Шрифт» offers: its token in `look_font`, its own name and its group. */
internal class FontChoice(val token: String, val name: String, val group: String)

internal object NoteFonts {
    /** The app's own, by token, the diary's hands first (owner). */
    val tokens = listOf("neucha", "pangolin", "marmelad", "amatic-sc", "cormorant-infant", "philosopher", "poiret-one", "jetbrains-mono")

    private val groups = mapOf(
        "neucha" to "diary",
        "pangolin" to "diary",
        "marmelad" to "diary",
        "amatic-sc" to "diary",
        "cormorant-infant" to "serif",
        "philosopher" to "sans",
        "poiret-one" to "sans",
        "jetbrains-mono" to "mono",
    )

    /** The groups in «Шрифт»'s order: handwritten first, then the owner's own, the rest as on the specimen page. */
    private val order = listOf("diary", "own", "serif", "sans", "accent", "mono")

    /** The font's own name, under its sample in «Оформление записки». */
    fun name(token: String): String = token.split('-').joinToString(" ") { part ->
        when (part) {
            "sc" -> "SC"
            "jetbrains" -> "JetBrains"
            else -> part.replaceFirstChar(Char::uppercase)
        }
    }

    /** What «Шрифт» offers: the app's own and the server's, group by group, the app's own first within each. */
    fun choices(server: List<FontInfo>): List<FontChoice> {
        val own = tokens.map { FontChoice(it, name(it), groups.getValue(it)) }
        val more = server.filter { it.token !in groups }.map { FontChoice(it.token, it.name, it.group) }
        return (own + more).sortedBy { choice -> order.indexOf(choice.group).takeIf { it >= 0 } ?: order.size }
    }
}

/** The note's font for its look's [token], or null for the app's own (none chosen, or one this device can't draw yet). */
@Composable
internal fun noteFont(token: String?): NoteFont? {
    if (token == null) return null
    val face = faces[token] ?: return serverFont(token)
    val regular = ResourceFont(face.regular)
    val bold = face.bold?.let { ResourceFont(it, FontWeight.Bold) }
    return remember(token, regular, bold) { NoteFont(FontFamily(listOfNotNull(regular, bold)), face.scale) }
}

/** A font from the server: its files from this device, or fetched first; the app's font until they are here. */
@Composable
private fun serverFont(token: String): NoteFont? {
    val fonts = LocalServerFonts.current ?: return null
    val catalog by fonts.catalog.collectAsStateWithLifecycle()
    val info = catalog.firstOrNull { it.token == token } ?: return null
    val key = info.files.joinToString(",") { it.sha256 } + "@" + info.scale
    // A new font starts from what this process has of it, never from the font before (owner: the second one picked
    // didn't take): produceState keeps its value when its key changes.
    val loaded by produceState(LoadedFonts.get(key), key) {
        value = LoadedFonts.get(key)
        if (value == null) value = LoadedFonts.load(fonts, info, key)
    }
    return loaded
}

/** A note's font as its files by weight and its scale, for a page that carries it (printing, PDF). */
internal class PageFont(val files: List<Pair<Int, ByteArray>>, val scale: Float)

/** The files of the note's font [token]: the app's own from its resources, the server's from this device or fetched. */
internal suspend fun pageFont(token: String?, server: ServerFonts): PageFont? {
    if (token == null) return null
    faces[token]?.let { face ->
        val files = listOfNotNull(face.regular.let { 400 to it }, face.bold?.let { 700 to it })
        return PageFont(
            files.map { (weight, resource) ->
                weight to getFontResourceBytes(getSystemResourceEnvironment(), resource)
            },
            face.scale,
        )
    }
    val info = server.catalog.value.firstOrNull { it.token == token } ?: return null
    val files = info.files.mapNotNull { file -> server.file(file)?.let { file.weight to it.readAllBytes() } }
    return PageFont(files, info.scale).takeIf { files.any { it.first < 600 } }
}

/** A font file from the server as Compose reads it on this platform (ADR-052). */
internal expect fun fontFromFile(file: LocalFile, weight: FontWeight): Font

/** The server's fonts this process has read, by their files and size: a card in a list doesn't read its file again. */
private object LoadedFonts {
    private val loaded = HashMap<String, NoteFont>()

    fun get(key: String): NoteFont? = loaded[key]

    suspend fun load(fonts: ServerFonts, info: FontInfo, key: String): NoteFont? {
        val files = info.files.mapNotNull { file -> withContext(IoDispatcher) { fonts.file(file) }?.let { file.weight to it } }
        if (files.none { it.first < 600 }) return null
        val family = FontFamily(files.map { (weight, file) -> fontFromFile(file, FontWeight(weight)) })
        return NoteFont(family, info.scale).also { loaded[key] = it }
    }
}

/** A font's files and how large it is set: a narrow or small hand a little larger, so it reads as the app's font does. */
private class Face(val regular: FontResource, val bold: FontResource? = null, val scale: Float = 1f)

private val faces = mapOf(
    "neucha" to Face(Res.font.neucha, scale = 1.1f),
    "pangolin" to Face(Res.font.pangolin),
    "marmelad" to Face(Res.font.marmelad),
    "amatic-sc" to Face(Res.font.amatic_sc, Res.font.amatic_sc_bold, scale = 1.35f),
    "cormorant-infant" to Face(Res.font.cormorant_infant, Res.font.cormorant_infant_bold, scale = 1.15f),
    "philosopher" to Face(Res.font.philosopher, Res.font.philosopher_bold, scale = 1.05f),
    "poiret-one" to Face(Res.font.poiret_one, scale = 1.1f),
    "jetbrains-mono" to Face(Res.font.jetbrains_mono, Res.font.jetbrains_mono_bold, scale = 0.92f),
)
