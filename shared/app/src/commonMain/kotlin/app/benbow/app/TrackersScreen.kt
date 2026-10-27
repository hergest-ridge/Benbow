package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.menu
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.tab_trackers
import app.benbow.app.resources.trackers_archive
import app.benbow.app.resources.trackers_archived
import app.benbow.app.resources.trackers_blank
import app.benbow.app.resources.trackers_down
import app.benbow.app.resources.trackers_empty
import app.benbow.app.resources.trackers_empty_hint
import app.benbow.app.resources.trackers_new
import app.benbow.app.resources.trackers_setup
import app.benbow.app.resources.trackers_unarchive
import app.benbow.app.resources.trackers_up
import app.benbow.app.resources.untitled
import app.benbow.client.IoDispatcher
import app.benbow.client.tracker.TrackerRepository
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerKind
import app.benbow.core.tracker.TrackerRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Trackers show only in labs builds while they are being built (ADR-050). */
internal val AppServices.trackersShown: Boolean get() = labs

/** A tab of their own on the web and the desktop; a phone reaches them from «Ещё». */
internal val AppServices.trackersTab: Boolean get() = trackersShown && platform != "android"

/** Opens [id]'s sheet from elsewhere (Home, the calendar): beside the list in the tab, else a page of its own. */
internal fun openTracker(services: AppServices, nav: Navigator, id: String) {
    if (services.trackersTab) {
        services.showTracker.value = id
        nav.selectTab(Route.Trackers)
    } else {
        nav.open(Route.TrackerSheet(id))
    }
}

/** Writes to the trackers off the main thread, one at a time in the order they were asked for (a sheet, Home, a widget). */
internal class TrackerWrites(private val scope: CoroutineScope, private val repo: TrackerRepository) {
    private val lock = Mutex()

    operator fun invoke(action: TrackerRepository.() -> Unit) {
        scope.launch { lock.withLock { repo.action() } }
    }
}

/**
 * «Трекеры» (ADR-050): one's trackers in their order and «Новый трекер» from a preset or a blank sheet. On a wide window
 * the list and the open sheet sit side by side; narrower, a tracker opens as a page of its own ([Route.TrackerSheet]).
 * A tab on the web and the desktop ([tab]), a page from «Ещё» on a phone. A tracker's menu (a long press, or its «⋮»)
 * moves it, puts it in the archive or the trash, or opens «Настроить трекер».
 */
@Composable
internal fun TrackersScreen(services: AppServices, nav: Navigator, tab: Boolean) {
    val trackers by remember { services.trackers.trackers() }.collectAsStateWithLifecycle(null)
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val untitled = stringResource(Res.string.untitled)
    // A tracker asked for from elsewhere (a Home block): open beside the list.
    val asked by services.showTracker.collectAsStateWithLifecycle()
    LaunchedEffect(asked) {
        asked?.let {
            chosen = it
            services.showTracker.value = null
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= TWO_PANES && !trackers.isNullOrEmpty()
        val open: (String) -> Unit = { id -> if (wide) chosen = id else nav.open(Route.TrackerSheet(id)) }
        val actions = ListActions(
            open = open,
            create = { preset ->
                scope.launch {
                    val id = withContext(IoDispatcher) { services.trackers.createTracker(preset.tracker, preset.columns) }
                    if (preset.columns.isEmpty()) {
                        chosen = id
                        nav.open(Route.TrackerEditor(id))
                    } else {
                        open(id)
                    }
                }
            },
            setup = { nav.open(Route.TrackerEditor(it)) },
            move = { tracker, delta ->
                val current = trackers.orEmpty().filter { !it.archived }
                val at = current.indexOfFirst { it.id == tracker.id } + delta
                services.trackerWrites { place(TrackerKind.TRACKER, tracker.id, current.filter { it.id != tracker.id }, at) }
            },
            archive = { tracker ->
                val on = !tracker.archived
                services.trackerWrites { updateTracker(tracker.id, mapOf(TrackerField.ARCHIVED to if (on) "true" else null)) }
            },
            trash = { tracker ->
                services.trackerWrites { moveToTrash(tracker.id) }
                services.messages.tryEmit(AppMessage.TrackerTrashed(tracker.id, trackerTitle(tracker, untitled)))
            },
        )
        if (wide) {
            val all = trackers.orEmpty()
            val shown = all.firstOrNull { it.id == chosen } ?: all.firstOrNull { !it.archived } ?: all.first()
            Row(Modifier.fillMaxSize()) {
                TrackerList(trackers, shown.id, tab, nav, actions, Modifier.width(LIST_WIDTH).fillMaxHeight())
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    key(shown.id) { TrackerSheetScreen(services, nav, shown.id, back = false) }
                }
            }
        } else {
            TrackerList(trackers, null, tab, nav, actions, Modifier.fillMaxSize())
        }
    }
}

/** What the list does: open a tracker, make one, and a tracker's menu. */
private class ListActions(
    val open: (String) -> Unit,
    val create: (TrackerPreset) -> Unit,
    val setup: (String) -> Unit,
    val move: (TrackerRecord, Int) -> Unit,
    val archive: (TrackerRecord) -> Unit,
    val trash: (TrackerRecord) -> Unit,
)

/** The trackers in their order, archived ones apart at the end; [shown] is the one open beside the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerList(
    trackers: List<TrackerRecord>?,
    shown: String?,
    tab: Boolean,
    nav: Navigator,
    actions: ListActions,
    modifier: Modifier,
) {
    var adding by remember { mutableStateOf(false) }
    Scaffold(modifier, topBar = {
        TopAppBar(
            navigationIcon = { if (!tab) BackButton(nav::back) },
            title = { Text(stringResource(Res.string.tab_trackers)) },
            actions = {
                Box {
                    IconButton({ adding = true }, Modifier.hand()) { Icon(Icons.Add, stringResource(Res.string.trackers_new)) }
                    DropdownMenu(adding, { adding = false }) {
                        TrackerPresets.all.forEach { preset ->
                            DropdownMenuItem({ Text(presetLabel(preset)) }, {
                                adding = false
                                actions.create(preset)
                            }, Modifier.hand())
                        }
                        HorizontalDivider()
                        DropdownMenuItem({ Text(stringResource(Res.string.trackers_blank)) }, {
                            adding = false
                            actions.create(TrackerPresets.blank)
                        }, Modifier.hand(), leadingIcon = { Icon(Icons.Add, null) })
                    }
                }
            },
        )
    }) { padding ->
        val untitled = stringResource(Res.string.untitled)
        when {
            trackers == null -> Unit

            trackers.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyTrackers(actions.create)
            }

            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                val (archived, current) = trackers.partition { it.archived }
                itemsIndexed(current, key = { _, tracker -> tracker.id }) { i, tracker ->
                    TrackerRow(tracker, untitled, tracker.id == shown, canUp = i > 0, canDown = i < current.lastIndex, actions)
                }
                if (archived.isNotEmpty()) {
                    item(key = "archived") {
                        Text(
                            stringResource(Res.string.trackers_archived),
                            Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(archived, key = { _, tracker -> tracker.id }) { _, tracker ->
                        TrackerRow(
                            tracker,
                            untitled,
                            tracker.id == shown,
                            canUp = false,
                            canDown = false,
                            actions,
                            Modifier.alpha(ARCHIVED_ALPHA),
                        )
                    }
                }
            }
        }
    }
}

/** A tracker's line: its icon on its own color, as its sheet's paper, and its name; its menu on a long press or «⋮». */
@Composable
private fun TrackerRow(
    tracker: TrackerRecord,
    untitled: String,
    shown: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    actions: ListActions,
    modifier: Modifier = Modifier,
) {
    val base = swatch(tracker[TrackerField.BACKGROUND]) ?: MaterialTheme.colorScheme.surfaceVariant
    val name = tracker[TrackerField.NAME]?.takeIf { it.isNotBlank() } ?: untitled
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        ListItem(
            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                Box(Modifier.size(ICON_BOX).clip(RoundedCornerShape(10.dp)).background(base), contentAlignment = Alignment.Center) {
                    Text(tracker[TrackerField.ICON]?.let(::iconEmoji).orEmpty(), fontSize = 20.sp)
                }
            },
            trailingContent = {
                IconButton({ menu = true }, Modifier.hand()) { Icon(Icons.More, stringResource(Res.string.menu)) }
            },
            colors = ListItemDefaults.colors(
                containerColor = if (shown) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
            modifier = Modifier.hand().combinedClickable(onClick = { actions.open(tracker.id) }, onLongClick = { menu = true }),
        )
        DropdownMenu(menu, { menu = false }, Modifier.align(Alignment.TopEnd)) {
            val item = @Composable { label: String, enabled: Boolean, action: () -> Unit ->
                DropdownMenuItem({ Text(label) }, {
                    menu = false
                    action()
                }, Modifier.hand(enabled), enabled = enabled)
            }
            item(stringResource(Res.string.trackers_up), canUp) { actions.move(tracker, -1) }
            item(stringResource(Res.string.trackers_down), canDown) { actions.move(tracker, 1) }
            item(stringResource(Res.string.trackers_setup), true) { actions.setup(tracker.id) }
            item(stringResource(if (tracker.archived) Res.string.trackers_unarchive else Res.string.trackers_archive), true) {
                actions.archive(tracker)
            }
            item(stringResource(Res.string.move_to_trash), true) { actions.trash(tracker) }
        }
    }
}

/** No trackers yet: what they are, and the presets to start from in one tap. */
@Composable
private fun EmptyTrackers(onCreate: (TrackerPreset) -> Unit) {
    Column(Modifier.padding(32.dp).widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(Res.string.trackers_empty), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(Res.string.trackers_empty_hint),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(
            Modifier.padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrackerPresets.all.forEach { preset -> OutlinedButton({ onCreate(preset) }, Modifier.hand()) { Text(presetLabel(preset)) } }
        }
        TextButton({
            onCreate(TrackerPresets.blank)
        }, Modifier.padding(top = 8.dp).hand()) { Text(stringResource(Res.string.trackers_blank)) }
    }
}

private fun presetLabel(preset: TrackerPreset): String =
    listOfNotNull(preset.tracker[TrackerField.ICON]?.let(::iconEmoji), preset.tracker[TrackerField.NAME]).joinToString(" ")

/** From this width the list and the open sheet sit side by side. */
private val TWO_PANES = 720.dp
private val LIST_WIDTH = 300.dp
private val ICON_BOX = 40.dp
private const val ARCHIVED_ALPHA = 0.6f
