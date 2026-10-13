package app.benbow.app

import androidx.compose.runtime.Composable

/** Puts text on the clipboard; true when the system shows its own confirmation (Android 13+), so the app needn't. */
@Composable
internal expect fun rememberCopy(): (String) -> Boolean

/** Hands text to another app (the share sheet); false where there is none and it was copied instead. */
@Composable
internal expect fun rememberShare(): (String) -> Boolean
