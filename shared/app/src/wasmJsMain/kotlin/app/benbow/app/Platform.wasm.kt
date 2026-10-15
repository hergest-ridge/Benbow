package app.benbow.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.WebElementView
import app.benbow.app.resources.Res
import app.benbow.app.resources.media_file
import app.benbow.client.LocalFile
import app.benbow.client.uri
import org.jetbrains.compose.resources.stringResource

// The browser's side of the app's platform pieces (ADR-029).

internal actual val pageServer: String? = pageOrigin()

/** The browser has no system palette to borrow. */
@Composable
internal actual fun rememberSystemColors(dark: Boolean): ColorScheme? = null

/** No owner check in the browser (for now). */
@Composable
internal actual fun rememberDeviceAuth(): DeviceAuth? = null

/** The browser asks whether this site may show notifications (for reminders while the tab is open). */
@Composable
internal actual fun rememberNotificationPermission(): () -> Unit = ::askForNotifications

/** Prints through a new tab: the browser's Print there also saves a PDF. */
@Composable
internal actual fun rememberPrint(): (PrintPage) -> Unit = { page -> openHtml(page.html) }

/** The browser's file dialog: the chosen files are attached like picked ones on the other platforms. */
@Composable
internal actual fun MediaBar(services: AppServices, entryId: suspend () -> String, modifier: Modifier) {
    val title = stringResource(Res.string.media_file)
    Row(modifier.padding(horizontal = 4.dp)) {
        IconButton({
            pickFiles("", multiple = true) { files -> forEachItem(files) { services.attachBrowserFile(entryId, it) } }
        }) { Icon(Icons.Attach, title) }
    }
}

/**
 * Files dropped on the page or pasted while the entry is open are attached, like files dragged from Explorer into the
 * desktop app. Pasted text stays with the editor.
 */
@Composable
internal actual fun Modifier.attachOnDrop(services: AppServices, entryId: suspend () -> String, onHover: (Boolean) -> Unit): Modifier {
    val hover by rememberUpdatedState(onHover)
    val entry by rememberUpdatedState(entryId)
    DisposableEffect(services) {
        val listeners = listenForFiles(
            onHover = { hover(it) },
            onFiles = { files -> forEachItem(files) { services.attachBrowserFile({ entry() }, it) } },
        )
        onDispose { stopFileListeners(listeners) }
    }
    return this
}

/**
 * The browser's own player over the file's blob, with its controls (seek, volume, full screen). It is an HTML element
 * above the app's canvas, so it leaves the viewer's top bar (back, delete) free.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun MediaPlayer(file: LocalFile, mime: String, modifier: Modifier) {
    val url = remember(file) { file.uri() }
    WebElementView(
        factory = { mediaElement(if (mime.startsWith("audio/")) "audio" else "video", url) },
        modifier = modifier.fillMaxSize().padding(top = 56.dp),
        onRelease = { pauseMedia(it) },
    )
}

@Composable
internal actual fun rememberAudioPlayback(): AudioPlayback {
    val playback = remember { BrowserAudio() }
    DisposableEffect(playback) { onDispose { playback.release() } }
    return playback
}
