package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.filter_favorites
import app.benbow.app.resources.filter_media
import app.benbow.app.resources.search_empty
import app.benbow.app.resources.search_hint
import app.benbow.client.EntrySummary
import app.benbow.client.SearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.stringResource

internal class SearchViewModel(private val services: AppServices) : ViewModel() {
    val query = MutableStateFlow(SearchQuery())
    val tags = services.repo.tags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<EntrySummary>?> = query.debounce(150).flatMapLatest { services.repo.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun edit(change: (SearchQuery) -> SearchQuery) = query.update(change)
}

/** Everything on the device, instantly and offline: words (as prefixes), a tag, favorites, entries with media. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { SearchViewModel(services) }
    val query by vm.query.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(nav::back) },
                title = {
                    TextField(
                        value = query.text,
                        onValueChange = { text -> vm.edit { it.copy(text = text) } },
                        placeholder = { Text(stringResource(Res.string.search_hint)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(query.favorites, {
                        vm.edit { it.copy(favorites = !it.favorites) }
                    }, { Text("★ " + stringResource(Res.string.filter_favorites)) }, modifier = Modifier.hand())
                }
                item {
                    FilterChip(query.withMedia, {
                        vm.edit { it.copy(withMedia = !it.withMedia) }
                    }, { Text(stringResource(Res.string.filter_media)) }, modifier = Modifier.hand())
                }
                items(tags, key = { it.id }) { tag ->
                    val on = query.tag == tag.id
                    FilterChip(
                        on,
                        { vm.edit { it.copy(tag = if (on) null else tag.id) } },
                        { Text("${tag.name} · ${tag.uses}") },
                        colors = FilterChipDefaults.filterChipColors(containerColor = tagColor(tag.id)),
                        modifier = Modifier.hand(),
                    )
                }
            }
            val found = results ?: return@Column
            if (found.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(found, key = { it.id }) { entry ->
                        EntryCard(entry, onClick = { nav.open(Route.Editor(entry.id, entry.day)) }, media = services.media, showDay = true)
                    }
                }
            }
        }
    }
}
