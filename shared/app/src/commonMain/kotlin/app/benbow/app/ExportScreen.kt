package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.export
import app.benbow.app.resources.export_archive_text
import app.benbow.app.resources.export_archive_title
import app.benbow.app.resources.export_done
import app.benbow.app.resources.export_failed
import app.benbow.app.resources.export_missing
import app.benbow.app.resources.export_progress
import app.benbow.app.resources.export_save
import app.benbow.app.resources.export_with_media
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Export: the whole journal as one ZIP that opens without the app (data-safety.md#backups). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportScreen(services: AppServices, nav: Navigator) {
    val state by services.export.collectAsStateWithLifecycle()
    var withMedia by rememberSaveable { mutableStateOf(true) }
    val saveAs = rememberSaveFile("application/zip") { open -> open?.let { services.exportArchive(it, withMedia) } }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.export)) })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.export_archive_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(Res.string.export_archive_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.export_with_media),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(withMedia, {
                            withMedia = it
                        }, enabled = state !is ExportState.Running, modifier = Modifier.hand(state !is ExportState.Running))
                    }
                    Button({
                        saveAs("benbow-${services.today()}.zip")
                    }, enabled = state !is ExportState.Running, modifier = Modifier.hand(state !is ExportState.Running)) {
                        Text(stringResource(Res.string.export_save))
                    }
                    ExportStatus(state)
                }
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun ExportStatus(state: ExportState) {
    val colors = MaterialTheme.colorScheme
    when (state) {
        ExportState.Idle -> Unit

        is ExportState.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val progress = state.progress
            if (progress == null || progress.total == 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator({ progress.done.toFloat() / progress.total }, Modifier.fillMaxWidth())
                Text(
                    stringResource(Res.string.export_progress, progress.done, progress.total),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        is ExportState.Done -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.export_done, state.report.entries, state.report.files), color = colors.primary)
            val missing = state.report.missing
            if (missing > 0) Text(pluralStringResource(Res.plurals.export_missing, missing, missing), color = colors.error)
        }

        is ExportState.Failed -> Text(stringResource(Res.string.export_failed, state.reason), color = colors.error)
    }
}
