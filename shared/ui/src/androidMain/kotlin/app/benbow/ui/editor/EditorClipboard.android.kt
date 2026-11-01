package app.benbow.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext

// Android's text toolbar requires the platform Clipboard instance, so LocalClipboard can't be
// wrapped here; the clip is rewritten after the fact instead.
@Composable
internal actual fun EditorClipboardFix(recentlySelected: () -> Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isRecent = rememberUpdatedState(recentlySelected)
    DisposableEffect(context) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            // Only clips we just set are read, so Android shows no "pasted from clipboard" toast.
            if (!isRecent.value()) return@OnPrimaryClipChangedListener
            val clip = manager.primaryClip?.takeIf { it.itemCount > 0 } ?: return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0).text?.toString() ?: return@OnPrimaryClipChangedListener
            if (text.hasDisplayGlyphs()) {
                manager.setPrimaryClip(ClipData.newPlainText(clip.description.label, restoreParagraphBreaks(text)))
            }
        }
        manager.addPrimaryClipChangedListener(listener)
        onDispose { manager.removePrimaryClipChangedListener(listener) }
    }
    content()
}

internal actual fun plainTextClipEntry(text: String): ClipEntry? = ClipEntry(ClipData.newPlainText("Benbow", text))

@Composable
actual fun PasteFallback(enabled: Boolean, onText: (String) -> Unit) = Unit
