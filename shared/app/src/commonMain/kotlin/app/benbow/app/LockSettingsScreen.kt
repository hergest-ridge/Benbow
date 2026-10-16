package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.lock
import app.benbow.app.resources.lock_after
import app.benbow.app.resources.lock_choices
import app.benbow.app.resources.lock_desktop
import app.benbow.app.resources.lock_enable
import app.benbow.app.resources.lock_note
import app.benbow.app.resources.lock_prompt
import app.benbow.app.resources.lock_unavailable
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** "Ещё → Блокировка": on or off, and how long the app may be away before it locks again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LockSettingsScreen(services: AppServices, nav: Navigator) {
    val minutes by services.lock.minutes.collectAsStateWithLifecycle()
    val auth = rememberDeviceAuth()
    val prompt = stringResource(Res.string.lock_prompt)
    val labels = stringArrayResource(Res.array.lock_choices)
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.lock)) })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val usable = auth != null && auth.available()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.lock_enable),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = minutes != null,
                            enabled = usable || minutes != null,
                            onCheckedChange = { on ->
                                // Turning it on checks the owner once, so it's known to work before it ever locks.
                                if (!on) {
                                    services.lock.set(null)
                                } else {
                                    auth?.authenticate(prompt) { ok ->
                                        if (ok) services.lock.set(AppLock.CHOICES.first())
                                    }
                                }
                            },
                            modifier = Modifier.hand(usable || minutes != null),
                        )
                    }
                    if (minutes != null) {
                        Text(stringResource(Res.string.lock_after), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppLock.CHOICES.forEachIndexed { i, choice ->
                                FilterChip(minutes == choice, {
                                    services.lock.set(choice)
                                }, { Text(labels.getOrElse(i) { "$choice" }) }, modifier = Modifier.hand())
                            }
                        }
                    }
                    val note = when {
                        auth == null -> Res.string.lock_desktop
                        !auth.available() -> Res.string.lock_unavailable
                        else -> Res.string.lock_note
                    }
                    Text(
                        stringResource(note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
