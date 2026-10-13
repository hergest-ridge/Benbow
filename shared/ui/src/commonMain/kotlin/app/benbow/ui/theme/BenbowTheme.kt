package app.benbow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/** Light, dark, or whatever the system says. */
enum class ThemeMode { System, Light, Dark }

/**
 * The app's palettes (ux.md#visual-language): complete Material 3 schemes generated from a few seed colors, so every
 * role (surfaces, containers) belongs to the palette. [System] uses the phone's Material You colors where there are any.
 */
enum class ThemePalette(
    internal val primary: Color,
    internal val secondary: Color,
    internal val neutral: Color,
    internal val style: PaletteStyle,
) {
    /** The inn: lantern amber, sea blue, warm wood. */
    Inn(Color(0xFFD9822B), Color(0xFF2E5F7A), Color(0xFF8C7B6A), PaletteStyle.Fidelity),

    /** The sea outside: deep blue, foam teal, a little amber. */
    Sea(Color(0xFF1F6A94), Color(0xFF2E8A88), Color(0xFF6E7A82), PaletteStyle.TonalSpot),

    /** Parchment and ink: quiet sepia, almost no color. */
    Parchment(Color(0xFF8A6A3B), Color(0xFF6B5B45), Color(0xFF8E846F), PaletteStyle.Neutral),

    System(Color(0xFFD9822B), Color(0xFF2E5F7A), Color(0xFF8C7B6A), PaletteStyle.TonalSpot),
}

/** The generated scheme for [palette] (no system colors: [System] falls back to the inn). */
fun schemeOf(palette: ThemePalette, dark: Boolean): ColorScheme = dynamicColorScheme(
    seedColor = palette.primary,
    isDark = dark,
    secondary = palette.secondary,
    neutral = palette.neutral,
    style = palette.style,
)

/**
 * Single entry point for app theming; Material 3 (and later Expressive) APIs stay behind it.
 * [systemColors] is the phone's Material You scheme for [ThemePalette.System], null where the platform has none.
 */
@Composable
fun BenbowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePalette.Inn,
    systemColors: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val generated = remember(palette, darkTheme) { schemeOf(if (palette == ThemePalette.System) ThemePalette.Inn else palette, darkTheme) }
    val scheme = if (palette == ThemePalette.System && systemColors != null) systemColors else generated
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Resolves [ThemeMode] against the system setting. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
