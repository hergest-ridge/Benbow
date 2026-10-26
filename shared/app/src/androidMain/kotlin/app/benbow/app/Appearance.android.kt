package app.benbow.app

import android.provider.Settings
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

/** Android's «Remove animations» (the animator's scale at 0). */
@Composable
internal actual fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
