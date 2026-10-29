package app.benbow.app

import android.app.UiModeManager
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Android 14's contrast setting (0 standard, 0.5 medium, 1 high), followed as it changes; 0 before Android 14. */
@Composable
internal actual fun rememberSystemContrast(): Double {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 0.0
    val context = LocalContext.current
    val modes = remember(context) { context.getSystemService(UiModeManager::class.java) }
    var level by remember(modes) { mutableFloatStateOf(modes.contrast) }
    DisposableEffect(modes) {
        val listener = UiModeManager.ContrastChangeListener { level = it }
        modes.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { modes.removeContrastChangeListener(listener) }
    }
    return level.coerceIn(0f, 1f).toDouble()
}

/** Android's window takes the theme in the activity (`MainActivity`), as the capture sheet must stay see-through. */
@Composable
internal actual fun ThemeOutside(scheme: ColorScheme, dark: Boolean) = Unit
