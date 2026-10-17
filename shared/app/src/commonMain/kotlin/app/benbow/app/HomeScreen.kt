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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.benbow.app.resources.days_written
import app.benbow.app.resources.greeting_day
import app.benbow.app.resources.greeting_evening
import app.benbow.app.resources.greeting_morning
import app.benbow.app.resources.greeting_night
import app.benbow.app.resources.home_answer
import app.benbow.app.resources.home_drafts
import app.benbow.app.resources.home_on_this_day
import app.benbow.app.resources.home_pinned
import app.benbow.app.resources.home_resurfaced
import app.benbow.app.resources.home_today
import app.benbow.app.resources.home_today_empty
import app.benbow.app.resources.home_write
import app.benbow.app.resources.review_month
import app.benbow.app.resources.review_summary
import app.benbow.app.resources.review_week
import app.benbow.app.resources.years_ago
import app.benbow.client.DayInfo
import app.benbow.client.EntrySummary
import app.benbow.client.Prompts
import app.benbow.client.SyncStatus
import app.benbow.client.Templates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
import kotlin.time.Clock

internal data class HomeState(
    val today: String,
    val todays: List<EntrySummary>,
    val pinned: List<EntrySummary>,
    val drafts: List<EntrySummary>,
    val onThisDay: List<EntrySummary>,
    /** One favorite from over a month ago, the same all day (savoring, ux.md#psychology). */
    val resurfaced: EntrySummary?,
    val daysThisMonth: Long,
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

    private val reviews = combine(
        services.repo.monthDays(date.minus(6, DateTimeUnit.DAY).toString(), today),
        services.repo.monthDays(date.minus(29, DateTimeUnit.DAY).toString(), today),
    ) { week, month -> summary(week.values) to summary(month.values) }

    private val base = combine(
        combine(services.repo.entriesForDay(today), services.repo.pinned(), services.repo.withDrafts(), ::Triple),
        combine(
            services.repo.onThisDay(today),
            services.repo.favoritesUpTo(date.minus(1, DateTimeUnit.MONTH).toString()),
            services.repo.daysWritten(monthStart.toString(), monthStart.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).toString()),
            ::Triple,
        ),
        services.gentle.on,
    ) { (todays, pinned, drafts), (allOnThisDay, allFavorites, days), gentle ->
        // Бережно (ADR-031): quiet entries stay out of Home; what comes back unasked (On this day, the resurfaced
        // favorite) leaves out curtained ones too.
        val onThisDay = if (gentle) allOnThisDay.filter { it.veil == null } else allOnThisDay
        val favorites = if (gentle) allFavorites.filter { it.veil == null } else allFavorites
        HomeState(
            today,
            todays.withoutQuiet(gentle) { it },
            pinned.withoutQuiet(gentle) { it },
            drafts.withoutQuiet(gentle) { it },
            onThisDay,
            favorites.takeIf { it.isNotEmpty() }?.let {
                it[
                    today.hashCode().absoluteValue %
                        it.size,
                ]
            },
            days,
        )
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

/** "Сегодня": the day's entries, what's unfinished, and gentle looks back. No streaks, no guilt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { HomeViewModel(services) }
    val state by vm.state.collectAsStateWithLifecycle()
    val kindness by vm.kindness.collectAsStateWithLifecycle()
    val status by services.sync.status.collectAsStateWithLifecycle()
    var refreshUntil by remember { mutableStateOf<Int?>(null) }
    var postponing by remember { mutableStateOf<EntrySummary?>(null) }
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
            val open = { entry: EntrySummary -> nav.open(Route.Editor(entry.id, entry.day)) }
            val write = { template: String -> nav.open(Route.Editor(null, services.today(), opened = services.now(), template = template)) }
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
                                open(entry)
                            },
                            onLater = { vm.answerReturned(entry.id) },
                            onAgain = { postponing = entry },
                        )
                    }
                }
                item(key = "checkin") { CheckinCard(vm::checkin) }
                if (kindness) {
                    item(key = "kindness") {
                        KindnessCard(Prompts.kindness(home.today), onWrite = {
                            vm.kindness.value = false
                            write(Templates.KINDNESS.id)
                        }, onLater = { vm.kindness.value = false })
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
                item(key = "prompt") {
                    Prompt(Prompts.ofTheDay(home.today), stringResource(Res.string.home_answer)) { write(Templates.PROMPT.id) }
                }
                section(Res.string.home_pinned, home.pinned, services, open)
                item(key = "today") { SectionTitle(stringResource(Res.string.home_today)) }
                if (home.todays.isEmpty()) {
                    item(key = "today-empty") {
                        Prompt(stringResource(Res.string.home_today_empty), stringResource(Res.string.home_write), newEntry)
                    }
                } else {
                    items(home.todays, key = { "t" + it.id }) { EntryCard(it, onClick = { open(it) }, media = services.media) }
                }
                section(Res.string.home_drafts, home.drafts, services, open, keyPrefix = "d")
                if (home.onThisDay.isNotEmpty()) {
                    item(key = "otd") { SectionTitle(stringResource(Res.string.home_on_this_day)) }
                    home.onThisDay.groupBy { it.day.take(4).toInt() }.forEach { (year, entries) ->
                        val ago = home.today.take(4).toInt() - year
                        item(key = "otd$year") {
                            Text(
                                pluralStringResource(Res.plurals.years_ago, ago, ago),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 2.dp),
                            )
                        }
                        items(entries, key = { "o" + it.id }) { EntryCard(it, onClick = { open(it) }, media = services.media) }
                    }
                }
                home.resurfaced?.let { entry ->
                    item(key = "fav") { SectionTitle(stringResource(Res.string.home_resurfaced)) }
                    item(key = "f" + entry.id) { EntryCard(entry, onClick = { open(entry) }, media = services.media, showDay = true) }
                }
                if (home.daysThisMonth > 0) {
                    item(key = "stats") {
                        val days = home.daysThisMonth.toInt()
                        Text(
                            pluralStringResource(Res.plurals.days_written, days, days),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(
    title: org.jetbrains.compose.resources.StringResource,
    entries: List<EntrySummary>,
    services: AppServices,
    open: (EntrySummary) -> Unit,
    keyPrefix: String = "p",
) {
    if (entries.isEmpty()) return
    item(key = "title-$keyPrefix") { SectionTitle(stringResource(title)) }
    items(entries, key = { keyPrefix + it.id }) { EntryCard(it, onClick = { open(it) }, media = services.media, showDay = true) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Prompt(text: String, action: String, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            TextButton(onAction, modifier = Modifier.hand()) { Text(action) }
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
