package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.net.URI

/** Links open in the system's browser (mail links in its mail app), off the UI thread: the call can take a moment. */
@Composable
internal actual fun rememberOpenUrl(): (String) -> Unit = remember {
    { href ->
        Thread {
            runCatching {
                val desktop = Desktop.getDesktop()
                val uri = URI(asciiUrl(href))
                if (uri.scheme.equals("mailto", ignoreCase = true)) desktop.mail(uri) else desktop.browse(uri)
            }
        }.apply { isDaemon = true }.start()
    }
}

@Composable
internal actual fun rememberReadClipboard(): (suspend () -> String?)? = remember {
    suspend {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()
    }
}
