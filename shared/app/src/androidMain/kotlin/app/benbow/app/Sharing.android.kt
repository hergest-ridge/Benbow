package app.benbow.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.OutputStream

@Composable
internal actual fun rememberCopy(): (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { text ->
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Benbow", text))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        }
    }
}

@Composable
internal actual fun rememberShare(): (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { text ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    }
}

@Composable
internal actual fun rememberOpenFile(mimes: List<String>, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        callback(
            uri?.let {
                PickedFile(context.displayName(it) ?: "calendar.ics") {
                    context.contentResolver.openInputStream(it)
                        ?: error("Can't read $it")
                }
            },
        )
    }
    return { launcher.launch(mimes.toTypedArray()) }
}

/** The name the providing app gives a content URI (a file's name in Downloads, Drive, ...). */
fun Context.displayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
    if (it.moveToFirst()) it.getString(0) else null
}

@Composable
internal actual fun rememberSaveFile(mime: String, onPicked: (open: (() -> OutputStream)?) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mime),
    ) { uri ->
        callback(uri?.let { { context.contentResolver.openOutputStream(it, "w") ?: error("Can't write $it") } })
    }
    return { name -> launcher.launch(name) }
}
