package app.benbow.ui.editor

import androidx.compose.runtime.Composable

// TODO(web polish, Phase 6): check what the browser IME bridge exposes.
@Composable
internal actual fun EditorImeBridge(content: @Composable () -> Unit) = content()
