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
import app.benbow.core.text.Links
import java.io.File
import java.net.URI

/**
 * Files dragged from Explorer onto the entry: each is copied into the media folder and attached, like a picked one. A
 * link dragged from a browser becomes a card (ADR-038).
 */
@OptIn(ExperimentalComposeUiApi::class)
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
    val target = remember(services) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)

            override fun onExited(event: DragAndDropEvent) = hover(false)

            override fun onEnded(event: DragAndDropEvent) = hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val dropped = event.dragData()
                if (dropped is DragData.Text) {
                    val href = Links.parse(dropped.readText().lineSequence().firstOrNull().orEmpty()) ?: return false
                    link(href)
                    return true
                }
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                    .mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
                    .filter { it.isFile }
                files.forEach { file -> services.attach({ entry() }, { file.inputStream() }) { desktopProbe(file) } }
                return files.isNotEmpty()
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = {
        it.dragData().let { d -> d is DragData.FilesList || d is DragData.Text }
    }, target = target)
}
