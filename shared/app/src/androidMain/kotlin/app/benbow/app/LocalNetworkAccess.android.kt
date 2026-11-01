package app.benbow.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

private val open = LocalNetworkAccess(missing = false) { it() }

@Composable
internal actual fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return open
    val context = LocalContext.current
    fun granted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    var missing by remember { mutableStateOf(!granted()) }
    // Refused for good: the system shows its question no more, only the app's settings can open the network.
    var refused by remember { mutableStateOf(false) }
    var then by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        missing = !ok
        if (ok) then?.invoke()
        then = null
        refused = !ok && context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK) == false
    }
    // Back from the settings, or the group granted along with another of its permissions.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { missing = !granted() }
    return LocalNetworkAccess(missing) { next ->
        if (refused) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } else {
            then = next
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}
