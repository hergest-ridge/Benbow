package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.io.File

/** Desktop has no in-app audio yet: a play button hands the file (with an extension the system knows) to its player. */
@Composable
internal actual fun rememberAudioPlayback(): AudioPlayback = remember {
    object : AudioPlayback {
        override val now: StateFlow<AudioNow?> = MutableStateFlow(null)

        override fun toggle(id: String, file: File, mime: String) {
            val ext = mime.substringAfter('/').substringBefore(';').let {
                if (it == "mpeg") {
                    "mp3"
                } else if (it == "mp4") {
                    "m4a"
                } else {
                    it
                }
            }
            val copy = File(System.getProperty("java.io.tmpdir"), "benbow-${file.name.take(12)}.$ext")
            if (!copy.exists()) file.copyTo(copy)
            runCatching { Desktop.getDesktop().open(copy) }
        }

        override fun seek(positionMs: Long) = Unit
    }
}
