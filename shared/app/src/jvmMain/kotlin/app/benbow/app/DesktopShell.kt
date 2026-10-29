package app.benbow.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import app.benbow.app.resources.Res
import app.benbow.app.resources.tray_hint
import app.benbow.app.resources.tray_new_entry
import app.benbow.app.resources.tray_open
import app.benbow.app.resources.tray_quit
import app.benbow.client.Settings
import app.benbow.ui.theme.isDarkColor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment

private const val WINDOW = "desktop.window"
private const val TRAY_HINT = "desktop.tray_hint_shown"

/**
 * The desktop app: one window that hides to the tray when closed, so reminders and sync keep running (quit from the
 * tray menu), with its size and place remembered. Keys go to [shortcutOf] before any screen sees them.
 */
@OptIn(FlowPreview::class)
@Composable
fun ApplicationScope.BenbowDesktop(services: AppServices, tray: TrayState) {
    var visible by remember { mutableStateOf(true) }
    var raise by remember { mutableIntStateOf(0) }
    val settings = services.settings
    val state = rememberSavedWindowState(settings)
    val show: () -> Unit = {
        visible = true
        raise += 1
    }
    val hint = stringResource(Res.string.tray_hint)
    if (isTraySupported) {
        Tray(icon = appIcon(), state = tray, tooltip = "Benbow", onAction = show) {
            Item(stringResource(Res.string.tray_open), onClick = show)
            Item(stringResource(Res.string.tray_new_entry), onClick = {
                show()
                services.launch.value = AppLaunch.NewEntry
            })
            Separator()
            Item(stringResource(Res.string.tray_quit), onClick = ::exitApplication)
        }
    }
    Window(
        onCloseRequest = {
            if (!isTraySupported) {
                exitApplication()
            } else {
                visible = false
                if (settings.get(TRAY_HINT) == null) {
                    tray.sendNotification(Notification("Benbow", hint))
                    settings.put(TRAY_HINT, "1")
                }
            }
        },
        visible = visible,
        state = state,
        title = "Benbow",
        icon = appIcon(),
        onPreviewKeyEvent = { event -> shortcutOf(event)?.let { services.shortcuts.tryEmit(it) } ?: false },
    ) {
        LaunchedEffect(raise) {
            if (raise == 0) return@LaunchedEffect
            state.isMinimized = false
            window.toFront()
            window.requestFocus()
        }
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.placement, state.position, state.size) }.drop(1).debounce(500).collect { save(settings, state) }
        }
        AppTheme(services) {
            // The title bar takes the app's background and text colors, and follows theme changes; so does the window's own
            // ground, which shows for a moment as it grows (ADR-057).
            val caption = MaterialTheme.colorScheme.background
            val text = MaterialTheme.colorScheme.onBackground
            LaunchedEffect(caption, text) {
                window.background = java.awt.Color(caption.toArgb())
                window.contentPane.background = window.background
                repeat(50) {
                    if (WindowsTitleBar.paint(
                            window,
                            caption,
                            text,
                            dark = caption.isDarkColor(),
                        )
                    ) {
                        return@LaunchedEffect
                    } else {
                        delay(20)
                    }
                }
            }
            BenbowApp(services)
        }
    }
}

/** "placement;x;y;width;height" in dp; a place on a monitor that's gone falls back to the system's choice. */
@Composable
private fun rememberSavedWindowState(settings: Settings): WindowState {
    val saved = remember { settings.get(WINDOW)?.split(';') }
    val placement = saved?.getOrNull(0)?.let { name -> WindowPlacement.entries.firstOrNull { it.name == name } } ?: WindowPlacement.Floating
    val x = saved?.getOrNull(1)?.toFloatOrNull()
    val y = saved?.getOrNull(2)?.toFloatOrNull()
    val width = saved?.getOrNull(3)?.toFloatOrNull()?.coerceAtLeast(360f) ?: 1100f
    val height = saved?.getOrNull(4)?.toFloatOrNull()?.coerceAtLeast(480f) ?: 820f
    val onScreen = x != null && y != null && GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .any { it.defaultConfiguration.bounds.contains(x.toInt() + 40, y.toInt() + 20) }
    return rememberWindowState(
        placement = placement,
        position = if (onScreen) WindowPosition(x!!.dp, y!!.dp) else WindowPosition.PlatformDefault,
        size = DpSize(width.dp, height.dp),
    )
}

private fun save(settings: Settings, state: WindowState) {
    if (state.placement != WindowPlacement.Floating) {
        // Keep the floating size and place for when it's restored; only the placement changes.
        val old = settings.get(WINDOW)?.split(';')?.drop(1)?.joinToString(";")
        settings.put(WINDOW, state.placement.name + (old?.let { ";$it" } ?: ""))
        return
    }
    val position = state.position
    val (x, y) = if (position.isSpecified) position.x.value to position.y.value else Float.NaN to Float.NaN
    settings.put(WINDOW, "${state.placement.name};$x;$y;${state.size.width.value};${state.size.height.value}")
}
