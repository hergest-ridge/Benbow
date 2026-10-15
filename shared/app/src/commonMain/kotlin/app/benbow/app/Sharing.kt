package app.benbow.app

import androidx.compose.runtime.Composable
import java.io.InputStream
import java.io.OutputStream

/** Puts text on the clipboard; true when the system shows its own confirmation (Android 13+), so the app needn't. */
@Composable
internal expect fun rememberCopy(): (String) -> Boolean

/** Hands text to another app (the share sheet); false where there is none and it was copied instead. */
@Composable
internal expect fun rememberShare(): (String) -> Boolean

/** A file the user picked: its name and a way to read it (on a background thread). */
internal class PickedFile(val name: String, val open: () -> InputStream)

/** An "open" dialog for files of [mimes] (MIME types, wildcards allowed); [onPicked] gets the file, or null if cancelled. */
@Composable
internal expect fun rememberOpenFile(mimes: List<String>, onPicked: (PickedFile?) -> Unit): () -> Unit

/** A "save as" dialog: call it with a suggested file name; [onPicked] gets a way to open the chosen file, or null if cancelled. */
@Composable
internal expect fun rememberSaveFile(mime: String, onPicked: (open: (() -> OutputStream)?) -> Unit): (suggestedName: String) -> Unit
