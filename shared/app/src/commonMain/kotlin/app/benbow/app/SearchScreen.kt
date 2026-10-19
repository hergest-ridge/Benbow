package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.benbow.app.resources.search_tag
import app.benbow.app.resources.tag_picker_search
import app.benbow.client.EntrySummary
import app.benbow.client.SearchQuery
import app.benbow.client.TagRef
import app.benbow.client.tags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal class SearchViewModel(private val services: AppServices) : ViewModel() {
    /** Opened with a selection (a tag block on Home): then the keyboard stays down. */
    val asked = services.searchFor.value != null
    val query = MutableStateFlow(services.searchFor.value ?: SearchQuery())
    val tags = services.repo.tags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<EntrySummary>?> = query.debounce(150).flatMapLatest { services.repo.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun edit(change: (SearchQuery) -> SearchQuery) = query.update(change)

    init {
        viewModelScope.launch {
            services.searchFor.filterNotNull().collect { asked ->
                query.value = asked
                services.searchFor.value = null
            }
        }
    }
}

/**
 * Everything on the device, instantly and offline: words (as prefixes), a tag, favorites, entries with media. A tag is
 * picked in the tag picker («Тег…»), or with one tap among the most used ones beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { SearchViewModel(services) }
    val query by vm.query.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!vm.asked) focus.requestFocus() }
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
                    }, {
                        Text(stringResource(Res.string.filter_favorites))
                    }, leadingIcon = { FavoriteMark(description = null) }, modifier = Modifier.hand())
                }
                item {
                    FilterChip(query.withMedia, {
                        vm.edit { it.copy(withMedia = !it.withMedia) }
                    }, { Text(stringResource(Res.string.filter_media)) }, modifier = Modifier.hand())
                }
                item(key = "tag") {
                    val tag = query.tag?.let { id -> tags.firstOrNull { it.id == id } }
                    FilterChip(
                        tag != null,
                        { if (tag != null) vm.edit { it.copy(tag = null) } else picking = true },
                        { Text(tag?.name ?: stringResource(Res.string.search_tag)) },
                        trailingIcon = tag?.let { { Icon(Icons.Close, null, Modifier.size(16.dp)) } },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor =
                            tag?.let { tagColor(it.id) } ?: Color.Unspecified,
                        ),
                        modifier = Modifier.hand(),
                    )
                }
                items(tags.filter { !it.archived && it.id != query.tag }.take(QUICK_TAGS), key = { it.id }) { tag ->
                    FilterChip(
                        false,
                        { vm.edit { it.copy(tag = tag.id) } },
                        { Text("${tag.name} · ${tag.uses}") },
                        colors = FilterChipDefaults.filterChipColors(containerColor = tagColor(tag.id)),
                        modifier = Modifier.hand(),
                    )
                }
            }
            if (picking) {
                TagPicker(
                    services,
                    title = stringResource(Res.string.tag_picker_search),
                    chosen = tags.filter { it.id == query.tag }.map { TagRef(it.id, it.name) },
                    multi = false,
                    onPick = { tag -> vm.edit { it.copy(tag = tag.id) } },
                    onDismiss = { picking = false },
                )
            }
            val found = results ?: return@Column
            if (found.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (query.withMedia) {
                // Entries with media as a grid of their pictures, like the tag block that may have opened it.
                val grid = rememberLazyGridState()
                LazyVerticalGrid(
                    GridCells.Adaptive(112.dp),
                    Modifier.fillMaxSize().pageScrolling(
                        grid,
                        enabled = services.platform != "android",
                        takeFocus = false,
                        home = { grid.scrollToItem(0) },
                        end = { grid.scrollToItem(found.lastIndex) },
                    ),
                    state = grid,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(found, key = { it.id }) { entry ->
                        EntryTile(entry, services.media, Modifier.aspectRatio(1f), onClick = {
                            nav.open(Route.Editor(entry.id, entry.day, readOnly = true))
                        })
                    }
                }
            } else {
                val list = rememberLazyListState()
                LazyColumn(
                    Modifier.fillMaxSize().pageScrolling(
                        list,
                        enabled = services.platform != "android",
                        takeFocus = false,
                        home = { list.toFirst() },
                        end = { list.toLast() },
                    ),
                    state = list,
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(found, key = { it.id }) { entry ->
                        EntryCard(entry, onClick = {
                            nav.open(Route.Editor(entry.id, entry.day, readOnly = true))
                        }, media = services.media, showDay = true)
                    }
                }
            }
        }
    }
}

/** How many of the most used tags sit beside «Тег…» for one tap. */
private const val QUICK_TAGS = 8
