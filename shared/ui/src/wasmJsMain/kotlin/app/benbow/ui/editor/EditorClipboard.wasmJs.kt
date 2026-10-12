package app.benbow.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry

// TODO(web polish, Phase 6): wire the browser clipboard; until then web copies keep the display glyphs.
@Composable
internal actual fun EditorClipboardFix(recentlySelected: () -> Boolean, content: @Composable () -> Unit) = content()

internal actual fun plainTextClipEntry(text: String): ClipEntry? = null
