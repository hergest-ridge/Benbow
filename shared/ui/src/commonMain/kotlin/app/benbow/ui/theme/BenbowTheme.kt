package app.benbow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Provisional "inn" palette (lantern amber, sea blue, parchment). Final tokens come with the design pass, see docs/ux.md.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = Color(0xFF2E5F7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC6E7FF),
    onSecondaryContainer = Color(0xFF001E2D),
    background = Color(0xFFFBF6EE),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFBF6EE),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF0E0CF),
    onSurfaceVariant = Color(0xFF50453A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB870),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFF98CDEE),
    onSecondary = Color(0xFF00344B),
    secondaryContainer = Color(0xFF0E4B65),
    onSecondaryContainer = Color(0xFFC6E7FF),
    background = Color(0xFF17130E),
    onBackground = Color(0xFFECE0D5),
    surface = Color(0xFF17130E),
    onSurface = Color(0xFFECE0D5),
    surfaceVariant = Color(0xFF50453A),
    onSurfaceVariant = Color(0xFFD4C4B5),
)

/** Single entry point for app theming; Material 3 (and later Expressive) APIs stay behind it. */
@Composable
fun BenbowTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
