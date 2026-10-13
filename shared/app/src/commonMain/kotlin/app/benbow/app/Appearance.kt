package app.benbow.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.client.Settings
import app.benbow.ui.theme.BenbowTheme
import app.benbow.ui.theme.ThemeMode
import app.benbow.ui.theme.ThemePalette
import app.benbow.ui.theme.isDark

/** How the app looks on this device: light or dark, and the palette. Kept in local settings, not synced. */
data class Appearance(val mode: ThemeMode = ThemeMode.System, val palette: ThemePalette = ThemePalette.Inn) {
    internal fun save(settings: Settings) {
        settings.put(MODE, mode.name)
        settings.put(PALETTE, palette.name)
    }

    companion object {
        private const val MODE = "theme.mode"
        private const val PALETTE = "theme.palette"

        internal fun load(settings: Settings) = Appearance(
            mode = ThemeMode.entries.firstOrNull { it.name == settings.get(MODE) } ?: ThemeMode.System,
            palette = ThemePalette.entries.firstOrNull { it.name == settings.get(PALETTE) } ?: ThemePalette.Inn,
        )
    }
}

/** The phone's Material You colors where the platform has them (Android 12+), else null. */
@Composable
internal expect fun rememberSystemColors(dark: Boolean): ColorScheme?

/** [BenbowTheme] with this device's appearance: for the app and the capture sheet alike. */
@Composable
fun AppTheme(services: AppServices, content: @Composable () -> Unit) {
    val appearance by services.appearance.collectAsStateWithLifecycle()
    val dark = appearance.mode.isDark()
    BenbowTheme(darkTheme = dark, palette = appearance.palette, systemColors = rememberSystemColors(dark), content = content)
}
