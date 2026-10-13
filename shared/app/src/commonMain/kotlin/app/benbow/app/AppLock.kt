package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.lock_closed
import app.benbow.app.resources.lock_open
import app.benbow.app.resources.lock_prompt
import app.benbow.client.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * The app lock (6.4): a gate in front of the UI, opened by the platform's owner check. Per device, never synced.
 * A new process always starts locked (nothing unlocked survives process death); after that it locks again once the
 * app has been away for the chosen minutes. It guards the screen, not the files: those rely on the phone's encryption.
 */
class AppLock internal constructor(private val settings: Settings, private val now: () -> Long) {
    private val _minutes = MutableStateFlow(settings.get(KEY)?.toIntOrNull())

    /** Minutes away before it locks again; null = off. */
    val minutes: StateFlow<Int?> = _minutes

    private val _locked = MutableStateFlow(_minutes.value != null)
    val locked: StateFlow<Boolean> = _locked
    private var leftAt: Long? = null

    /** Turning it on doesn't lock right away; turning it off opens it. */
    fun set(minutes: Int?) {
        if (minutes == null) settings.remove(KEY) else settings.put(KEY, minutes.toString())
        _minutes.value = minutes
        if (minutes == null) _locked.value = false
    }

    fun onLeave() {
        leftAt = now()
    }

    fun onReturn() {
        val minutes = _minutes.value ?: return
        val left = leftAt ?: return
        if (now() - left >= minutes * 60_000L) _locked.value = true
    }

    fun unlock() {
        _locked.value = false
    }

    companion object {
        /** Telegram's choices: away for a minute, five, an hour, five hours. */
        val CHOICES = listOf(1, 5, 60, 300)
        private const val KEY = "lock.minutes"
    }
}

/** The platform's owner check: fingerprint, face or the screen lock on Android; none on desktop yet (null). */
internal interface DeviceAuth {
    /** False when the device has no screen lock at all: then nothing can check the owner, so the app doesn't lock. */
    fun available(): Boolean

    /** Shows the system prompt; [onResult] gets true once the owner is confirmed, false if they cancel. */
    fun authenticate(title: String, onResult: (Boolean) -> Unit)
}

@Composable
internal expect fun rememberDeviceAuth(): DeviceAuth?

/** Covers everything until the owner is confirmed; asks at once. Without any screen lock on the device it just opens. */
@Composable
internal fun LockScreen(auth: DeviceAuth?, onUnlock: () -> Unit) {
    val title = stringResource(Res.string.lock_prompt)
    val open = {
        if (auth == null || !auth.available()) onUnlock() else auth.authenticate(title) { if (it) onUnlock() }
    }
    LaunchedEffect(Unit) { open() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text("Benbow", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(Res.string.lock_closed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(open) { Text(stringResource(Res.string.lock_open)) }
        }
    }
}
