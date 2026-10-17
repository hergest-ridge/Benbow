package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import app.benbow.app.resources.greeting_day
import app.benbow.app.resources.greeting_evening
import app.benbow.app.resources.greeting_morning
import app.benbow.app.resources.greeting_night
import app.benbow.app.resources.review_month
import app.benbow.app.resources.review_summary
import app.benbow.app.resources.review_week
import app.benbow.client.BlockSize
import app.benbow.client.DayInfo
import app.benbow.client.EntrySummary
import app.benbow.client.HomeBlock
import app.benbow.client.HomeBlockType
import app.benbow.client.HomeLayout
import app.benbow.client.HomeStats
import app.benbow.client.Prompts
import app.benbow.client.SearchQuery
import app.benbow.client.SyncStatus
import app.benbow.client.Templates
import app.benbow.client.editHomeLayout
import app.benbow.client.homeLayout
import app.benbow.client.homeStats
import app.benbow.client.minusBlock
import app.benbow.client.tagRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Clock

internal data class HomeState(
    val today: String,
    /** The owner's blocks (ADR-034) that can be drawn and have something to show, in their order. */
    val blocks: List<HomeBlock>,
    /** The whole layout as stored, for the editor and a block's menu. */
    val layout: HomeLayout,
    val todays: List<EntrySummary>,
    val pinned: List<EntrySummary>,
    val drafts: List<EntrySummary>,
    val onThisDay: List<EntrySummary>,
    /** One favorite from over a month ago, the same all day (savoring, ux.md#psychology). */
    val resurfaced: EntrySummary?,
    /** Soft stats for the stats blocks: the month counted up (null without a stats block). */
    val stats: HomeStats?,
    /** Each tag block's gallery by the block's id (none while its tag is gone). */
    val galleries: Map<String, TagGallery> = emptyMap(),
    /** Days with entries and their average mood over the last 7 and 30 days, for the review cards. */
    val week: Pair<Int, Double?> = 0 to null,
    val month: Pair<Int, Double?> = 0 to null,
    /** A put-off entry whose day has come (ADR-031), oldest first; one card at a time. */
    val returned: EntrySummary? = null,
)

internal class HomeViewModel(private val services: AppServices) : ViewModel() {
    private val today = services.today()
    private val date = LocalDate.parse(today)
    private val monthStart = LocalDate(date.year, date.month, 1)

    /** True right after a hard check-in, until written or put off. */
    val kindness = MutableStateFlow(false)

    /** Sundays offer the weekly review, the last days of a month the monthly one. */
    val review: String? = when {
        date.dayOfWeek == DayOfWeek.SUNDAY -> "week"
        date.plus(3, DateTimeUnit.DAY).month != date.month -> "month"
        else -> null
    }

    private val reviews: Flow<Pair<Pair<Int, Double?>, Pair<Int, Double?>>> = if (review == null) {
        flowOf((0 to null) to (0 to null))
    } else {
        combine(
            services.repo.monthDays(date.minus(6, DateTimeUnit.DAY).toString(), today),
            services.repo.monthDays(date.minus(29, DateTimeUnit.DAY).toString(), today),
        ) { week, month -> summary(week.values) to summary(month.values) }
    }

    /** Only the blocks on Home read the journal: a block the owner took away costs nothing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val base = services.repo.homeLayout().flatMapLatest { layout ->
        val kinds = layout.blocks.mapNotNullTo(HashSet()) { block -> block.kind?.takeIf { block.blockSize != null } }
        fun <T> ifShown(kind: HomeBlockType, none: T, flow: () -> Flow<T>): Flow<T> = if (kind in kinds) flow() else flowOf(none)
        val repo = services.repo
        combine(
            combine(
                ifShown(HomeBlockType.Today, emptyList()) { repo.entriesForDay(today) },
                ifShown(HomeBlockType.Pinned, emptyList()) { repo.pinned() },
                ifShown(HomeBlockType.Drafts, emptyList()) { repo.withDrafts() },
                ::Triple,
            ),
            combine(
                ifShown(HomeBlockType.OnThisDay, emptyList()) { repo.onThisDay(today) },
                ifShown(HomeBlockType.Favorite, emptyList()) { repo.favoritesUpTo(date.minus(1, DateTimeUnit.MONTH).toString()) },
                ifShown(HomeBlockType.Stats, null) {
                    val monthEnd = monthStart.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).toString()
                    val weekStart = date.minus(6, DateTimeUnit.DAY).toString()
                    services.gentle.on.flatMapLatest { gentle -> repo.homeStats(monthStart.toString(), monthEnd, weekStart, today, gentle) }
                },
                ::Triple,
            ),
            tagGalleries(layout.blocks.filter { it.kind == HomeBlockType.Tag && it.blockSize != null }),
            services.gentle.on,
        ) { (todays, pinned, drafts), (allOnThisDay, allFavorites, stats), found, gentle ->
            // Бережно (ADR-031): quiet entries stay out of Home; what comes back unasked (On this day, the resurfaced
            // favorite) leaves out curtained ones too.
            val onThisDay = if (gentle) allOnThisDay.filter { it.veil == null } else allOnThisDay
            val favorites = if (gentle) allFavorites.filter { it.veil == null } else allFavorites
            val home = HomeState(
                today,
                layout.blocks,
                layout,
                todays.withoutQuiet(gentle) { it },
                pinned.withoutQuiet(gentle) { it },
                drafts.withoutQuiet(gentle) { it },
                onThisDay,
                favorites.takeIf { it.isNotEmpty() }?.let { it[today.hashCode().absoluteValue % it.size] },
                stats,
                galleries(found, gentle),
            )
            home.copy(blocks = home.blocks.filter { it.blockSize != null && home.shows(it) })
        }
    }

    /**
     * What each tag block shows: the same as search with its tag (merged tags read as the one they went into) and
     * "with media", or with the tag alone when none of its entries has media. A tag in the trash shows nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun tagGalleries(blocks: List<HomeBlock>): Flow<Map<HomeBlock, Pair<SearchQuery, List<EntrySummary>>>> {
        if (blocks.isEmpty()) return flowOf(emptyMap())
        val repo = services.repo
        return repo.tagRegistry().flatMapLatest { registry ->
            combine(
                blocks.map { block ->
                    val tag = block.config[HomeBlock.TAG]?.let(registry::root)?.takeIf { !registry.inTrash(it) }
                    if (tag == null) {
                        flowOf(null)
                    } else {
                        val pictures = SearchQuery(tag = tag, withMedia = true)
                        repo.search(pictures).flatMapLatest { found ->
                            if (found.isNotEmpty()) {
                                flowOf(block to (pictures to found))
                            } else {
                                SearchQuery(tag = tag).let { all -> repo.search(all).map { block to (all to it) } }
                            }
                        }
                    }
                },
            ) { pairs -> pairs.filterNotNull().toMap() }
        }
    }

    /** A tag block is a passive place (ADR-031): with Бережно on, no quiet or curtained entry comes up in it. */
    private fun galleries(found: Map<HomeBlock, Pair<SearchQuery, List<EntrySummary>>>, gentle: Boolean): Map<String, TagGallery> =
        found.entries.associate { (block, result) ->
            val (query, all) = result
            val entries = if (gentle) all.filter { it.veil == null } else all
            val ordered = if (block.config[HomeBlock.ORDER] == HomeBlock.RANDOM) {
                entries.shuffled(Random(today.hashCode() * 31 + block.id.hashCode())) // the same all day
            } else {
                entries
            }
            block.id to TagGallery(query.tag.orEmpty(), query, entries.size, ordered.take((block.blockSize ?: BlockSize.M).cells))
        }

    val state: StateFlow<HomeState?> = combine(base, reviews, services.repo.returned(today)) { home, (week, month), returned ->
        home.copy(week = week, month = month, returned = returned.firstOrNull())
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun checkin(mood: Int, emotions: List<String>, text: String) {
        services.scope.launch { services.repo.createCheckin(services.today(), mood, emotions, text) }
        kindness.value = mood <= -1
    }

    /** The returned card's "Не сейчас" and "Открыть": answered, so the card goes; the entry is already back. */
    fun answerReturned(id: String) {
        services.scope.launch { services.repo.setVeilUntil(id, null) }
    }

    fun quickNote(text: String) {
        if (text.isBlank()) return
        services.scope.launch { services.repo.createNote(services.today(), text) }
    }
}

/**
 * "Сегодня": the owner's blocks (ADR-034) under the date and greeting. Cards that come for a reason (an entry back from
 * "Отложить до…", the week's or month's review) sit above them and go once answered. No streaks, no guilt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { HomeViewModel(services) }
    val state by vm.state.collectAsStateWithLifecycle()
    val kindness by vm.kindness.collectAsStateWithLifecycle()
    val status by services.sync.status.collectAsStateWithLifecycle()
    var refreshUntil by remember { mutableStateOf<Int?>(null) }
    var postponing by remember { mutableStateOf<EntrySummary?>(null) }

    /** The editor's open with this block picked ("" for none); null while closed. */
    var editing by remember { mutableStateOf<String?>(null) }
    val newEntry = { nav.open(Route.Editor(null, services.today(), opened = services.now())) }
    Scaffold(
        topBar = { HomeHeader(state?.today, status) { nav.open(Route.Server) } },
        bottomBar = { QuickNoteBar(services, onSend = vm::quickNote, onNewEntry = newEntry) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshUntil?.let { status.completed < it } == true,
            onRefresh = {
                refreshUntil = status.completed + 1 + if (status.syncing) 1 else 0
                services.sync.requestSync()
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val home = state ?: return@PullToRefreshBox
            val write = { template: String -> nav.open(Route.Editor(null, services.today(), opened = services.now(), template = template)) }
            val actions = HomeActions(
                open = { entry -> nav.open(Route.Editor(entry.id, entry.day)) },
                write = write,
                newEntry = newEntry,
                checkin = vm::checkin,
                remove = { block ->
                    val at = home.layout.blocks.indexOfFirst { it.id == block.id }
                    services.scope.launch { services.repo.editHomeLayout { it.minusBlock(block.id) } }
                    services.messages.tryEmit(AppMessage.HomeBlockRemoved(block, at))
                },
                customize = { block -> editing = block?.id.orEmpty() },
                search = { query ->
                    services.searchFor.value = query
                    nav.selectTab(Route.Search)
                },
            )
            editing?.let { select ->
                HomeEditor(services, home.layout, home, select.ifEmpty { null }, onDismiss = { editing = null })
            }
            postponing?.let { entry ->
                PostponeDialog(onPick = { day ->
                    postponing = null
                    services.postpone(entry.id, day.toString(), entry.until)
                }, onDismiss = { postponing = null })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                home.returned?.let { entry ->
                    item(key = "returned") {
                        ReturnedCard(
                            entry,
                            onOpen = {
                                vm.answerReturned(entry.id)
                                actions.open(entry)
                            },
                            onLater = { vm.answerReturned(entry.id) },
                            onAgain = { postponing = entry },
                        )
                    }
                }
                vm.review?.let { kind ->
                    item(key = "review") {
                        val (days, mood) = if (kind == "week") home.week else home.month
                        val face = moodEmoji(mood?.roundToInt())?.let { " · $it" }.orEmpty()
                        ReviewCard(
                            stringResource(if (kind == "week") Res.string.review_week else Res.string.review_month),
                            if (days > 0) pluralStringResource(Res.plurals.review_summary, days, days) + face else "",
                        ) { write(kind) }
                    }
                }
                homeBlocks(home, actions, services) { block ->
                    if (block.kind == HomeBlockType.Checkin && kindness) {
                        item(key = "kindness") {
                            KindnessCard(Prompts.kindness(home.today), onWrite = {
                                vm.kindness.value = false
                                write(Templates.KINDNESS.id)
                            }, onLater = { vm.kindness.value = false })
                        }
                    }
                }
            }
        }
    }
}

private fun summary(days: Collection<DayInfo>): Pair<Int, Double?> =
    days.size to days.mapNotNull { it.mood }.takeIf { it.isNotEmpty() }?.average()

/** Date above, a large greeting, sync as one quiet icon; the rest of the app lives under the "Ещё" tab. */
@Composable
private fun HomeHeader(today: String?, status: SyncStatus, onSync: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(
            TopAppBarDefaults.windowInsets,
        ).padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                today?.let { fullDateLabel(it) }.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(greeting(), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
        }
        SyncBadge(status, onSync)
    }
}

@Composable
private fun greeting(): String {
    val hour = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour
    return stringResource(
        when (hour) {
            in 5..11 -> Res.string.greeting_morning
            in 12..17 -> Res.string.greeting_day
            in 18..22 -> Res.string.greeting_evening
            else -> Res.string.greeting_night
        },
    )
}
