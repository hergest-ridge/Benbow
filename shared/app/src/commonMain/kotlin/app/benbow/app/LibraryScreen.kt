package app.benbow.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.library
import app.benbow.app.resources.library_by_size
import app.benbow.app.resources.library_chosen
import app.benbow.app.resources.library_drawings
import app.benbow.app.resources.library_empty
import app.benbow.app.resources.library_file
import app.benbow.app.resources.library_files
import app.benbow.app.resources.library_notes
import app.benbow.app.resources.library_open_entry
import app.benbow.app.resources.library_sounds
import app.benbow.app.resources.library_total
import app.benbow.app.resources.library_visual
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.size_units
import app.benbow.app.resources.storage
import app.benbow.client.LibraryItem
import app.benbow.client.LibrarySection
import app.benbow.client.Media
import app.benbow.client.Veil
import app.benbow.client.library
import app.benbow.core.media.AttachmentKey
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

/** What the library shows: one section, by date (in months) or by weight, the board's attachments too or not. */
internal data class LibraryView(
    val section: LibrarySection = LibrarySection.Visual,
    val bySize: Boolean = false,
    val notes: Boolean = false,
) {
    /** [items] as this view shows them; while Бережно is on, the quiet entries' attachments aren't there at all. */
    fun of(items: List<LibraryItem>, gentle: Boolean): List<LibraryItem> = items
        .filter { it.section == section && (notes || !it.board) && !(gentle && it.veil == Veil.Quiet) }
        .let { shown -> if (bySize) shown.sortedByDescending { it.size } else shown }
}

/** Under the curtain right now: Бережно is on and its entry is veiled (the quiet ones aren't shown at all). */
internal fun LibraryItem.curtained(gentle: Boolean) = gentle && veil != null

/** The items by the month of their entries' days, in the order they come (the newest first). */
internal fun List<LibraryItem>.byMonth(): List<Pair<String, List<LibraryItem>>> = groupBy { it.day.take(7) }.toList()

/** The viewer's key of an item: its entry and attachment. */
internal val LibraryItem.viewerKey: String get() = "$entryId/${attachment.meta.id}"

private val SECTIONS = listOf(
    LibrarySection.Visual to Res.string.library_visual,
    LibrarySection.Sounds to Res.string.library_sounds,
    LibrarySection.Drawings to Res.string.library_drawings,
    LibrarySection.Files to Res.string.library_files,
)

/**
 * «Ещё → Медиатека» (ADR-041): every attachment shown in an entry, by section. Pictures and videos (and drawings) as a
 * grid by month that a pinch makes denser or roomier, sounds and files as rows; «По весу» puts the heaviest first. A tap
 * opens the viewer (a file opens its entry, a sound plays); a long press starts choosing, and the chosen go to the
 * trash with «Вернуть». Бережно: the quiet entries' attachments aren't here, the curtained ones open on a tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(services: AppServices, nav: Navigator) {
    val all by remember { services.repo.library() }.collectAsStateWithLifecycle(null)
    val gentle = LocalGentle.current
    var section by rememberSaveable { mutableStateOf(LibrarySection.Visual) }
    var bySize by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<AttachmentKey>()) }
    var opened by remember { mutableStateOf(setOf<String>()) }
    val view = LibraryView(section, bySize, notes)
    val shown = remember(all, view, gentle) { all?.let { view.of(it, gentle) } }
    // A chosen attachment that left the library (taken out elsewhere, or another section) is no longer chosen.
    LaunchedEffect(shown) {
        val here = shown?.mapTo(HashSet()) { it.key } ?: return@LaunchedEffect
        if (chosen.any { it !in here }) chosen = chosen.filterTo(HashSet()) { it in here }
    }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = chosen.isNotEmpty()) {
        chosen = emptySet()
    }
    val openEntry = { item: LibraryItem -> nav.open(Route.Editor(item.postId, item.day, readOnly = true)) }
    val tap = { item: LibraryItem ->
        when {
            chosen.isNotEmpty() -> chosen = chosen.toggle(item.key)
            item.curtained(gentle) && item.postId !in opened -> opened = opened + item.postId
            item.section == LibrarySection.Files -> openEntry(item)
            else -> nav.open(Route.LibraryMedia(view, item.viewerKey))
        }
    }
    val choose = { item: LibraryItem -> chosen = chosen.toggle(item.key) }
    Scaffold(topBar = {
        if (chosen.isEmpty()) {
            TopAppBar(
                navigationIcon = { BackButton(nav::back) },
                title = { Text(stringResource(Res.string.library)) },
                actions = {
                    IconButton({ nav.open(Route.Storage) }, Modifier.hand()) { Icon(Icons.Storage, stringResource(Res.string.storage)) }
                },
            )
        } else {
            val one = chosen.singleOrNull()?.let { key -> shown?.firstOrNull { it.key == key } }
            TopAppBar(
                navigationIcon = {
                    IconButton({ chosen = emptySet() }, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.cancel)) }
                },
                title = { Text(stringResource(Res.string.library_chosen, chosen.size)) },
                actions = {
                    if (one != null) TextButton({ openEntry(one) }, Modifier.hand()) { Text(stringResource(Res.string.library_open_entry)) }
                    IconButton({
                        services.trashMedia(chosen.toList())
                        chosen = emptySet()
                    }, Modifier.hand()) { Icon(Icons.Delete, stringResource(Res.string.move_to_trash)) }
                },
            )
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SECTIONS.forEach { (value, label) ->
                    FilterChip(section == value, { section = value }, { Text(stringResource(label)) }, Modifier.hand())
                }
            }
            val list = shown ?: return@Column
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Each file once, however many entries hold it: what it weighs on the device and the server.
                val weight = remember(list) { list.distinctBy { it.attachment.meta.sha256 }.sumOf { it.size } }
                Text(
                    if (list.isEmpty()) "" else stringResource(Res.string.library_total, list.size, sizeLabel(weight)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(bySize, { bySize = !bySize }, { Text(stringResource(Res.string.library_by_size)) }, Modifier.hand())
                FilterChip(notes, { notes = !notes }, { Text(stringResource(Res.string.library_notes)) }, Modifier.hand())
            }
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            val groups = remember(list, bySize) { if (bySize) listOf(null to list) else list.byMonth() }
            val rows = LibraryRows(list, groups, gentle, opened, chosen, tap, choose)
            when (section) {
                LibrarySection.Visual, LibrarySection.Drawings -> LibraryGrid(services.media, rows)
                LibrarySection.Sounds -> LibrarySounds(services, rows)
                LibrarySection.Files -> LibraryFiles(rows)
            }
        }
    }
}

/** What every section's list needs: the items and how they're shown, chosen and tapped. */
private class LibraryRows(
    val items: List<LibraryItem>,
    /** By month with a heading each, or one run by weight (no heading). */
    val groups: List<Pair<String?, List<LibraryItem>>>,
    val gentle: Boolean,
    val opened: Set<String>,
    val chosen: Set<AttachmentKey>,
    val onTap: (LibraryItem) -> Unit,
    val onChoose: (LibraryItem) -> Unit,
) {
    val choosing: Boolean get() = chosen.isNotEmpty()

    fun curtained(item: LibraryItem) = item.curtained(gentle) && item.postId !in opened
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGrid(media: Media, rows: LibraryRows) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var columns by rememberSaveable { mutableIntStateOf(0) } // 0: as many as the width holds
        val count = if (columns > 0) columns else (maxWidth / 112.dp).toInt().coerceIn(3, 8)
        val pinch by rememberUpdatedState { by: Int -> columns = (count + by).coerceIn(2, 8) }
        LazyVerticalGrid(
            GridCells.Fixed(count),
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    watchForPinch { pinch(it) }
                }
            },
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            rows.groups.forEach { (month, group) ->
                if (month != null) item(key = "m:$month", span = { GridItemSpan(maxLineSpan) }) { MonthHeading(month) }
                items(group, key = { it.viewerKey }) { item ->
                    LibraryTile(
                        media,
                        item,
                        curtained = rows.curtained(item),
                        chosen = item.key in rows.chosen,
                        choosing = rows.choosing,
                        onTap = { rows.onTap(item) },
                        onLongPress = { rows.onChoose(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeading(month: String, start: Dp = 14.dp) {
    Text(
        monthTitle(month).orEmpty(),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = start, end = 14.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** A square: the picture (a video's frame with its length, a drawing on paper), or the curtain; chosen ones sink in. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(
    media: Media,
    item: LibraryItem,
    curtained: Boolean,
    chosen: Boolean,
    choosing: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val meta = item.attachment.meta
    val inset by animateDpAsState(if (chosen) 10.dp else 0.dp)
    Box(
        modifier.aspectRatio(1f).background(if (chosen) colors.primaryContainer else Color.Transparent)
            .semantics { contentDescription = meta.name ?: meta.kind }
            .hand().combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Box(
            Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(if (chosen) 10.dp else 3.dp))
                .background(if (meta.kind == "drawing" && !curtained) colors.surface else colors.surfaceContainerHigh),
        ) {
            when {
                curtained -> Curtain(Modifier.fillMaxSize())

                meta.kind == "drawing" -> DrawingPicture(media, item.attachment, Modifier.fillMaxSize().padding(6.dp))

                else -> item.attachment.visual?.let { sha ->
                    val load by rememberMediaFile(media, sha)
                    (load as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
            if (meta.kind == "video" && !curtained) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Play, null, Modifier.size(14.dp), tint = Color.White)
                    meta.durationMs?.let { Text(durationLabel(it), style = MaterialTheme.typography.labelSmall, color = Color.White) }
                }
            }
        }
        if (choosing) ChoiceMark(chosen, Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}

/** A round mark while choosing: filled with a tick when chosen, an empty ring otherwise. */
@Composable
private fun ChoiceMark(chosen: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.size(22.dp).clip(CircleShape)
            .background(if (chosen) colors.primary else Color.Black.copy(alpha = 0.25f))
            .then(if (chosen) Modifier else Modifier.border(2.dp, Color.White, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) Icon(Icons.Check, null, Modifier.size(16.dp), tint = colors.onPrimary)
    }
}

/** Sounds as the player's rows; a tap plays from there on down the list, a long press chooses. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibrarySounds(services: AppServices, rows: LibraryRows) {
    val names = rememberTrackNames()
    val tracks = remember(rows.items, names) { rows.items.map { names.track(it.entryId, it.attachment, it.track) } }
    val at = remember(rows.items) { rows.items.withIndex().associate { it.value.key to it.index } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        rows.groups.forEach { (month, group) ->
            if (month != null) item(key = "m:$month") { MonthHeading(month, start = 4.dp) }
            items(group, key = { it.viewerKey }) { item ->
                val index = at.getValue(item.key)
                val chosen = item.key in rows.chosen
                Box(
                    Modifier.animateItem().padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .combinedClickable(
                            onClick = {
                                if (rows.choosing || rows.curtained(item)) {
                                    rows.onTap(item)
                                } else {
                                    services.player.toggle(tracks[index]) { tracks.drop(index) }
                                }
                            },
                            onLongClick = { rows.onChoose(item) },
                        ).hand(),
                ) {
                    if (rows.curtained(item)) {
                        Curtain()
                    } else {
                        AudioRow(services, tracks[index], item.attachment, queue = { tracks.drop(index) })
                    }
                    if (rows.choosing) {
                        // Over the row while choosing, so its play button doesn't take the tap.
                        Box(
                            Modifier.matchParentSize()
                                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { rows.onTap(item) },
                        )
                        ChoiceMark(chosen, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
                    }
                }
            }
        }
    }
}

/** Files as rows: the name, its weight and day; a tap opens the entry it's in, a long press chooses. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryFiles(rows: LibraryRows) {
    val months = stringArrayResource(Res.array.months_genitive)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        rows.groups.forEach { (month, group) ->
            if (month != null) item(key = "m:$month") { MonthHeading(month) }
            items(group, key = { it.viewerKey }) { item ->
                val meta = item.attachment.meta
                val chosen = item.key in rows.chosen
                Row(
                    Modifier.animateItem().fillMaxWidth()
                        .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .hand().combinedClickable(onClick = { rows.onTap(item) }, onLongClick = { rows.onChoose(item) })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (rows.choosing) ChoiceMark(chosen) else Icon(Icons.Attach, null, tint = MaterialTheme.colorScheme.primary)
                    if (rows.curtained(item)) {
                        Curtain(Modifier.weight(1f))
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(
                                meta.name ?: stringResource(Res.string.library_file),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${sizeLabel(meta.size)} · ${dateLabel(item.day, months)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A weight as people read it: «640 КБ», «3,4 МБ», «12 ГБ». */
@Composable
internal fun sizeLabel(bytes: Long): String {
    val units = stringArrayResource(Res.array.size_units)
    val (number, unit) = sizeParts(bytes)
    return "$number${Char(0x00A0)}${units.getOrElse(unit) { "" }}"
}

/** The number and the unit's index (0 bytes, 1 KB, 2 MB, 3 GB, 4 TB): one decimal below ten, with a comma. */
internal fun sizeParts(bytes: Long): Pair<String, Int> {
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1024 && unit < 4) {
        value /= 1024
        unit++
    }
    val tenths = (value * 10).roundToLong()
    val number = if (unit == 0 || value >= 10 || tenths % 10 == 0L) value.roundToLong().toString() else "${tenths / 10},${tenths % 10}"
    return number to unit
}

private fun Set<AttachmentKey>.toggle(key: AttachmentKey) = if (key in this) this - key else this + key
