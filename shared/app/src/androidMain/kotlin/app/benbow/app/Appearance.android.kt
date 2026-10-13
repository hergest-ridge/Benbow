package app.benbow.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Material You: the colors Android derives from the wallpaper (every supported version has them). */
@Composable
internal actual fun rememberSystemColors(dark: Boolean): ColorScheme? {
    val context = LocalContext.current
    return remember(context, dark) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
}
