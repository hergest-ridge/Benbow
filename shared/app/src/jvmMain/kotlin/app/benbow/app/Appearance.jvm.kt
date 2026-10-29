package app.benbow.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit

/** Desktop has no system palette to borrow. */
@Composable
internal actual fun rememberSystemColors(dark: Boolean): ColorScheme? = null

/** Desktop has no system-wide wish for less motion that Java can read. */
@Composable
internal actual fun reducedMotion(): Boolean = false

/** Windows' high contrast (a contrast theme on), read once: the highest level; elsewhere 0. */
@Composable
internal actual fun rememberSystemContrast(): Double = remember {
    if (Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on") == true) 1.0 else 0.0
}

/** The desktop window takes the theme in its shell (`DesktopShell`), beside its title bar. */
@Composable
internal actual fun ThemeOutside(scheme: ColorScheme, dark: Boolean) = Unit
