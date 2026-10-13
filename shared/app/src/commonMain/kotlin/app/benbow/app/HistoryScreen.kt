package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.entry_broken
import app.benbow.app.resources.history
import app.benbow.app.resources.history_empty
import app.benbow.app.resources.history_offline
import app.benbow.app.resources.retry
import app.benbow.app.resources.revision_conflict
import app.benbow.app.resources.revision_current
import app.benbow.app.resources.revision_merge
import app.benbow.app.resources.revision_restore
import app.benbow.app.resources.revision_title
import app.benbow.core.api.RevisionInfo
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.toRichText
import app.benbow.ui.editor.BenbowEditor
import app.benbow.ui.editor.EditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Loading, loaded or failed: something fetched from the server. */
internal sealed interface Remote<out T> {
    data object Loading : Remote<Nothing>

    data class Loaded<T>(val value: T) : Remote<T>

    data class Failed(val reason: String) : Remote<Nothing>
}

/** Fetches [load] into a state the screen observes; [reload] retries. */
internal class RemoteViewModel<T>(private val load: suspend () -> T) : ViewModel() {
    var state by mutableStateOf<Remote<T>>(Remote.Loading)
        private set

    init {
        reload()
    }

    fun reload() {
        state = Remote.Loading
        viewModelScope.launch {
            state = try {
                Remote.Loaded(load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Remote.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }
}

@Composable
private fun <T> RemoteContent(vm: RemoteViewModel<T>, content: @Composable (T) -> Unit) {
    when (val state = vm.state) {
        Remote.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        is Remote.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.history_offline, state.reason), style = MaterialTheme.typography.bodyLarge)
            TextButton(vm::reload) { Text(stringResource(Res.string.retry)) }
        }

        is Remote.Loaded -> content(state.value)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryScreen(services: AppServices, nav: Navigator, route: Route.History) {
    val vm = viewModel { RemoteViewModel { services.history.revisions(route.entryId) } }
    val entry by services.repo.entry(route.entryId).collectAsStateWithLifecycle(null)
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.history)) })
    }) { padding ->
        Box(Modifier.padding(padding)) {
            RemoteContent(vm) { revisions ->
                if (revisions.isEmpty()) {
                    Text(stringResource(Res.string.history_empty), modifier = Modifier.padding(24.dp))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(revisions, key = { it.id }) { revision ->
                            RevisionRow(
                                revision,
                                current = revision.id == entry?.headRevision,
                                conflict =
                                revision.id in entry?.conflictHeads.orEmpty(),
                            ) {
                                nav.open(Route.Revision(route.entryId, revision.id, revision.createdAt))
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RevisionRow(revision: RevisionInfo, current: Boolean, conflict: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(dateTimeLabel(revision.createdAt), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (revision.origin ==
            "merge"
        ) {
            Text(
                stringResource(Res.string.revision_merge),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        if (conflict) Text(stringResource(Res.string.revision_conflict), style = MaterialTheme.typography.labelMedium, color = colors.error)
        if (current) Text(stringResource(Res.string.revision_current), style = MaterialTheme.typography.labelMedium, color = colors.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RevisionScreen(services: AppServices, nav: Navigator, route: Route.Revision) {
    val vm = viewModel { RemoteViewModel { services.history.body(route.revisionId) } }
    val entry by services.repo.entry(route.entryId).collectAsStateWithLifecycle(null)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(nav::back) },
                title = {
                    Text(
                        stringResource(Res.string.revision_title, dateTimeLabel(route.createdAt)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
            )
        },
        bottomBar = {
            val body = (vm.state as? Remote.Loaded)?.value
            val needed = entry?.let { it.headRevision != route.revisionId || it.conflictHeads.isNotEmpty() } == true
            if (body != null && needed) {
                Button(
                    onClick = {
                        services.scope.launch { services.repo.restoreVersion(route.entryId, body) }
                        nav.backTo { it is Route.Editor || it is Route.Home }
                    },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text(stringResource(Res.string.revision_restore)) }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            RemoteContent(vm) { body ->
                val state = remember(body) { runCatching { EditorState(DocumentCodec.decode(body).toRichText()) }.getOrNull() }
                if (state == null) {
                    Text(
                        stringResource(Res.string.entry_broken),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                    return@RemoteContent
                }
                BenbowEditor(
                    state = state,
                    readOnly = true,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}
