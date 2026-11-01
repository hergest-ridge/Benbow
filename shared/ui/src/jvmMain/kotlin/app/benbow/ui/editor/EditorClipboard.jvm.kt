@file:OptIn(ExperimentalComposeUiApi::class) // desktop ClipEntry / asAwtTransferable

package app.benbow.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

// Desktop text fields reach the AWT clipboard through nativeClipboard, which the wrapper delegates,
// so wrapping LocalClipboard is safe here.
@Composable
internal actual fun EditorClipboardFix(recentlySelected: () -> Boolean, content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val fixing = remember(clipboard) { GlyphFixingClipboard(clipboard) }
    CompositionLocalProvider(LocalClipboard provides fixing, content = content)
}

private class GlyphFixingClipboard(private val delegate: Clipboard) : Clipboard by delegate {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.plainText()
        delegate.setClipEntry(if (text != null && text.hasDisplayGlyphs()) plainTextClipEntry(restoreParagraphBreaks(text)) else clipEntry)
    }
}

private fun ClipEntry.plainText(): String? {
    val transferable = asAwtTransferable ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    return transferable.getTransferData(DataFlavor.stringFlavor) as? String
}

internal actual fun plainTextClipEntry(text: String): ClipEntry? = ClipEntry(StringSelection(text))

@Composable
actual fun PasteFallback(enabled: Boolean, onText: (String) -> Unit) = Unit
