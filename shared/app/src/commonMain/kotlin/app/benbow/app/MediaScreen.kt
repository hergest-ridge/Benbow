package app.benbow.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.media_remove
import app.benbow.app.resources.media_remove_confirm
import app.benbow.app.resources.media_unavailable
import app.benbow.app.resources.server_cancel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** One attachment full screen: zoomable picture, or a player for video and sound. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaScreen(services: AppServices, nav: Navigator, route: Route.Media) {
    val entry by services.repo.entry(route.entryId).collectAsStateWithLifecycle(null)
    val attachment = entry?.attachments?.firstOrNull { it.meta.id == route.attachmentId }
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                navigationIcon = { BackButton(nav::back) },
                title = {
                    Text(attachment?.meta?.capturedAt?.let { dateTimeLabel(it) }.orEmpty(), style = MaterialTheme.typography.titleSmall)
                },
                actions = {
                    if (attachment != null) IconButton({ confirm = true }) { Icon(Icons.Delete, stringResource(Res.string.media_remove)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val meta = attachment?.meta ?: return@Box
            when (val load = rememberMediaFile(services.media, meta.sha256).value) {
                MediaLoad.Loading -> Loading()

                is MediaLoad.Unavailable -> Text(
                    stringResource(Res.string.media_unavailable, load.reason),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )

                is MediaLoad.Ready -> if (meta.kind == "image") ZoomableImage(load.file) else MediaPlayer(load.file, meta.mime)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(Res.string.media_remove_confirm)) },
            confirmButton = {
                TextButton({
                    confirm = false
                    services.scope.launch { services.repo.removeAttachment(route.entryId, route.attachmentId) }
                    nav.back()
                }) { Text(stringResource(Res.string.media_remove)) }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(Res.string.server_cancel)) } },
        )
    }
}
