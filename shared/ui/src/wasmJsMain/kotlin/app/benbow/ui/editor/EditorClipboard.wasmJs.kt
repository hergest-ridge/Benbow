@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry

// TODO(web polish, Phase 6): wire the browser clipboard; until then web copies keep the display glyphs.
@Composable
internal actual fun EditorClipboardFix(recentlySelected: () -> Boolean, content: @Composable () -> Unit) = content()

internal actual fun plainTextClipEntry(text: String): ClipEntry? = null

@Composable
actual fun PasteFallback(enabled: Boolean, onText: (String) -> Unit) {
    val paste by rememberUpdatedState(onText)
    DisposableEffect(enabled) {
        val listener = if (enabled) listenForUntakenPastes { paste(it) } else null
        onDispose { listener?.let(::stopListening) }
    }
}

/**
 * Text pastes that reach the page unhandled: heard as they bubble up past every element (a field that took one has
 * prevented its default by then), taken, and handed to [onText].
 */
private fun listenForUntakenPastes(onText: (String) -> Unit): JsAny = js(
    """(() => {
        const f = e => {
            if (e.defaultPrevented) return;
            const text = e.clipboardData && e.clipboardData.getData('text/plain');
            if (!text) return;
            e.preventDefault();
            onText(text);
        };
        document.addEventListener('paste', f);
        return f;
    })()""",
)

private fun stopListening(listener: JsAny): Unit = js("document.removeEventListener('paste', listener)")
