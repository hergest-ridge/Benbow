package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.speech_about
import app.benbow.app.resources.speech_all
import app.benbow.app.resources.speech_all_done
import app.benbow.app.resources.speech_auto
import app.benbow.app.resources.speech_auto_note
import app.benbow.app.resources.speech_check_again
import app.benbow.app.resources.speech_delete
import app.benbow.app.resources.speech_download
import app.benbow.app.resources.speech_downloading
import app.benbow.app.resources.speech_error
import app.benbow.app.resources.speech_need_server
import app.benbow.app.resources.speech_not_on_server
import app.benbow.app.resources.speech_queue
import app.benbow.app.resources.speech_ready
import app.benbow.app.resources.speech_retry
import app.benbow.app.resources.speech_server_old
import app.benbow.app.resources.speech_title
import app.benbow.client.IoDispatcher
import app.benbow.client.SpeechModel
import app.benbow.core.api.SpeechModelSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** "Ещё → Расшифровка голосовых" (7.6): the model from the paired server, new voice notes on their own, the rest at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechScreen(services: AppServices, nav: Navigator) {
    val transcriptions = services.transcriptions ?: return
    val state by transcriptions.model.state.collectAsStateWithLifecycle()
    val auto by transcriptions.auto.collectAsStateWithLifecycle()
    val running by transcriptions.running.collectAsStateWithLifecycle()
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(running, state) { left = withContext(IoDispatcher) { transcriptions.untranscribedCount() } }
    val paired = remember { services.settings.connection != null }
    val fetch = { services.scope.launch { transcriptions.model.fetch() } }
    val mb = { bytes: Long -> ((bytes + 500_000) / 1_000_000).toString() }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.speech_title)) })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Card {
                Note(stringResource(Res.string.speech_about, mb(SpeechModelSpec.totalSize)))
                when (val s = state) {
                    SpeechModel.State.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.speech_ready),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton({ transcriptions.model.delete() }) { Text(stringResource(Res.string.speech_delete)) }
                    }

                    is SpeechModel.State.Downloading -> {
                        LinearProgressIndicator({ s.done.toFloat() / s.total }, Modifier.fillMaxWidth())
                        Text(
                            stringResource(Res.string.speech_downloading, mb(s.done), mb(s.total)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    SpeechModel.State.ServerTooOld -> {
                        Text(stringResource(Res.string.speech_server_old), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton({ fetch() }) { Text(stringResource(Res.string.speech_check_again)) }
                    }

                    SpeechModel.State.NotOnServer -> {
                        Text(stringResource(Res.string.speech_not_on_server), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton({ fetch() }) { Text(stringResource(Res.string.speech_check_again)) }
                    }

                    SpeechModel.State.Missing -> if (paired) {
                        Button({ fetch() }) { Text(stringResource(Res.string.speech_download, mb(SpeechModelSpec.totalSize))) }
                    } else {
                        Text(stringResource(Res.string.speech_need_server), style = MaterialTheme.typography.bodyMedium)
                    }

                    is SpeechModel.State.Failed -> {
                        Text(
                            stringResource(Res.string.speech_error, s.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton({ fetch() }) { Text(stringResource(Res.string.speech_retry)) }
                    }
                }
            }
            if (state == SpeechModel.State.Ready) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(stringResource(Res.string.speech_auto), style = MaterialTheme.typography.bodyLarge)
                            Note(stringResource(Res.string.speech_auto_note))
                        }
                        Switch(auto, transcriptions::setAuto)
                    }
                    when {
                        running.isNotEmpty() -> Text(
                            stringResource(Res.string.speech_queue, running.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        left > 0 -> OutlinedButton({ transcriptions.transcribeAll() }) { Text(stringResource(Res.string.speech_all, left)) }

                        else -> Note(stringResource(Res.string.speech_all_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
