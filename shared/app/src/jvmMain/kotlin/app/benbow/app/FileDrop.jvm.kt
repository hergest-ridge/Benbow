package app.benbow.app

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.io.File
import java.net.URI

/** Files dragged from Explorer onto the entry: each is copied into the media folder and attached, like a picked one. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.attachOnDrop(services: AppServices, entryId: suspend () -> String, onHover: (Boolean) -> Unit): Modifier {
    val hover by rememberUpdatedState(onHover)
    val entry by rememberUpdatedState(entryId)
    val target = remember(services) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)

            override fun onExited(event: DragAndDropEvent) = hover(false)

            override fun onEnded(event: DragAndDropEvent) = hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                    .mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
                    .filter { it.isFile }
                files.forEach { file -> services.attach({ entry() }, { file.inputStream() }) { desktopProbe(file) } }
                return files.isNotEmpty()
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = target)
}
