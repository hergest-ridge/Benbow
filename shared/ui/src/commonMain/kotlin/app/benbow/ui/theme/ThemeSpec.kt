package app.benbow.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.hct.Hct

/** How far apart a theme's colors stand (ADR-057): Material's contrast levels. */
enum class ThemeContrast(val level: Double) { Standard(0.0), Medium(0.5), High(1.0) }

/** A palette's character, as Material's schemes have them; the names are what's stored. */
enum class PaletteCharacter(internal val style: PaletteStyle) {
    Calm(PaletteStyle.TonalSpot),
    Bright(PaletteStyle.Vibrant),
    Expressive(PaletteStyle.Expressive),
    True(PaletteStyle.Fidelity),
    Quiet(PaletteStyle.Neutral),
    Mono(PaletteStyle.Monochrome),
}

/** What a palette's pages and cards lean to: its own color, warm, cool or plain grey. */
enum class PaletteGround { Own, Warm, Cool, Gray }

/** «Своя» (ADR-057): a palette made from a color of one's own. */
@Immutable
data class OwnPalette(
    val seed: Color,
    val character: PaletteCharacter = PaletteCharacter.Calm,
    val ground: PaletteGround = PaletteGround.Own,
    /** A second accent of one's own; null, the character's. */
    val accent: Color? = null,
)

/** Everything a theme is made from: the palette (one for every device) and this device's contrast and black. */
@Immutable
data class ThemeSpec(
    val palette: ThemePalette = ThemePalette.Inn,
    /** [ThemePalette.Own]'s colors; without them it is the inn. */
    val own: OwnPalette? = null,
    /** Material's contrast level, 0 to 1: the device's choice, or its system's. */
    val contrast: Double = 0.0,
    /** A dark theme on black, for OLED screens. */
    val black: Boolean = false,
)

/** `#RRGGBB`, as colors of one's own are stored. */
fun Color.hex(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/** A color from `#RRGGBB` (or `RRGGBB`); null for anything else. */
fun colorOfHex(text: String?): Color? {
    val digits = text?.trim()?.removePrefix("#") ?: return null
    if (digits.length != 6 || digits.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
    return Color(0xFF000000 or digits.toLong(16))
}

/** A color's hue on Material's HCT wheel, 0 to 360, as «Своя»'s slider shows it. */
fun Color.hctHue(): Double = Hct.fromInt(toArgb()).hue

/** A color's colorfulness in HCT (0 grey, about 100 the most a screen shows), as «Своя»'s slider shows it. */
fun Color.hctChroma(): Double = Hct.fromInt(toArgb()).chroma

/** The color at [hue] and [chroma], at a middle [tone] (as near as the screen's colors go). */
fun hctColor(hue: Double, chroma: Double, tone: Double = 50.0): Color = Color(Hct.from(hue, chroma, tone).toInt())
