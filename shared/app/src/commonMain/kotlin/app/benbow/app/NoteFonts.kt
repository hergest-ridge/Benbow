package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import app.benbow.ui.editor.NoteFont
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

/*
 * The fonts a note can take (ADR-047, L3): the eight the owner picked on the specimen page, bundled with the app (all
 * OFL, Cyrillic and Latin, licenses in docs/licenses), handwritten first. A token this app doesn't know (a font from the
 * server's pack, L4) draws in the app's own font and stays in its field.
 */

internal object NoteFonts {
    /** By token, in the order «Оформление записки» offers them: the diary's hands first (owner). */
    val tokens = listOf("neucha", "pangolin", "marmelad", "amatic-sc", "cormorant-infant", "philosopher", "poiret-one", "jetbrains-mono")

    /** The font's own name, under its sample in «Оформление записки». */
    fun name(token: String): String = token.split('-').joinToString(" ") { part ->
        when (part) {
            "sc" -> "SC"
            "jetbrains" -> "JetBrains"
            else -> part.replaceFirstChar(Char::uppercase)
        }
    }
}

/** The note's font for its look's [token], or null for the app's own (none chosen, or one this app doesn't have). */
@Composable
internal fun noteFont(token: String?): NoteFont? {
    val face = faces[token] ?: return null
    val regular = Font(face.regular)
    val bold = face.bold?.let { Font(it, FontWeight.Bold) }
    return remember(token, regular, bold) { NoteFont(FontFamily(listOfNotNull(regular, bold)), face.scale) }
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
