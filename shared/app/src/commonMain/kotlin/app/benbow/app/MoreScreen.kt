package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.export
import app.benbow.app.resources.more_export
import app.benbow.app.resources.more_reminders
import app.benbow.app.resources.more_tagline
import app.benbow.app.resources.more_tags
import app.benbow.app.resources.more_trash
import app.benbow.app.resources.reminders
import app.benbow.app.resources.server
import app.benbow.app.resources.tags
import app.benbow.app.resources.trash
import app.benbow.client.SyncStatus
import org.jetbrains.compose.resources.stringResource

/** The last tab (Mihon-style "More"): the app's name, then everything that doesn't need a tab, each with a line of what it is. */
@Composable
internal fun MoreScreen(services: AppServices, nav: Navigator) {
    val status by services.sync.status.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        MoreList(status, nav)
    }
}

@Composable
private fun MoreList(status: SyncStatus, nav: Navigator) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Benbow", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(Res.string.more_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val look = syncLook(status)
        MoreItem(Icons.Server, stringResource(Res.string.server), look?.label, look?.tone) { nav.open(Route.Server) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MoreItem(Icons.Bell, stringResource(Res.string.reminders), stringResource(Res.string.more_reminders)) {
            nav.open(Route.Reminders())
        }
        MoreItem(Icons.Label, stringResource(Res.string.tags), stringResource(Res.string.more_tags)) { nav.open(Route.Tags) }
        MoreItem(Icons.Delete, stringResource(Res.string.trash), stringResource(Res.string.more_trash)) { nav.open(Route.Trash) }
        MoreItem(Icons.Download, stringResource(Res.string.export), stringResource(Res.string.more_export)) { nav.open(Route.Export) }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String?, tone: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
