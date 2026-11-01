package app.benbow.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import app.benbow.core.text.Links
import app.benbow.ui.theme.hex
import org.jetbrains.compose.resources.stringResource

// The browser's side of the app's platform pieces (ADR-029).

internal actual val pageServer: String? = pageOrigin()

/** The browser has no system palette to borrow. */
@Composable
internal actual fun rememberSystemColors(dark: Boolean): ColorScheme? = null

/** The browser's `prefers-contrast: more`, read once: the highest level; else 0. */
@Composable
internal actual fun rememberSystemContrast(): Double = remember { if (prefersMoreContrast()) 1.0 else 0.0 }

private fun prefersMoreContrast(): Boolean = js("window.matchMedia('(prefers-contrast: more)').matches")

/** The browser's reduced motion (prefers-reduced-motion), read once. */
@Composable
internal actual fun reducedMotion(): Boolean = remember { prefersReducedMotion() }

private fun prefersReducedMotion(): Boolean = js("window.matchMedia('(prefers-reduced-motion: reduce)').matches")

/** No owner check in the browser (for now). */
@Composable
internal actual fun rememberDeviceAuth(): DeviceAuth? = null

/** The browser asks whether this site may show notifications (for reminders while the tab is open). */
@Composable
internal actual fun rememberNotificationPermission(): () -> Unit = ::askForNotifications

@Composable
internal actual fun rememberLocalNetworkAccess(): LocalNetworkAccess = LocalNetworkAccess(missing = false) { it() }

/** Prints through a new tab: the browser's Print there also saves a PDF. */
@Composable
internal actual fun rememberPrint(): (PrintPage) -> Unit = { page -> openHtml(page.html) }

/** The browser's file dialog: the chosen files are attached like picked ones on the other platforms. */
@Composable
internal actual fun MediaBar(
    services: AppServices,
    entryId: suspend () -> String,
    dictation: DictationTarget?,
    modifier: Modifier,
    tools: @Composable RowScope.() -> Unit,
) {
    val title = stringResource(Res.string.media_file)
    Row(modifier.padding(horizontal = 4.dp)) {
        IconButton({
            pickFiles("", multiple = true) { files -> forEachItem(files) { services.attachBrowserFile(entryId, it) } }
        }, modifier = Modifier.hand()) { Icon(Icons.Attach, title) }
        tools()
    }
}

/** No dictation in the browser (ADR-029). */
@Composable
internal actual fun DictationKey(services: AppServices, target: DictationTarget, modifier: Modifier) = Unit

/**
 * Files dropped on the page or pasted while the entry is open are attached, like files dragged from Explorer into the
 * desktop app. Pasted text stays with the editor.
 */
@Composable
internal actual fun Modifier.attachOnDrop(
    services: AppServices,
    entryId: suspend () -> String,
    onHover: (Boolean) -> Unit,
    onLink: (String) -> Unit,
): Modifier {
    val hover by rememberUpdatedState(onHover)
    val entry by rememberUpdatedState(entryId)
    val link by rememberUpdatedState(onLink)
    DisposableEffect(services) {
        val listeners = listenForFiles(
            onHover = { hover(it) },
            onFiles = { files -> forEachItem(files) { services.attachBrowserFile({ entry() }, it) } },
            onLink = { href -> Links.parse(href)?.let(link) },
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

internal actual val inlineVideos: Boolean = true

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun InlineVideo(file: LocalFile, crop: Boolean, modifier: Modifier) {
    val url = remember(file) { file.uri() }
    WebElementView(factory = { inlineVideoElement(url, crop) }, modifier = modifier, onRelease = { pauseMedia(it) })
}

/** The browser's bar and the page under the app take the theme's page, and the next loading page (`theme.js`) the page, the accent and the mode. */
@Composable
internal actual fun ThemeOutside(scheme: ColorScheme, dark: Boolean) {
    val page = scheme.background.hex()
    val accent = scheme.primary.hex()
    val quiet = scheme.onSurfaceVariant.hex()
    LaunchedEffect(page, accent, quiet, dark) { tellPage(page, accent, quiet, dark) }
}

private fun tellPage(page: String, accent: String, quiet: String, dark: Boolean): Unit = js(
    """{
    try { localStorage.setItem('benbow.theme', JSON.stringify({ page: page, accent: accent, quiet: quiet, dark: dark })); } catch (e) {}
    document.querySelectorAll('meta[name=theme-color]').forEach(function (meta) { meta.setAttribute('content', page); });
    var root = document.documentElement;
    root.style.setProperty('--page', page);
    root.style.setProperty('--accent', accent);
    root.style.setProperty('--quiet', quiet);
    root.style.colorScheme = dark ? 'dark' : 'light';
}""",
)
