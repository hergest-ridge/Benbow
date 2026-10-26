package app.benbow.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Desktop has no system palette to borrow. */
@Composable
internal actual fun rememberSystemColors(dark: Boolean): ColorScheme? = null

/** Desktop has no system-wide wish for less motion that Java can read. */
@Composable
internal actual fun reducedMotion(): Boolean = false
