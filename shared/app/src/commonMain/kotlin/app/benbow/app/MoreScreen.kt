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
import app.benbow.app.resources.appearance
import app.benbow.app.resources.export
import app.benbow.app.resources.gentle
import app.benbow.app.resources.lock
import app.benbow.app.resources.lock_choices
import app.benbow.app.resources.more_export
import app.benbow.app.resources.more_gentle
import app.benbow.app.resources.more_lock_off
import app.benbow.app.resources.more_lock_on
import app.benbow.app.resources.more_reminders
import app.benbow.app.resources.more_search
import app.benbow.app.resources.more_shortcuts
import app.benbow.app.resources.more_shortcuts_web
import app.benbow.app.resources.more_tagline
import app.benbow.app.resources.more_tags
import app.benbow.app.resources.more_trash
import app.benbow.app.resources.palette_names
import app.benbow.app.resources.reminders
import app.benbow.app.resources.server
import app.benbow.app.resources.shortcuts
import app.benbow.app.resources.speech_more_missing
import app.benbow.app.resources.speech_more_ready
import app.benbow.app.resources.speech_title
import app.benbow.app.resources.tab_search
import app.benbow.app.resources.tags
import app.benbow.app.resources.trash
import app.benbow.client.SpeechModel
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** The last tab (Mihon-style "More"): the app's name, then everything that doesn't need a tab, each with a line of what it is. */
@Composable
internal fun MoreScreen(services: AppServices, nav: Navigator) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        MoreList(services, nav)
    }
}

@Composable
private fun MoreList(services: AppServices, nav: Navigator) {
    val status by services.sync.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark()
            Text(
                stringResource(Res.string.more_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Поиск gave its tab to «Записки» for now (ADR-040).
        MoreItem(Icons.Search, stringResource(Res.string.tab_search), stringResource(Res.string.more_search)) { nav.open(Route.Search) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val look = syncLook(status)
        MoreItem(Icons.Server, stringResource(Res.string.server), look?.label, look?.tone) { nav.open(Route.Server) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val appearance by services.appearance.collectAsStateWithLifecycle()
        MoreItem(
            Icons.Palette,
            stringResource(Res.string.appearance),
            stringArrayResource(Res.array.palette_names).getOrNull(appearance.palette.ordinal),
        ) { nav.open(Route.Appearance) }
        MoreItem(Icons.Bell, stringResource(Res.string.reminders), stringResource(Res.string.more_reminders)) {
            nav.open(Route.Reminders())
        }
        MoreItem(Icons.Label, stringResource(Res.string.tags), stringResource(Res.string.more_tags)) { nav.open(Route.Tags) }
        MoreItem(Icons.Leaf, stringResource(Res.string.gentle), stringResource(Res.string.more_gentle)) { nav.open(Route.Gentle) }
        MoreItem(Icons.Delete, stringResource(Res.string.trash), stringResource(Res.string.more_trash)) { nav.open(Route.Trash) }
        MoreItem(Icons.Download, stringResource(Res.string.export), stringResource(Res.string.more_export)) { nav.open(Route.Export) }
        if (services.platform == "desktop" || services.platform == "web") {
            MoreItem(
                Icons.Keyboard,
                stringResource(Res.string.shortcuts),
                stringResource(
                    if (services.platform == "web") Res.string.more_shortcuts_web else Res.string.more_shortcuts,
                ),
            ) {
                nav.open(Route.Shortcuts)
            }
        }
        services.transcriptions?.let { transcriptions ->
            val model by transcriptions.model.state.collectAsStateWithLifecycle()
            val ready = model == SpeechModel.State.Ready
            MoreItem(
                Icons.Transcribe,
                stringResource(Res.string.speech_title),
                stringResource(if (ready) Res.string.speech_more_ready else Res.string.speech_more_missing),
            ) { nav.open(Route.Speech) }
        }
        val lockMinutes by services.lock.minutes.collectAsStateWithLifecycle()
        val lockLabel = lockMinutes?.let { m -> stringArrayResource(Res.array.lock_choices).getOrNull(AppLock.CHOICES.indexOf(m)) }
        MoreItem(
            Icons.Lock,
            stringResource(Res.string.lock),
            lockLabel?.let { stringResource(Res.string.more_lock_on, it) } ?: stringResource(Res.string.more_lock_off),
        ) { nav.open(Route.Lock) }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String?, tone: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().hand().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
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
