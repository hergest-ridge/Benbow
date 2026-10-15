package app.benbow.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.media_file
import app.benbow.client.LocalFile
import app.benbow.client.byteSourceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// The browser's side of the app's platform pieces (ADR-029). Playback and dropping files come with W3.

internal actual val pageServer: String? = pageOrigin()

/** The browser has no system palette to borrow. */
@Composable
internal actual fun rememberSystemColors(dark: Boolean): ColorScheme? = null

/** No owner check in the browser (for now). */
@Composable
internal actual fun rememberDeviceAuth(): DeviceAuth? = null

@Composable
internal actual fun rememberNotificationPermission(): () -> Unit = {}

/** Prints through a new tab: the browser's Print there also saves a PDF. */
@Composable
internal actual fun rememberPrint(): (PrintPage) -> Unit = { page -> openHtml(page.html) }

/** The browser's file dialog: the chosen files are attached like picked ones on the other platforms. */
@OptIn(ExperimentalEncodingApi::class)
@Composable
internal actual fun MediaBar(services: AppServices, entryId: suspend () -> String, modifier: Modifier) {
    val title = stringResource(Res.string.media_file)
    Row(modifier.padding(horizontal = 4.dp)) {
        IconButton({
            pickFiles("", multiple = true) { name, type, base64, modified ->
                val bytes = Base64.decode(base64)
                services.attach(entryId, { byteSourceOf(bytes) }) {
                    MediaProbe(mime = type, name = name, kind = kindOf(type), capturedAt = modified.toLong())
                }
            }
        }) { Icon(Icons.Attach, title) }
    }
}

@Composable
internal actual fun Modifier.attachOnDrop(services: AppServices, entryId: suspend () -> String, onHover: (Boolean) -> Unit): Modifier = this

/** Not yet in the browser (W3): the file stays available on the other devices. */
@Composable
internal actual fun MediaPlayer(file: LocalFile, mime: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(file.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal actual fun rememberAudioPlayback(): AudioPlayback = remember {
    object : AudioPlayback {
        override val now: StateFlow<AudioNow?> = MutableStateFlow(null)

        override fun toggle(id: String, file: LocalFile, mime: String) = Unit

        override fun seek(positionMs: Long) = Unit
    }
}
