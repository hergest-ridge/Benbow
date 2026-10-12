package app.benbow.ui.editor

import androidx.compose.runtime.Composable

/** Shows the platform IME the real text instead of the display glyphs (see [PARAGRAPH_BREAK_GLYPH]). */
@Composable
internal expect fun EditorImeBridge(content: @Composable () -> Unit)
