package app.benbow.app

import androidx.compose.runtime.Composable
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.OutputStream

@Composable
internal actual fun rememberCopy(): (String) -> Boolean = { text ->
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    false
}

/** No share sheet on desktop: the text goes to the clipboard. */
@Composable
internal actual fun rememberShare(): (String) -> Boolean = { text ->
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    false
}

/** The system's open dialog; on Windows the pattern in the name field filters, elsewhere the name filter. */
@Composable
internal actual fun rememberOpenFile(mimes: List<String>, onPicked: (PickedFile?) -> Unit): () -> Unit = {
    val dialog = FileDialog(null as Frame?, null, FileDialog.LOAD).apply {
        file = "*.ics;*.zip"
        setFilenameFilter { _, name -> name.endsWith(".ics", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true) }
        isVisible = true // modal
    }
    val chosen = dialog.file?.let { File(dialog.directory, it) }
    onPicked(chosen?.let { file -> PickedFile(file.name) { file.inputStream() } })
}

@Composable
internal actual fun rememberSaveFile(mime: String, onPicked: (open: (() -> OutputStream)?) -> Unit): (String) -> Unit = { name ->
    val dialog = FileDialog(null as Frame?, name, FileDialog.SAVE).apply {
        file = name
        isVisible = true // modal
    }
    val chosen = dialog.file?.let { File(dialog.directory, it) }
    onPicked(chosen?.let { file -> { file.outputStream() } })
}
