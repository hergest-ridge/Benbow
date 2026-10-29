package app.benbow.android

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import app.benbow.app.AppServices
import app.benbow.app.themeDark
import app.benbow.app.themeScheme

/**
 * The app's theme where Compose doesn't draw (ADR-057): the window before the first frame, the widgets, a notification's
 * accent. The same palette, contrast and black as in the app, from the same generator.
 */
internal object AndroidTheme {
    private fun systemDark(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun systemContrast(context: Context): Double = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(UiModeManager::class.java).contrast.coerceIn(0f, 1f).toDouble()
    } else {
        0.0
    }

    private fun systemColors(context: Context, dark: Boolean): ColorScheme? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> null
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }

    /** The scheme in [dark], by default the mode the app wears now. */
    fun scheme(context: Context, services: AppServices, dark: Boolean = services.themeDark() ?: systemDark(context)): ColorScheme =
        services.themeScheme(dark, systemColors(context, dark), systemContrast(context))

    /** A notification's accent: the theme's primary in the mode the app wears now. */
    fun accent(context: Context): Int = scheme(context, (context.applicationContext as BenbowApplication).services).primary.toArgb()

    /** A widget's colors, day and night; an app set to light or dark keeps its widgets in it too. */
    fun widgetColors(context: Context, services: AppServices): ColorProviders {
        val mode = services.themeDark()
        val day = scheme(context, services, mode ?: false)
        val night = scheme(context, services, mode ?: true)
        fun pair(role: (ColorScheme) -> androidx.compose.ui.graphics.Color) = ColorProvider(role(day), role(night))
        return colorProviders(
            primary = pair { it.primary },
            onPrimary = pair { it.onPrimary },
            primaryContainer = pair { it.primaryContainer },
            onPrimaryContainer = pair { it.onPrimaryContainer },
            secondary = pair { it.secondary },
            onSecondary = pair { it.onSecondary },
            secondaryContainer = pair { it.secondaryContainer },
            onSecondaryContainer = pair { it.onSecondaryContainer },
            tertiary = pair { it.tertiary },
            onTertiary = pair { it.onTertiary },
            tertiaryContainer = pair { it.tertiaryContainer },
            onTertiaryContainer = pair { it.onTertiaryContainer },
            error = pair { it.error },
            errorContainer = pair { it.errorContainer },
            onError = pair { it.onError },
            onErrorContainer = pair { it.onErrorContainer },
            background = pair { it.background },
            onBackground = pair { it.onBackground },
            surface = pair { it.surface },
            onSurface = pair { it.onSurface },
            surfaceVariant = pair { it.surfaceVariant },
            onSurfaceVariant = pair { it.onSurfaceVariant },
            outline = pair { it.outline },
            inverseOnSurface = pair { it.inverseOnSurface },
            inverseSurface = pair { it.inverseSurface },
            inversePrimary = pair { it.inversePrimary },
            // The page the app's cards lie on, so a widget looks like a piece of it.
            widgetBackground = pair { it.background },
        )
    }
}
