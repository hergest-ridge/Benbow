package app.benbow.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
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
