package app.benbow.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Desktop has no system palette to borrow. */
@Composable
internal actual fun rememberSystemColors(dark: Boolean): ColorScheme? = null
