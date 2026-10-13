package app.benbow.app

import androidx.compose.runtime.Composable
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

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
