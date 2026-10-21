package app.benbow.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.update_allow
import app.benbow.app.resources.update_available
import app.benbow.app.resources.update_busy_dictating
import app.benbow.app.resources.update_busy_playing
import app.benbow.app.resources.update_busy_recording
import app.benbow.app.resources.update_failed
import app.benbow.app.resources.update_fetching
import app.benbow.app.resources.update_install
import app.benbow.app.resources.update_installing
import app.benbow.app.resources.update_restart
import app.benbow.app.resources.update_title
import org.jetbrains.compose.resources.stringResource

/** How this app's update reads right now: a title, a line under it, its colour, and what a tap does (null: nothing). */
internal class UpdateLook(val title: String, val subtitle: String, val tone: Color?, val action: (() -> Unit)?)

/**
 * The update's look (ADR-042, U2): live while it downloads; «Обновить» only when nothing is recorded, dictated or played
 * and Android lets this app install; else what to do first. Null when there is nothing to update.
 */
@Composable
internal fun updateLook(services: AppServices): UpdateLook? {
    val state by services.updates.state.collectAsStateWithLifecycle()
    val recording by VoiceRecording.active.collectAsStateWithLifecycle()
    val player by services.player.state.collectAsStateWithLifecycle()
    val dictation = services.dictations?.active?.collectAsStateWithLifecycle()
    // Back from Android's setting: whether installs are allowed now.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val colors = MaterialTheme.colorScheme
    val mb = { bytes: Long -> (bytes / 1_048_576).toString() }
    return when (val s = state) {
        UpdateState.None -> null

        is UpdateState.Fetching -> UpdateLook(
            stringResource(Res.string.update_title, s.app.versionName),
            stringResource(Res.string.update_fetching, mb(s.done), mb(s.total)),
            null,
            null,
        )

        is UpdateState.Ready -> {
            val title = stringResource(Res.string.update_available, s.app.versionName)
            val busy = when {
                recording -> stringResource(Res.string.update_busy_recording)
                dictation?.value != null -> stringResource(Res.string.update_busy_dictating)
                player?.playing == true -> stringResource(Res.string.update_busy_playing)
                else -> null
            }
            when {
                busy != null -> UpdateLook(title, busy, null, null)

                resumed >= 0 && !services.updates.canInstall() ->
                    UpdateLook(title, stringResource(Res.string.update_allow), colors.primary, services.updates::allowInstalls)

                else -> UpdateLook(title, stringResource(Res.string.update_install), colors.primary) { services.updates.install() }
            }
        }

        is UpdateState.Installing -> UpdateLook(
            stringResource(Res.string.update_installing, s.app.versionName),
            stringResource(Res.string.update_restart),
            null,
            null,
        )

        is UpdateState.Failed -> UpdateLook(
            stringResource(Res.string.update_title, s.app.versionName),
            stringResource(Res.string.update_failed, s.reason),
            colors.error,
            services.updates::check,
        )
    }
}

/** «Ещё»'s line for this app's update, with its divider; nothing when there is none. */
@Composable
internal fun UpdateItem(services: AppServices) {
    val look = updateLook(services) ?: return
    Column {
        MoreItem(Icons.Download, look.title, look.subtitle, look.tone) { look.action?.invoke() }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
