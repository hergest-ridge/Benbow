package app.benbow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.contrast.Contrast
import com.materialkolor.dynamicColorScheme
import com.materialkolor.hct.Hct

/** Light, dark, or whatever the system says. */
enum class ThemeMode { System, Light, Dark }

/**
 * The app's palettes (ux.md#visual-language): complete Material 3 schemes generated from a few seed colors, so every
 * role (surfaces, containers) belongs to the palette. [System] takes the phone's Material You color where there is one,
 * [Own] the colors of [ThemeSpec.own]; both are the inn where they have nothing.
 */
enum class ThemePalette(
    internal val primary: Color,
    internal val secondary: Color?,
    internal val neutral: Color?,
    internal val style: PaletteStyle,
) {
    /** The inn: lantern amber, sea blue, warm wood. */
    Inn(Color(0xFFD9822B), Color(0xFF2E5F7A), Color(0xFF8C7B6A), PaletteStyle.Fidelity),

    /** The sea outside: deep blue, foam teal, a little amber. */
    Sea(Color(0xFF1F6A94), Color(0xFF2E8A88), Color(0xFF6E7A82), PaletteStyle.TonalSpot),

    /** Parchment and ink: quiet sepia, almost no color. */
    Parchment(Color(0xFF8A6A3B), Color(0xFF6B5B45), Color(0xFF8E846F), PaletteStyle.Neutral),

    /** The wood behind the inn: moss, bark, a grey-green shade. */
    Forest(Color(0xFF3F7D4E), Color(0xFF7A6A3A), Color(0xFF737A70), PaletteStyle.TonalSpot),

    /** Dusk over the bay: violet, a rose of the last light. */
    Dusk(Color(0xFF6B5BA8), Color(0xFFB5677D), Color(0xFF7A7482), PaletteStyle.TonalSpot),

    /** A letter's ink: blue-black on grey paper, nearly no color. */
    Ink(Color(0xFF304F7A), Color(0xFF5A6270), Color(0xFF74777C), PaletteStyle.Neutral),

    System(Color(0xFFD9822B), null, null, PaletteStyle.TonalSpot),

    Own(Color(0xFFD9822B), null, null, PaletteStyle.TonalSpot),
}

/** What a scheme is generated from: [ThemeSpec]'s palette, or its own colors, or the system's. */
private class Seeds(val primary: Color, val secondary: Color?, val neutral: Color?, val style: PaletteStyle)

private fun ThemeSpec.seeds(system: ColorScheme?): Seeds {
    val own = own
    return when {
        palette == ThemePalette.System && system != null -> Seeds(system.primary, null, null, PaletteStyle.TonalSpot)

        palette == ThemePalette.Own && own != null -> Seeds(own.seed, own.accent, own.ground.neutral(), own.character.style)

        palette == ThemePalette.System || palette == ThemePalette.Own -> ThemePalette.Inn.let {
            Seeds(it.primary, it.secondary, it.neutral, it.style)
        }

        else -> Seeds(palette.primary, palette.secondary, palette.neutral, palette.style)
    }
}

private fun PaletteGround.neutral(): Color? = when (this) {
    PaletteGround.Own -> null
    PaletteGround.Warm -> Color(Hct.from(65.0, 10.0, 50.0).toInt())
    PaletteGround.Cool -> Color(Hct.from(235.0, 10.0, 50.0).toInt())
    PaletteGround.Gray -> Color(Hct.from(0.0, 0.0, 50.0).toInt())
}

/**
 * The scheme [spec] makes in a mode, one generator for every palette (ADR-057): the phone's Material You ([system])
 * goes through it too, from its color, so contrast and Benbow's surfaces hold there as well.
 */
fun schemeOf(spec: ThemeSpec, dark: Boolean, system: ColorScheme? = null): ColorScheme {
    val seeds = spec.seeds(system)
    return dynamicColorScheme(
        seedColor = seeds.primary,
        isDark = dark,
        isAmoled = spec.black && dark,
        secondary = seeds.secondary,
        neutral = seeds.neutral,
        style = seeds.style,
        contrastLevel = spec.contrast.coerceIn(0.0, 1.0),
    ).withBenbowSurfaces(dark)
}

/** The generated scheme for [palette] at standard contrast (no system colors: [ThemePalette.System] is the inn). */
fun schemeOf(palette: ThemePalette, dark: Boolean): ColorScheme = schemeOf(ThemeSpec(palette), dark)

/** [spec]'s roles of our own, for previews and tests (the app reads them from [BenbowTheme.colors]). */
fun colorsOf(spec: ThemeSpec, dark: Boolean, system: ColorScheme? = null): BenbowColors =
    benbowColors(schemeOf(spec, dark, system), dark, spec, spec.seeds(system).style)

fun colorsOf(palette: ThemePalette, dark: Boolean): BenbowColors = colorsOf(ThemeSpec(palette), dark)

/**
 * Material's scheme as Benbow uses it (ADR-057). White cards on a tinted page (owner 2026-10-29): in the light the page
 * and its bars take the container tone, tinted no stronger than [PAGE_CHROMA] at [PAGE_TONE] so cards and note colors stand out, and the
 * low container, a card's and a sheet's, goes white; in the dark cards are lighter than the page already. Words on a
 * container (a button's, a chip's) read 7:1, not the 4.5 a seed may leave.
 */
internal fun ColorScheme.withBenbowSurfaces(dark: Boolean): ColorScheme {
    val paged = if (dark) {
        this
    } else {
        val tint = Hct.fromInt(surfaceContainer.toArgb())
        val page = Color(Hct.from(tint.hue, minOf(tint.chroma, PAGE_CHROMA), PAGE_TONE).toInt())
        copy(background = page, surface = page, surfaceContainer = page, surfaceContainerLow = surfaceContainerLowest)
    }
    val primary = readable(paged.onPrimaryContainer, paged.primaryContainer)
    val secondary = readable(paged.onSecondaryContainer, paged.secondaryContainer)
    val tertiary = readable(paged.onTertiaryContainer, paged.tertiaryContainer)
    val error = readable(paged.onErrorContainer, paged.errorContainer)
    return paged.copy(
        onPrimaryContainer = primary.first,
        primaryContainer = primary.second,
        onSecondaryContainer = secondary.first,
        secondaryContainer = secondary.second,
        onTertiaryContainer = tertiary.first,
        tertiaryContainer = tertiary.second,
        onErrorContainer = error.first,
        errorContainer = error.second,
    )
}

/**
 * Words [on] a container ([ground]) that read [ratio]: the words moved away from its tone, keeping their hue, on their
 * own side (darker words stay darker) or else the other; where a middle tone leaves neither side room (Material's
 * medium contrast), the container moves away from the words instead. Returns the words and the container.
 */
internal fun readable(on: Color, ground: Color, ratio: Double = CONTAINER_RATIO): Pair<Color, Color> {
    if (contrastRatio(on, ground) >= ratio) return on to ground
    val words = Hct.fromInt(on.toArgb())
    val below = Hct.fromInt(ground.toArgb()).tone
    val sides = listOf(Contrast.darkerUnsafe(below, ratio), Contrast.lighterUnsafe(below, ratio))
        .map { Color(Hct.from(words.hue, words.chroma, it).toInt()) }
        .let { if (words.tone < below) it else it.reversed() }
    sides.firstOrNull { contrastRatio(it, ground) >= ratio }?.let { return it to ground }
    val box = Hct.fromInt(ground.toArgb())
    val tone = if (words.tone > below) Contrast.darkerUnsafe(words.tone, ratio) else Contrast.lighterUnsafe(words.tone, ratio)
    return on to Color(Hct.from(box.hue, box.chroma, tone).toInt())
}

private const val CONTAINER_RATIO = 7.1
private const val PAGE_CHROMA = 7.0

/** The light page's tone, Material's container tone at standard contrast, kept at every contrast: cards and note colors
 * stand off it the same (higher contrast deepens words and edges, not the page). */
private const val PAGE_TONE = 94.0

/**
 * Single entry point for app theming; Material 3 (and later Expressive) APIs stay behind it.
 * [systemColors] is the phone's Material You scheme for [ThemePalette.System], null where the platform has none.
 */
@Composable
fun BenbowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    spec: ThemeSpec = ThemeSpec(),
    systemColors: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val system = if (spec.palette == ThemePalette.System) systemColors else null
    val scheme = remember(spec, darkTheme, system) { schemeOf(spec, darkTheme, system) }
    val colors = remember(scheme, darkTheme, spec) { benbowColors(scheme, darkTheme, spec, spec.seeds(system).style) }
    // The theme's dark twin for a black note in the light (InTheDark), made once, on the first such note.
    val night = remember(spec, darkTheme, system) {
        if (darkTheme) {
            null
        } else {
            lazy {
                schemeOf(spec, dark = true, system).let { Night(it, benbowColors(it, true, spec, spec.seeds(system).style)) }
            }
        }
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalBenbowColors provides colors, LocalNight provides night, content = content)
    }
}

private class Night(val scheme: ColorScheme, val colors: BenbowColors)

private val LocalNight = staticCompositionLocalOf<Lazy<Night>?> { null }

/**
 * [content] in the theme's dark twin while [on] (a black note in a light theme: its words and controls go light), else
 * as it is; in a dark theme always as it is. The tree is the same either way, so a note turned black rebuilds nothing.
 */
@Composable
fun InTheDark(on: Boolean, content: @Composable () -> Unit) {
    val night = LocalNight.current?.takeIf { on }?.value
    val style = LocalTextStyle.current
    val words = LocalContentColor.current
    val colors = BenbowTheme.colors
    MaterialTheme(colorScheme = night?.scheme ?: MaterialTheme.colorScheme) {
        CompositionLocalProvider(
            LocalBenbowColors provides (night?.colors ?: colors),
            LocalContentColor provides (night?.scheme?.onSurface ?: words),
            LocalTextStyle provides style,
            content = content,
        )
    }
}

/** Benbow's roles beside [MaterialTheme]'s. */
object BenbowTheme {
    val colors: BenbowColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBenbowColors.current
}

/** Resolves [ThemeMode] against the system setting. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
