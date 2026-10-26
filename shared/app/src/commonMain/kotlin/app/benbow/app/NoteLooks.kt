package app.benbow.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.benbow.ui.theme.BenbowPalette
import kotlin.random.Random

/*
 * The tokens of a note's look (ADR-047) and what they draw: each layer's choices for «Оформление записки», in the order
 * of their names in the strings, read into a [NoteLook] for the backdrop. A token this app doesn't know (a newer
 * app's) draws nothing and stays in its field; a light is its sources written out, "315 orange 2;45 blue 1".
 */

/**
 * An entry's [look] (its tokens by layer) behind a list's card or a page over [base], laid out by the entry's [id]: [muted] in
 * lists, [moving] in the open note. Pass no look while the entry is behind a curtain (Бережно).
 */
@Composable
internal fun entryBackdrop(
    id: String,
    look: Map<String, String>,
    base: Color,
    muted: Boolean,
    moving: Boolean = false,
    lightFrame: (() -> LightFrame?)? = null,
): Modifier {
    val drawn = remember(look) { NoteLooks.look(look) }
    return noteBackdrop(drawn, base, MaterialTheme.colorScheme.onSurface, id.hashCode(), muted, moving, lightFrame)
}

/** The layers «Оформление записки» sets, by their field's name (`look_<field>`, LookFields). */
internal enum class LookLayer(val field: String) { Paper("paper"), Pattern("pattern"), Light("light"), Motion("motion"), Scrap("scrap") }

internal object NoteLooks {
    val papers = listOf("dots", "lines", "grid", "parchment", "white", "creased", "beige")

    /** Photographed papers (ambientCG, CC0) by token. */
    private val photos = mapOf("white" to "paper001", "creased" to "paper003", "beige" to "paper006")

    private val ownPatterns = listOf("circles", "triangles", "waves", "terrazzo", "leaves", "stars")

    /** Hero Patterns (Steve Schoger, CC BY 4.0): credited in «Оформление записки» when one is chosen. */
    private val hero = listOf(
        "graph-paper", "topography", "leaf", "endless-clouds", "wiggle", "polka-dots", "texture", "wallpaper", "bubbles",
        "four-point-stars", "autumn", "bamboo", "moroccan", "formal-invitation", "overlapping-circles", "rain",
        "lines-in-motion", "glamorous", "kiwi",
    )

    /** William Morris's wallpapers from The Met (public domain). */
    private val morris = listOf("willow-bough", "marigold", "daisy", "fruit")

    val patterns = ownPatterns + hero.map { HERO + it } + morris.map { MORRIS + it }

    /** Tabler Icons (MIT) scattered for "leaves" and "stars". */
    private val scatters = mapOf("leaves" to listOf("leaf", "plant-2", "seedling", "clover"), "stars" to listOf("star", "sparkles", "moon"))

    val lights =
        listOf("315 orange 2", "45 blue 2", "225 pink 2;45 blue 2", "200 yellow 2;330 pink 2;80 purple 1", "270 yellow 3", "135 purple 2")
    val motions = listOf("drift")
    val scraps = listOf("tape", "scraps", "stamp", "stitch", "torn", "collage")

    fun choices(layer: LookLayer): List<String> = when (layer) {
        LookLayer.Paper -> papers
        LookLayer.Pattern -> patterns
        LookLayer.Light -> lights
        LookLayer.Motion -> motions
        LookLayer.Scrap -> scraps
    }

    fun isHero(token: String?) = token?.startsWith(HERO) == true

    fun isMorris(token: String?) = token?.startsWith(MORRIS) == true

    fun isPhoto(token: String?) = token in photos

    /** What [tokens] (by layer field, as LookFields reads them) draw; unknown tokens and layers draw nothing. */
    fun look(tokens: Map<String, String>): NoteLook {
        val paper = tokens[LookLayer.Paper.field]
        val pattern = tokens[LookLayer.Pattern.field]
        val (scrap, shuffle) = scrapOf(tokens[LookLayer.Scrap.field])
        return NoteLook(
            paper = paper?.takeIf { it in papers && it !in photos },
            paperPhoto = photos[paper],
            wallpaper = pattern?.takeIf(::isMorris)?.removePrefix(MORRIS)?.takeIf { it in morris },
            pattern = when (pattern) {
                in scatters -> "scatter"
                in ownPatterns -> pattern
                else -> null
            },
            scatter = scatters[pattern].orEmpty(),
            tile = pattern?.takeIf(::isHero)?.removePrefix(HERO)?.takeIf { it in hero },
            lights = lightsOf(tokens[LookLayer.Light.field]),
            motion = tokens[LookLayer.Motion.field]?.takeIf { it in motions },
            scrap = scrap,
            scrapShuffle = shuffle,
        )
    }

    /** "315 orange 2;45 blue 1": up to three sources, each an angle, a palette tone and a strength 1-3; bad ones left out. */
    fun lightsOf(token: String?): List<LookLight> = token.orEmpty().split(';').mapNotNull { source ->
        val (angle, tone, strength) = source.trim().split(' ').takeIf { it.size == 3 } ?: return@mapNotNull null
        LookLight(
            angle.toFloatOrNull()?.takeIf { it in 0f..360f } ?: return@mapNotNull null,
            tone.takeIf { it in BenbowPalette.tokens } ?: return@mapNotNull null,
            strength.toIntOrNull()?.takeIf { it in 1..3 } ?: return@mapNotNull null,
        )
    }.take(3)

    /** "tape.7": the kind, and how many times it was shuffled (0 for "tape"), which lays it out anew. */
    fun scrapOf(token: String?): Pair<String?, Int> {
        if (token == null) return null to 0
        val kind = token.substringBefore('.').takeIf { it in scraps } ?: return null to 0
        return kind to (token.substringAfter('.', "").toIntOrNull() ?: 0)
    }

    /** «Перемешать»: the same kind, laid out anew. */
    fun shuffled(token: String, random: Random = Random.Default): String = "${scrapOf(token).first ?: token}.${random.nextInt(1, 1_000)}"

    private const val HERO = "hero-"
    private const val MORRIS = "morris-"
}
