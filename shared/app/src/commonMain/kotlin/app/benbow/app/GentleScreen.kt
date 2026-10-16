package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import app.benbow.app.resources.cancel
import app.benbow.app.resources.gentle
import app.benbow.app.resources.gentle_add
import app.benbow.app.resources.gentle_count
import app.benbow.app.resources.gentle_intro
import app.benbow.app.resources.gentle_kind_category
import app.benbow.app.resources.gentle_kind_period
import app.benbow.app.resources.gentle_kind_tag
import app.benbow.app.resources.gentle_levels
import app.benbow.app.resources.gentle_marked
import app.benbow.app.resources.gentle_new_rule
import app.benbow.app.resources.gentle_no_tags
import app.benbow.app.resources.gentle_nothing
import app.benbow.app.resources.gentle_open
import app.benbow.app.resources.gentle_period_name
import app.benbow.app.resources.gentle_pick_dates
import app.benbow.app.resources.gentle_pick_days
import app.benbow.app.resources.gentle_remove
import app.benbow.app.resources.gentle_rules
import app.benbow.app.resources.gentle_rules_empty
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.save
import app.benbow.client.EntrySummary
import app.benbow.client.GentleRule
import app.benbow.client.TagCategory
import app.benbow.client.TagCount
import app.benbow.client.Veil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** "Ещё → Бережно" (ADR-031): the rules, each with its level and its entries, and the ones marked by hand. */
internal class GentleViewModel(private val services: AppServices) : ViewModel() {
    class State(val rules: List<Pair<GentleRule, Int>>, val marked: Int, val tags: List<TagCount>, val categories: List<TagCategory>)

    val state: StateFlow<State?> = combine(services.repo.journal(), services.repo.gentleRules(), services.repo.tags()) {
            entries,
            rules,
            tags,
        ->
        State(
            rules.rules.map { rule -> rule to entries.count { rules.matches(rule, it.day, it.tags) } },
            entries.count { it.marked },
            tags.sortedByDescending { it.uses },
            rules.registry.categories,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setLevel(rule: GentleRule, level: String) {
        services.scope.launch { services.repo.setGentleRule(rule.copy(level = level)) }
    }

    fun remove(rule: GentleRule) {
        services.scope.launch { services.repo.removeGentleRule(rule.id) }
    }

    fun add(kind: String, name: String, value: String = "", from: String = "", to: String = "") {
        services.scope.launch { services.repo.setGentleRule(GentleRule(services.repo.newRuleId(), name, kind, value, from, to)) }
    }
}

/** Marked by hand: a curtain or quiet of its own, or put off (not merely open despite a rule). */
private val EntrySummary.marked get() = ownVeil == Veil.Curtain || ownVeil == Veil.Quiet || until != null

private val LEVELS = listOf(GentleRule.OFF, GentleRule.CURTAIN, GentleRule.QUIET)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GentleScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { GentleViewModel(services) }
    val state by vm.state.collectAsStateWithLifecycle()
    val on by services.gentle.on.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var period by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.gentle)) })
    }) { padding ->
        val current = state ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "intro") {
                Text(
                    stringResource(Res.string.gentle_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item(key = "switch") { GentleStrip(on, services.gentle::set) }
            if (current.marked > 0) {
                item(key = "marked") {
                    RuleCard(stringResource(Res.string.gentle_marked), null, current.marked, onOpen = {
                        nav.open(Route.GentleEntries(null))
                    })
                }
            }
            item(key = "rules") {
                Text(
                    stringResource(Res.string.gentle_rules),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            if (current.rules.isEmpty()) {
                item(key = "rules-empty") {
                    Text(
                        stringResource(Res.string.gentle_rules_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            items(current.rules, key = { it.first.id }) { (rule, count) ->
                RuleCard(
                    rule.name,
                    ruleKind(rule),
                    count,
                    level = rule.level,
                    onLevel = { vm.setLevel(rule, it) },
                    onRemove = { vm.remove(rule) },
                    onOpen = { nav.open(Route.GentleEntries(rule.id)) },
                )
            }
            item(key = "add") {
                TextButton({ adding = true }, Modifier.padding(horizontal = 12.dp).hand()) { Text(stringResource(Res.string.gentle_add)) }
            }
        }
        if (adding) {
            // Rules point at ids (ADR-032); older ones at a name, which in lower case is the same id.
            val taken = current.rules.map { it.first.kind to it.first.key }.toSet()
            AddRuleDialog(
                tags = current.tags.filter { (GentleRule.TAG to it.id) !in taken },
                categories = current.categories.filter { (GentleRule.CATEGORY to it.id) !in taken },
                onTag = {
                    adding = false
                    vm.add(GentleRule.TAG, it.name, value = it.id)
                },
                onCategory = {
                    adding = false
                    vm.add(GentleRule.CATEGORY, it.name, value = it.id)
                },
                onPeriod = {
                    adding = false
                    period = true
                },
                onDismiss = { adding = false },
            )
        }
        if (period) {
            val months = stringArrayResource(Res.array.months_genitive)
            PeriodDialog(
                onPick = { name, from, to ->
                    period = false
                    vm.add(GentleRule.PERIOD, name.ifBlank { periodLabel(from, to, months) }, from = from.toString(), to = to.toString())
                },
                onDismiss = { period = false },
            )
        }
    }
}

/** "Тег", "Категория", or the period's days. */
@Composable
private fun ruleKind(rule: GentleRule): String = when (rule.kind) {
    GentleRule.TAG -> stringResource(Res.string.gentle_kind_tag)

    GentleRule.CATEGORY -> stringResource(Res.string.gentle_kind_category)

    else -> {
        val months = stringArrayResource(Res.array.months_genitive)
        val from = runCatching { LocalDate.parse(rule.from) }.getOrNull()
        val to = runCatching { LocalDate.parse(rule.to) }.getOrNull()
        if (from != null && to != null) periodLabel(from, to, months) else stringResource(Res.string.gentle_kind_period)
    }
}

/** "1 марта 2024 – 31 мая 2024". */
private fun periodLabel(from: LocalDate, to: LocalDate, months: List<String>): String {
    fun label(date: LocalDate) = "${date.day} ${months[date.month.number - 1]} ${date.year}"
    return "${label(from)} – ${label(to)}"
}

/** A rule (or the entries marked by hand, without a level): its name, kind, level and its entries. */
@Composable
private fun RuleCard(
    name: String,
    kind: String?,
    count: Int,
    level: String? = null,
    onLevel: (String) -> Unit = {},
    onRemove: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    kind?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                }
                onRemove?.let { remove ->
                    IconButton(remove, Modifier.hand()) {
                        Icon(Icons.Close, stringResource(Res.string.gentle_remove), tint = colors.onSurfaceVariant)
                    }
                }
            }
            if (level != null) {
                val labels = stringArrayResource(Res.array.gentle_levels)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LEVELS.forEachIndexed { i, value ->
                        FilterChip(level == value, { onLevel(value) }, { Text(labels[i]) }, Modifier.hand())
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(Res.plurals.gentle_count, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (count > 0) TextButton(onOpen, Modifier.hand()) { Text(stringResource(Res.string.gentle_open)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddRuleDialog(
    tags: List<TagCount>,
    categories: List<TagCategory>,
    onTag: (TagCount) -> Unit,
    onCategory: (TagCategory) -> Unit,
    onPeriod: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.gentle_new_rule)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.gentle_kind_tag), style = MaterialTheme.typography.titleSmall)
                if (tags.isEmpty()) {
                    Text(stringResource(Res.string.gentle_no_tags), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag -> FilterChip(false, { onTag(tag) }, { Text(tag.name) }, Modifier.hand()) }
                    }
                }
                Text(stringResource(Res.string.gentle_kind_category), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { category -> FilterChip(false, { onCategory(category) }, { Text(category.name) }, Modifier.hand()) }
                }
                Text(stringResource(Res.string.gentle_kind_period), style = MaterialTheme.typography.titleSmall)
                TextButton(onPeriod, Modifier.hand()) { Text(stringResource(Res.string.gentle_pick_dates)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** A period: its days and a name of its own ("Весна 2024"); the date pickers speak UTC midnights. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodDialog(onPick: (name: String, from: LocalDate, to: LocalDate) -> Unit, onDismiss: () -> Unit) {
    val range = rememberDateRangePickerState()
    val months = stringArrayResource(Res.array.months_genitive)
    var name by remember { mutableStateOf("") }
    fun day(millis: Long?) = millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
    val from = day(range.selectedStartDateMillis)
    val to = day(range.selectedEndDateMillis) ?: from
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({ if (from != null && to != null) onPick(name.trim(), from, to) }, Modifier.hand(), enabled = from != null) {
                Text(stringResource(Res.string.save))
            }
        },
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    ) {
        DateRangePicker(
            range,
            Modifier.weight(1f),
            headline = {
                Text(
                    if (from == null || to == null) stringResource(Res.string.gentle_pick_days) else periodLabel(from, to, months),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                )
            },
            title = {
                OutlinedTextField(
                    name,
                    { name = it },
                    placeholder = { Text(stringResource(Res.string.gentle_period_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

/** A rule's entries (or the ones marked by hand), everything open: coming back to them on purpose. */
internal class GentleEntriesViewModel(services: AppServices, private val ruleId: String?) : ViewModel() {
    class State(val title: String?, val entries: List<EntrySummary>)

    val state: StateFlow<State?> = combine(services.repo.journal(), services.repo.gentleRules()) { entries, rules ->
        if (ruleId == null) {
            State(null, entries.filter { it.marked })
        } else {
            val rule = rules.rules.firstOrNull { it.id == ruleId }
            State(rule?.name.orEmpty(), if (rule == null) emptyList() else entries.filter { rules.matches(rule, it.day, it.tags) })
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GentleEntriesScreen(services: AppServices, nav: Navigator, route: Route.GentleEntries) {
    val vm = viewModel(key = "gentle-${route.ruleId}") { GentleEntriesViewModel(services, route.ruleId) }
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { BackButton(nav::back) },
            title = { Text(state?.title ?: stringResource(Res.string.gentle_marked)) },
        )
    }) { padding ->
        val current = state ?: return@Scaffold
        if (current.entries.isEmpty()) {
            Text(
                stringResource(Res.string.gentle_nothing),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            return@Scaffold
        }
        // Here the owner came on purpose: no curtains.
        CompositionLocalProvider(LocalGentle provides false) {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(current.entries, key = { it.id }) { entry ->
                    EntryCard(entry, onClick = { nav.open(Route.Editor(entry.id, entry.day)) }, media = services.media, showDay = true)
                }
            }
        }
    }
}
