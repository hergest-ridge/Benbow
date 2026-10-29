package app.benbow.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct

/**
 * The content tokens for text color, highlight and callout tones (docs/content-format.md#theme-tokens). Their colors
 * belong to a theme ([BenbowTheme.colors]); [print] is the one theme for paper, whatever a device shows.
 */
object BenbowPalette {
    val tokens = listOf("gray", "brown", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "red")

    /** The tokens on paper (printed pages, a drawing's SVG file): the default palette's, so files read the same anywhere. */
    val print: TokenColors by lazy { TokenColors.of(schemeOf(ThemePalette.Inn, dark = false).primary) }

    /** The note colors on paper, as [print]. */
    val printSwatches: SwatchColors by lazy {
        SwatchColors.of(schemeOf(ThemePalette.Inn, dark = false).primary, ThemePalette.Inn.style.swatchChroma())
    }

    /**
     * A chart's categorical slot (0 to 5, then round again): the dataviz reference palette, checked for color blindness
     * and for contrast on light and dark surfaces, so it stays as it is in every theme.
     */
    fun chart(slot: Int, dark: Boolean): Color = Color((if (dark) CHART_DARK else CHART_LIGHT)[slot.mod(CHART_LIGHT.size)])

    private val CHART_LIGHT = longArrayOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300)
    private val CHART_DARK = longArrayOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300)
}

/**
 * The ten content tokens in one theme (ADR-057). Each keeps a hue of its own, turned a little toward the theme's primary
 * (gentler than Material's harmonize, so neighbours stay apart), in four uses: [text] for words, at Material's custom-color tones (40 light, 80 dark) that read
 * 4.5:1 on every page, card and note color; [mark] for fills, lights and decoration, at the token's own tone as tuned;
 * [highlight] behind words; [tint] under a callout. Unknown tokens are null, so callers fall back to the default color.
 */
@Immutable
class TokenColors private constructor(private val light: Map<String, Shades>, private val dark: Map<String, Shades>) {
    private class Shades(val text: Color, val mark: Color, val highlight: Color, val tint: Color)

    fun text(token: String, dark: Boolean): Color? = shades(token, dark)?.text

    fun mark(token: String, dark: Boolean): Color? = shades(token, dark)?.mark

    fun highlight(token: String, dark: Boolean): Color? = shades(token, dark)?.highlight

    fun tint(token: String, dark: Boolean): Color? = shades(token, dark)?.tint

    private fun shades(token: String, dark: Boolean) = (if (dark) this.dark else light)[token]

    companion object {
        /** Each token's own color, light and dark (once Notion's): [mark] keeps it, the rest take its hue and chroma. */
        private val KEYS: Map<String, Pair<Long, Long>> = mapOf(
            "gray" to (0xFF787774 to 0xFFA5A29E), "brown" to (0xFF9F6B53 to 0xFFC9967E),
            "orange" to (0xFFD9730D to 0xFFFFA344), "yellow" to (0xFFB7871F to 0xFFFFD45E),
            "green" to (0xFF448361 to 0xFF6CC08F), "teal" to (0xFF2E7C7C to 0xFF5FC4C4),
            "blue" to (0xFF337EA9 to 0xFF6CB4E4), "purple" to (0xFF9065B0 to 0xFFB794E0),
            "pink" to (0xFFC14C8A to 0xFFEA7DB7), "red" to (0xFFD44C47 to 0xFFFF7B72),
        )

        /** [lightTone] and [darkTone]: Material's custom-color tones (40, 80), further out where a scheme's grounds need it. */
        internal fun of(primary: Color, lightTone: Double = 40.0, darkTone: Double = 80.0): TokenColors {
            val source = Hct.fromInt(primary.toArgb())
            fun shades(key: Long, dark: Boolean): Shades {
                val key = Hct.fromInt(key.toInt())
                val own = Hct.from(turned(key.hue, source.hue), key.chroma, key.tone)
                val text = hct(own.hue, own.chroma, if (dark) darkTone else lightTone)
                return Shades(
                    text = text,
                    mark = Color(own.toInt()),
                    highlight = hct(own.hue, own.chroma, if (dark) 40.0 else 80.0).copy(alpha = HIGHLIGHT_ALPHA),
                    tint = text.copy(alpha = if (dark) 0.18f else 0.12f),
                )
            }
            return TokenColors(
                light = KEYS.mapValues { (_, keys) -> shades(keys.first, dark = false) },
                dark = KEYS.mapValues { (_, keys) -> shades(keys.second, dark = true) },
            )
        }

        private const val HIGHLIGHT_ALPHA = 0.55f
    }
}

/**
 * The note colors (an entry's `background`, a tag's, a category's, a calendar's; names as `SWATCHES` keeps them) in one
 * theme. They were Keep's shades; now they belong to the theme (owner 2026-10-29, ADR-057): hues spread around the wheel
 * and turned a little toward the theme's primary, as soft as the palette's style, at Material's container tones for a note's
 * page, a card or a chip ([fill]: 90 light, 30 dark) and at its text tones for what has to stand out on the page, a
 * calendar's dots or a category's name ([mark]: 40 light, 80 dark).
 */
@Immutable
class SwatchColors private constructor(
    private val light: Map<String, Pair<Color, Color>>,
    private val dark: Map<String, Pair<Color, Color>>,
) {
    fun fill(name: String?, dark: Boolean): Color? = (if (dark) this.dark else light)[name]?.first

    fun mark(name: String?, dark: Boolean): Color? = (if (dark) this.dark else light)[name]?.second

    companion object {
        /** Each color's hue on HCT's wheel, spread so no two look alike; the order is the pickers'. */
        private val HUES = linkedMapOf(
            "coral" to 18.0, "peach" to 55.0, "sand" to 105.0, "mint" to 138.0, "sage" to 175.0,
            "fog" to 218.0, "storm" to 260.0, "dusk" to 300.0, "blossom" to 345.0, "clay" to 80.0,
        )

        /** The quiet ones: storm a cool grey, clay a warm one. */
        private val SOFTER = mapOf("storm" to 0.3, "clay" to 0.3)

        val names: List<String> = HUES.keys.toList()

        internal const val LIGHT_FILL = 90.0
        internal const val DARK_FILL = 30.0

        internal fun of(primary: Color, chroma: Double): SwatchColors {
            val source = Hct.fromInt(primary.toArgb())
            fun pair(name: String, hue: Double, dark: Boolean): Pair<Color, Color> {
                val turned = turned(hue, source.hue)
                val own = chroma * (SOFTER[name] ?: 1.0)
                val fill = hct(turned, own, if (dark) DARK_FILL else LIGHT_FILL)
                val mark = hct(turned, maxOf(own * 1.8, if (name in SOFTER) own * 1.8 else 36.0), if (dark) 80.0 else 40.0)
                return fill to mark
            }
            return SwatchColors(
                light = HUES.mapValues { (name, hue) -> pair(name, hue, dark = false) },
                dark = HUES.mapValues { (name, hue) -> pair(name, hue, dark = true) },
            )
        }
    }
}

private fun hct(hue: Double, chroma: Double, tone: Double) = Color(Hct.from(hue, chroma, tone).toInt())

/**
 * [hue] turned toward the theme's ([toward]) more gently than Material's harmonize (a fifth of the way, 6° at most),
 * so ten colors side by side keep apart, whatever the theme's own hue.
 */
private fun turned(hue: Double, toward: Double): Double {
    val apart = ((toward - hue) % 360.0 + 540.0) % 360.0 - 180.0
    val turn = (apart * SWATCH_TURN).coerceIn(-SWATCH_TURN_MAX, SWATCH_TURN_MAX)
    return ((hue + turn) % 360.0 + 360.0) % 360.0
}

private const val SWATCH_TURN = 0.2
private const val SWATCH_TURN_MAX = 6.0
