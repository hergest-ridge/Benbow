package app.benbow.ui.editor

import androidx.compose.runtime.Composable

// TODO(desktop polish): check how AWT input methods read context; plain typing is unaffected.
@Composable
internal actual fun EditorImeBridge(content: @Composable () -> Unit) = content()
