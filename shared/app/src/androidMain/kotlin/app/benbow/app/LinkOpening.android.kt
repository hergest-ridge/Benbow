package app.benbow.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/**
 * A web link opens in the browser's Custom Tab as a sheet over the entry (ADR-038): a swipe down and the entry is where
 * it was. Where the browser can't make a sheet it is a full tab, and Back comes home. Mail and phone links go to their apps.
 */
@Composable
internal actual fun rememberOpenUrl(): (String) -> Unit {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { CustomTabs.bind(context.applicationContext) }
    return remember(context, colors) {
        val toolbar = colors.surface.toArgb()
        val dark = colors.background.luminance() < 0.5f
        ({ href: String -> open(context, href, toolbar, dark) })
    }
}

/** The browser started ahead (no page is loaded until a tap), and the session a sheet needs. */
private object CustomTabs {
    var session: CustomTabsSession? = null
    private var bound = false

    fun bind(context: Context) {
        if (bound) return
        val browser = CustomTabsClient.getPackageName(context, null) ?: return
        bound = CustomTabsClient.bindCustomTabsService(
            context,
            browser,
            object : CustomTabsServiceConnection() {
                override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                    client.warmup(0)
                    session = client.newSession(null)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    session = null
                    bound = false
                }
            },
        )
    }
}

private fun open(context: Context, href: String, toolbar: Int, dark: Boolean) {
    val uri = Uri.parse(href)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return
    }
    val activity = context.findActivity()
    val height = (context.resources.displayMetrics.heightPixels * SHEET).toInt()
    val tab = CustomTabsIntent.Builder(CustomTabs.session)
        .setShowTitle(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_ON)
        .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
        .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbar).build())
        .setInitialActivityHeightPx(height, CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
        .setToolbarCornerRadiusDp(16)
        .build()
    try {
        if (activity != null && CustomTabs.session == null) {
            // Without a session the browser makes a sheet only for a caller it knows: one waiting for a result.
            tab.intent.data = uri
            @Suppress("DEPRECATION")
            activity.startActivityForResult(tab.intent, SHEET_REQUEST)
        } else {
            if (activity == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(activity ?: context, uri)
        }
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** How much of the screen the sheet takes at first; it can be pulled up all the way. */
private const val SHEET = 0.9f
private const val SHEET_REQUEST = 3801

@Composable
internal actual fun rememberReadClipboard(): (suspend () -> String?)? {
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(android.content.ClipboardManager::class.java)
        suspend { manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }
    }
}
