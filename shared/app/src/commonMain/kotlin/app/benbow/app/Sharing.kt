package app.benbow.app

import androidx.compose.runtime.Composable
import java.io.OutputStream

/** Puts text on the clipboard; true when the system shows its own confirmation (Android 13+), so the app needn't. */
@Composable
internal expect fun rememberCopy(): (String) -> Boolean

/** Hands text to another app (the share sheet); false where there is none and it was copied instead. */
@Composable
internal expect fun rememberShare(): (String) -> Boolean

/** A "save as" dialog: call it with a suggested file name; [onPicked] gets a way to open the chosen file, or null if cancelled. */
@Composable
internal expect fun rememberSaveFile(mime: String, onPicked: (open: (() -> OutputStream)?) -> Unit): (suggestedName: String) -> Unit
