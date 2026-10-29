package app.benbow.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import kotlin.math.max
import kotlin.math.min

/**
 * Benbow's roles beside Material's (ADR-057), so that screens never fade a color, ask a background how dark it is or
 * keep a table of hex: made with the scheme, from the same palettes, for every palette and mode.
 */
@Immutable
class BenbowColors internal constructor(
    /** The theme is dark. (Whether a note's own color is: [isDarkColor].) */
    val dark: Boolean,
    /** Words quieter than `onSurfaceVariant` that still read: 4.5:1 or more on every page, card and note color. */
    val textQuiet: Color,
    /** What can't be used just now (Material's 38 %): the only words allowed under 4.5:1, as WCAG leaves them out. */
    val disabled: Color,
    /** A line between panes and parts. */
    val divider: Color,
    /** The thin edge of a card or a picture in the text. */
    val hairline: Color,
    /** A board card without a color: white on the light page, a little darker than the dark one (owner, ADR-040). */
    val boardCard: Color,
    /** An open note's paper without a color, and a drawing's: white in the light, the page in the dark. */
    val paper: Color,
    /** The content tokens in this theme. */
    val tokens: TokenColors,
    /** The note colors in this theme. */
    val swatches: SwatchColors,
)

internal fun benbowColors(scheme: ColorScheme, dark: Boolean, spec: ThemeSpec, style: PaletteStyle): BenbowColors {
    // Quiet words keep 4.5:1 from the deepest ground they lie on, a note color's fill (a little margin for rounding),
    // and 7:1 at high contrast; lines go up from the owner's half to whole.
    val level = spec.contrast.coerceIn(0.0, 1.0)
    val quiet = QUIET_RATIO + (CONTAINER_QUIET - QUIET_RATIO) * level
    val variant = Hct.fromInt(scheme.onSurfaceVariant.toArgb())
    val quietTone = if (dark) {
        min(Contrast.lighterUnsafe(SwatchColors.DARK_FILL, quiet), variant.tone)
    } else {
        max(Contrast.darkerUnsafe(SwatchColors.LIGHT_FILL, quiet), variant.tone)
    }
    return BenbowColors(
        dark = dark,
        textQuiet = Color(Hct.from(variant.hue, variant.chroma, quietTone).toInt()),
        disabled = scheme.onSurface.copy(alpha = 0.38f),
        // The dark lines are the owner's (half and 60 % of the outline variant); in the light they fade no further.
        divider = if (dark) scheme.outlineVariant.copy(alpha = (0.5 + 0.5 * level).toFloat()) else scheme.outlineVariant,
        hairline = if (dark) scheme.outlineVariant.copy(alpha = (0.6 + 0.4 * level).toFloat()) else scheme.outlineVariant,
        // On black nothing is darker than the page, so the card is a step lighter there.
        boardCard = when {
            dark && spec.black -> scheme.surfaceContainerLow
            dark -> lerp(scheme.background, Color.Black, 0.2f)
            else -> scheme.surfaceContainerLowest
        },
        paper = if (dark) scheme.background else scheme.surfaceContainerLowest,
        tokens = TokenColors.of(
            scheme.primary,
            lightTone = min(40.0, wordsTone(scheme, dark)),
            darkTone = max(80.0, wordsTone(scheme, dark)),
        ),
        swatches = SwatchColors.of(scheme.primary, style.swatchChroma()),
    )
}

/**
 * The tone colored words need to read [QUIET_RATIO] on the deepest ground in [scheme]: its inset (deeper at higher
 * contrast) or a note color's fill. Only one side counts: [dark] words go up, light ones down.
 */
private fun wordsTone(scheme: ColorScheme, dark: Boolean): Double {
    val inset = Hct.fromInt(scheme.surfaceContainerHighest.toArgb()).tone
    return if (dark) {
        Contrast.lighterUnsafe(max(inset, SwatchColors.DARK_FILL), QUIET_RATIO)
    } else {
        Contrast.darkerUnsafe(min(inset, SwatchColors.LIGHT_FILL), QUIET_RATIO)
    }
}

/** How strong the note colors are for a palette's style: soft, as a whole page wears them. */
internal fun PaletteStyle.swatchChroma(): Double = when (this) {
    PaletteStyle.Fidelity -> 20.0
    PaletteStyle.Neutral, PaletteStyle.Monochrome -> 18.0
    else -> 18.0
}

private const val QUIET_RATIO = 4.6
private const val CONTAINER_QUIET = 7.1

internal val LocalBenbowColors = staticCompositionLocalOf { colorsOf(ThemePalette.Inn, dark = false) }

/** Whether words on this color should be light: a note's own color, a cover's, a scheme picked from a picture. */
fun Color.isDarkColor(): Boolean = luminance() < 0.5f

/** WCAG's contrast ratio of two opaque colors, 1 to 21. */
fun contrastRatio(a: Color, b: Color): Double {
    val x = a.luminance() + 0.05
    val y = b.luminance() + 0.05
    return (max(x, y) / min(x, y)).toDouble()
}

/**
 * How strongly a note's look may lie over its page (ADR-057), up to [wanted]: the largest alpha at which [words] still
 * read [ratio] on what [ground] makes of the page at that alpha. A layer darkens or lightens the page steadily as it
 * grows, so halving finds the edge; 0 when even the bare page doesn't read.
 */
fun budgetAlpha(wanted: Float, words: Color, ratio: Double, ground: (Float) -> Color): Float {
    if (contrastRatio(words, ground(wanted)) >= ratio) return wanted
    var low = 0f
    var high = wanted
    repeat(BUDGET_STEPS) {
        val middle = (low + high) / 2
        if (contrastRatio(words, ground(middle)) >= ratio) low = middle else high = middle
    }
    return low
}

/** What a look may take from a note's words: its words keep 7:1 and its secondary ones 4.5:1 under every layer. */
fun lookBudget(wanted: Float, main: Color, secondary: Color, ground: (Float) -> Color): Float =
    budgetAlpha(budgetAlpha(wanted, main, NOTE_RATIO, ground), secondary, SECONDARY_RATIO, ground)

/** Material's Screen blend of [layer] at [alpha] over [base], channel by channel, as a dark note's lights are drawn. */
fun screen(layer: Color, alpha: Float, base: Color): Color = Color(
    red = 1f - (1f - base.red) * (1f - layer.red * alpha),
    green = 1f - (1f - base.green) * (1f - layer.green * alpha),
    blue = 1f - (1f - base.blue) * (1f - layer.blue * alpha),
)

private const val BUDGET_STEPS = 12
private const val NOTE_RATIO = 7.0
private const val SECONDARY_RATIO = 4.5
