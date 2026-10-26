package app.benbow.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.months_nominative
import app.benbow.app.resources.storage
import app.benbow.app.resources.storage_backups
import app.benbow.app.resources.storage_counted
import app.benbow.app.resources.storage_database
import app.benbow.app.resources.storage_device
import app.benbow.app.resources.storage_device_freeable
import app.benbow.app.resources.storage_device_line
import app.benbow.app.resources.storage_device_nothing
import app.benbow.app.resources.storage_disk
import app.benbow.app.resources.storage_disk_free
import app.benbow.app.resources.storage_disk_other
import app.benbow.app.resources.storage_disk_ours
import app.benbow.app.resources.storage_files
import app.benbow.app.resources.storage_free_browser
import app.benbow.app.resources.storage_free_computer
import app.benbow.app.resources.storage_free_phone
import app.benbow.app.resources.storage_heaviest
import app.benbow.app.resources.storage_in_entries
import app.benbow.app.resources.storage_in_trash
import app.benbow.app.resources.storage_kind_drawing
import app.benbow.app.resources.storage_kind_file
import app.benbow.app.resources.storage_kind_image
import app.benbow.app.resources.storage_kind_preview
import app.benbow.app.resources.storage_kind_sound
import app.benbow.app.resources.storage_kind_video
import app.benbow.app.resources.storage_kinds
import app.benbow.app.resources.storage_mirror
import app.benbow.app.resources.storage_mirror_free
import app.benbow.app.resources.storage_mirror_none
import app.benbow.app.resources.storage_month_line
import app.benbow.app.resources.storage_months
import app.benbow.app.resources.storage_never
import app.benbow.app.resources.storage_nothing
import app.benbow.app.resources.storage_refresh
import app.benbow.app.resources.storage_snapshots
import app.benbow.app.resources.storage_stale
import app.benbow.app.resources.storage_state_live
import app.benbow.app.resources.storage_state_removed
import app.benbow.app.resources.storage_state_trash
import app.benbow.app.resources.storage_state_unreferenced
import app.benbow.app.resources.storage_states
import app.benbow.app.resources.storage_unreferenced
import app.benbow.app.resources.storage_unreferenced_line
import app.benbow.app.resources.storage_unreferenced_more
import app.benbow.app.resources.storage_waiting
import app.benbow.app.resources.trash_purge
import app.benbow.client.LibraryItem
import app.benbow.client.LibrarySection
import app.benbow.client.library
import app.benbow.client.trashItems
import app.benbow.core.api.PurgeRequest
import app.benbow.core.api.StorageKind
import app.benbow.core.api.StorageMonth
import app.benbow.core.api.StorageReport
import app.benbow.core.api.StorageState
import app.benbow.core.api.UnreferencedFile
import app.benbow.ui.mouseSwipe
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// The kinds wear the charts' categorical slots in a fixed order (the dataviz reference palette, validated for colour
// blindness and contrast against light and dark surfaces); the states a neutral ramp, so the two never read as one.
private val KIND_LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300).map { Color(it) }
private val KIND_DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300).map { Color(it) }

private val KIND_NAMES = mapOf(
    StorageKind.VIDEO to Res.string.storage_kind_video,
    StorageKind.IMAGE to Res.string.storage_kind_image,
    StorageKind.SOUND to Res.string.storage_kind_sound,
    StorageKind.DRAWING to Res.string.storage_kind_drawing,
    StorageKind.FILE to Res.string.storage_kind_file,
    StorageKind.PREVIEW to Res.string.storage_kind_preview,
)

private val STATE_NAMES = mapOf(
    StorageState.LIVE to Res.string.storage_state_live,
    StorageState.TRASH to Res.string.storage_state_trash,
    StorageState.REMOVED to Res.string.storage_state_removed,
    StorageState.UNREFERENCED to Res.string.storage_state_unreferenced,
)

@Composable
private fun kindColor(kind: String): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return (if (dark) KIND_DARK else KIND_LIGHT)[StorageKind.ALL.indexOf(kind).coerceAtLeast(0)]
}

@Composable
private fun stateColor(state: String): Color {
    val colors = MaterialTheme.colorScheme
    return when (state) {
        StorageState.LIVE -> colors.onSurface
        StorageState.TRASH -> colors.onSurfaceVariant
        StorageState.REMOVED -> colors.outline
        else -> colors.outlineVariant
    }
}

/**
 * «Место» (ADR-041, 7.18 M4): what the files weigh on the server, each file once (the server's count, the last one kept
 * here while it is away), by kind, by how alive and by month; the heaviest as a map of their own pictures; what this
 * device keeps and «Освободить на телефоне» (only what the server holds and has backed up goes); the backups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageScreen(services: AppServices, nav: Navigator) {
    var report by remember { mutableStateOf(services.storage.last()) }
    var fresh by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var unreferenced by remember { mutableStateOf<List<UnreferencedFile>>(emptyList()) }
    var purging by remember { mutableStateOf<PurgeRequest?>(null) }
    LaunchedEffect(attempt) {
        loading = true
        try {
            report = services.storageReader()
            fresh = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fresh = false
        }
        unreferenced = try {
            services.unreferencedReader()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        loading = false
    }
    purging?.let { request ->
        PurgeSheet(services, request, onDone = { done ->
            purging = null
            services.purged(done)
            attempt++
        }, onDismiss = { purging = null })
    }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { BackButton(nav::back) },
            title = { Text(stringResource(Res.string.storage)) },
            actions = {
                IconButton({
                    attempt++
                }, Modifier.hand(), enabled = !loading) { Icon(Icons.Sync, stringResource(Res.string.storage_refresh)) }
            },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hero") { HeroCard(report, fresh, loading) }
            report?.let { counted ->
                if (counted.bytes > 0) {
                    if (counted.kinds.values.any { it > 0 }) {
                        item(key = "kinds") {
                            val parts = StorageKind.ALL.map {
                                Part(
                                    kindColor(it),
                                    stringResource(KIND_NAMES.getValue(it)),
                                    counted.kinds[it] ?: 0,
                                )
                            }
                            BreakdownCard(stringResource(Res.string.storage_kinds), parts, counted.bytes)
                        }
                    }
                    item(key = "states") {
                        val parts = StorageState.ALL.map {
                            Part(
                                stateColor(it),
                                stringResource(STATE_NAMES.getValue(it)),
                                counted.states[it] ?: 0,
                            )
                        }
                        BreakdownCard(stringResource(Res.string.storage_states), parts, counted.bytes) {
                            // Deleted for good, waiting out the server's quarantine (ADR-041): not in the total any more.
                            if (counted.waiting > 0) {
                                val months = stringArrayResource(Res.array.months_genitive)
                                val day = counted.waitingUntil?.let { dateLabel(dayOf(it), months) }.orEmpty()
                                Text(
                                    stringResource(Res.string.storage_waiting, sizeLabel(counted.waiting), day),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (unreferenced.isNotEmpty()) {
                    item(key = "unreferenced") {
                        UnreferencedCard(unreferenced) { purging = PurgeRequest(unreferenced = unreferenced.map { it.sha256 }) }
                    }
                }
                if (counted.months.isNotEmpty()) item(key = "months") { MonthsCard(counted.months) }
            }
            item(key = "heaviest") { HeaviestCard(services, nav) }
            item(key = "device") { DeviceCard(services) }
            report?.let { counted -> item(key = "backups") { BackupsCard(counted) } }
        }
    }
}

private class Part(val color: Color, val name: String, val bytes: Long)

@Composable
private fun StorageCard(title: String?, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

/** The total in big figures, how many files and when the server counted them, and the disk they are on. */
@Composable
private fun HeroCard(report: StorageReport?, fresh: Boolean, loading: Boolean) {
    StorageCard(null) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        if (report == null) {
            Text(if (loading) "…" else stringResource(Res.string.storage_never), color = muted)
            return@StorageCard
        }
        Text(sizeLabel(report.bytes), style = MaterialTheme.typography.displaySmall)
        val counted = if (report.counted >
            0
        ) {
            " · " + stringResource(Res.string.storage_counted, dateTimeLabel(report.counted).replaceFirstChar { it.lowercase() })
        } else {
            ""
        }
        Text(pluralStringResource(Res.plurals.storage_files, report.files, report.files) + counted, color = muted)
        if (!fresh && !loading) Text(stringResource(Res.string.storage_stale), color = MaterialTheme.colorScheme.error)
        report.disk?.let { disk ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.storage_disk), style = MaterialTheme.typography.titleSmall)
            val colors = MaterialTheme.colorScheme
            val parts = listOf(
                Part(colors.primary, stringResource(Res.string.storage_disk_ours), report.bytes),
                Part(
                    colors.outline,
                    stringResource(Res.string.storage_disk_other),
                    (disk.total - disk.free - report.bytes).coerceAtLeast(0),
                ),
                Part(colors.surfaceContainerHighest, stringResource(Res.string.storage_disk_free), disk.free),
            )
            StackedBar(parts, disk.total)
            parts.forEach { LegendRow(it, disk.total) }
        }
    }
}

/** One bar of every part in proportion, then a row for each: its colour, name, weight and share (none hides in a colour). */
@Composable
private fun BreakdownCard(title: String, parts: List<Part>, total: Long, below: @Composable () -> Unit = {}) {
    StorageCard(title) {
        StackedBar(parts, total)
        parts.filter { it.bytes > 0 }.forEach { LegendRow(it, total) }
        below()
    }
}

/** Parts side by side in proportion, 2 dp of the track between them, the ends rounded. */
@Composable
private fun StackedBar(parts: List<Part>, total: Long, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val gap = with(LocalDensity.current) { 2.dp.toPx() }
    val shown = parts.filter { it.bytes > 0 }
    Canvas(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp))) {
        drawRect(track)
        if (total <= 0 || shown.isEmpty()) return@Canvas
        val room = size.width - gap * (shown.size - 1)
        var x = 0f
        for (part in shown) {
            val width = (room * part.bytes.toDouble() / total).toFloat().coerceAtLeast(1f)
            drawRect(part.color, topLeft = Offset(x, 0f), size = Size(width, size.height))
            x += width + gap
        }
    }
}

@Composable
private fun LegendRow(part: Part, total: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(part.color))
        Spacer(Modifier.width(10.dp))
        Text(
            part.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(sizeLabel(part.bytes), style = MaterialTheme.typography.labelLarge)
        val share = if (total > 0) (part.bytes * 100.0 / total).roundToLong() else 0
        Text(
            if (share == 0L && part.bytes > 0) "<1 %" else "$share %",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

/**
 * Every month from the first to the last as a column of its kinds, on one axis, the newest at the right; a tap picks a
 * month and its numbers show under the chart (the newest is picked to begin with).
 */
@Composable
private fun MonthsCard(months: List<StorageMonth>) {
    StorageCard(stringResource(Res.string.storage_months)) {
        val all = remember(months) { continuous(months) }
        // The newest month to begin with: the chart opens at its right end.
        var picked by rememberSaveable(all) { mutableIntStateOf(all.lastIndex) }
        val top = all.maxOf { it.kinds.values.sum() }.coerceAtLeast(1)
        val names = stringArrayResource(Res.array.months_nominative)
        Text(sizeLabel(top), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val list = rememberLazyListState(initialFirstVisibleItemIndex = all.lastIndex.coerceAtLeast(0))
        LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.mouseSwipe(list).fillMaxWidth()) {
            itemsIndexed(all, key = { _, month -> month.month }) { index, month ->
                val number = month.month.drop(5).toIntOrNull() ?: 1
                val label = names.getOrNull(number - 1)?.take(3)?.lowercase().orEmpty()
                Column(
                    Modifier.width(30.dp).alpha(if (index == picked) 1f else 0.5f).hand().clickable { picked = index }
                        .semantics { contentDescription = month.month },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MonthColumn(month, top, CHART_HEIGHT)
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text(
                        if (number == 1 || index == 0) month.month.take(4) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        all.getOrNull(picked)?.let { month ->
            val total = month.kinds.values.sum()
            val number = month.month.drop(5).toIntOrNull() ?: 1
            val title = "${names.getOrNull(number - 1).orEmpty()} ${month.month.take(4)}"
            Text(stringResource(Res.string.storage_month_line, title, sizeLabel(total)), style = MaterialTheme.typography.titleSmall)
            StorageKind.ALL.filter { (month.kinds[it] ?: 0) > 0 }.forEach { kind ->
                LegendRow(Part(kindColor(kind), stringResource(KIND_NAMES.getValue(kind)), month.kinds.getValue(kind)), total)
            }
        }
    }
}

private val CHART_HEIGHT = 120.dp

/** One month's column: its kinds stacked from the baseline, 2 dp apart, the top rounded. */
@Composable
private fun MonthColumn(month: StorageMonth, top: Long, height: Dp) {
    val parts = StorageKind.ALL.reversed().map { kindColor(it) to (month.kinds[it] ?: 0) }.filter { it.second > 0 }
    val gap = with(LocalDensity.current) { 2.dp.toPx() }
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.width(18.dp).height(height)) {
        drawRect(baseline, topLeft = Offset(-size.width, size.height - 1f), size = Size(size.width * 3, 1f))
        val total = parts.sumOf { it.second }
        if (total <= 0) return@Canvas
        val full = (size.height * total.toDouble() / top).toFloat()
        val room = full - gap * (parts.size - 1).coerceAtLeast(0)
        var y = size.height
        for ((color, bytes) in parts) {
            val h = (room * bytes.toDouble() / total).toFloat().coerceAtLeast(1f)
            drawRoundRect(
                color,
                topLeft = Offset(0f, y - h),
                size = Size(size.width, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
            y -= h + gap
        }
    }
}

/** Every month from the first to the last, the empty ones too: time runs evenly along the axis. */
internal fun continuous(months: List<StorageMonth>): List<StorageMonth> {
    if (months.isEmpty()) return emptyList()
    val byMonth = months.associateBy { it.month }
    val (firstYear, firstMonth) = months.first().month.split('-').map { it.toInt() }
    val (lastYear, lastMonth) = months.last().month.split('-').map { it.toInt() }
    return generateSequence(firstYear to firstMonth) { (y, m) -> if (m == 12) y + 1 to 1 else y to m + 1 }
        .takeWhile { (y, m) -> y < lastYear || (y == lastYear && m <= lastMonth) }
        .map { (y, m) -> "$y-${m.toString().padStart(2, '0')}" }
        .map { byMonth[it] ?: StorageMonth(it, emptyMap()) }
        .toList()
}

/**
 * The heaviest files as a map: each a tile as big as its weight, showing its own picture (an eye while its entry is
 * veiled and Бережно is on, until a tap), with its weight; «В записях» or «В корзине». Counted on this device.
 */
@Composable
private fun HeaviestCard(services: AppServices, nav: Navigator) {
    StorageCard(stringResource(Res.string.storage_heaviest)) {
        var trash by rememberSaveable { mutableStateOf(false) }
        val live by remember { services.repo.library() }.collectAsStateWithLifecycle(null)
        val waiting by remember { services.repo.trashItems() }.collectAsStateWithLifecycle(null)
        val items = remember(live, waiting, trash) {
            (if (trash) waiting else live?.sortedByDescending { it.size })?.distinctBy { it.attachment.meta.sha256 }?.take(HEAVIEST)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!trash, { trash = false }, { Text(stringResource(Res.string.storage_in_entries)) }, Modifier.hand())
            FilterChip(trash, { trash = true }, { Text(stringResource(Res.string.storage_in_trash)) }, Modifier.hand())
        }
        val shown = items ?: return@StorageCard
        if (shown.isEmpty()) {
            Text(stringResource(Res.string.storage_nothing), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@StorageCard
        }
        val gentle = LocalGentle.current
        var opened by remember { mutableStateOf(setOf<String>()) }
        Treemap(services, shown, isOpen = { !it.curtained(gentle) || it.postId in opened }) { item ->
            when {
                item.curtained(gentle) && item.postId !in opened -> opened = opened + item.postId

                !trash && (item.section == LibrarySection.Visual || item.section == LibrarySection.Drawings) ->
                    nav.open(Route.LibraryMedia(LibraryView(item.section, bySize = true, notes = true), item.viewerKey))

                else -> nav.open(Route.Editor(item.postId, item.day, readOnly = true))
            }
        }
    }
}

private const val HEAVIEST = 24

@Composable
private fun Treemap(services: AppServices, items: List<LibraryItem>, isOpen: (LibraryItem) -> Boolean, onTap: (LibraryItem) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp)) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val tiles = remember(items, width, height) { squarify(items.map { it.size.toDouble() }, width, height) }
        val gap = with(density) { 1.dp.toPx() }
        for (tile in tiles) {
            val item = items[tile.index]
            val w = with(density) { (tile.w - 2 * gap).coerceAtLeast(0f).toDp() }
            val h = with(density) { (tile.h - 2 * gap).coerceAtLeast(0f).toDp() }
            Box(
                Modifier.offset { IntOffset((tile.x + gap).roundToInt(), (tile.y + gap).roundToInt()) }.size(w, h)
                    .clip(RoundedCornerShape(4.dp)).background(kindColor(StorageKind.of(item.attachment.meta.kind)).copy(alpha = 0.35f))
                    .semantics { contentDescription = item.attachment.meta.name ?: item.attachment.meta.kind }
                    .hand().clickable { onTap(item) },
            ) {
                val visual = item.attachment.visual
                when {
                    !isOpen(
                        item,
                    ) -> Icon(Icons.Eye, null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)

                    item.attachment.meta.kind == "drawing" ->
                        DrawingPicture(
                            services.media,
                            item.attachment,
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        )

                    visual != null -> {
                        val load by rememberMediaFile(services.media, visual)
                        (load as? MediaLoad.Ready)?.let {
                            AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }

                    else -> Icon(
                        if (item.attachment.isSound) Icons.Mic else Icons.Attach,
                        null,
                        Modifier.align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (w > 56.dp && h > 30.dp) {
                    Text(
                        sizeLabel(item.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                            .background(
                                Color.Black.copy(alpha = 0.55f),
                                RoundedCornerShape(6.dp),
                            ).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** One tile of a [squarify] layout: the weight at [index] in px. */
internal data class Tile(val index: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * A squarified treemap (Bruls, Huizing, van Wijk): [weights] (heaviest first) as rectangles filling [width] × [height],
 * each as large as its weight, laid in rows along the shorter side while the row's worst aspect ratio keeps improving.
 */
internal fun squarify(weights: List<Double>, width: Float, height: Float): List<Tile> {
    val total = weights.sum()
    if (total <= 0 || width <= 0 || height <= 0) return emptyList()
    val areas = weights.map { it / total * width * height }
    val tiles = ArrayList<Tile>()
    var x = 0f
    var y = 0f
    var w = width
    var h = height
    var i = 0
    fun worst(sum: Double, smallest: Double, largest: Double, side: Double): Double =
        max(side * side * largest / (sum * sum), sum * sum / (side * side * smallest))
    while (i < areas.size) {
        val side = min(w, h).toDouble()
        var j = i + 1
        var sum = areas[i]
        while (j < areas.size) {
            val grown = sum + areas[j]
            val before = worst(sum, areas.subList(i, j).min(), areas.subList(i, j).max(), side)
            val after = worst(grown, min(areas.subList(i, j).min(), areas[j]), max(areas.subList(i, j).max(), areas[j]), side)
            if (after > before) break
            sum = grown
            j++
        }
        val thickness = (sum / side).toFloat()
        var along = 0f
        for (k in i until j) {
            val length = (areas[k] / thickness).toFloat()
            tiles += if (w >= h) Tile(k, x, y + along, thickness, length) else Tile(k, x + along, y, length, thickness)
            along += length
        }
        if (w >= h) {
            x += thickness
            w -= thickness
        } else {
            y += thickness
            h -= thickness
        }
        i = j
    }
    return tiles
}

/**
 * «Без записи»: files on the server no entry references, placed thirty days ago or more (a file may come before its
 * link, so younger ones aren't offered). The heaviest few by name, and «Удалить навсегда» through the same sheet as
 * the trash's: the server counts again, and the files wait out the quarantine.
 */
@Composable
private fun UnreferencedCard(files: List<UnreferencedFile>, onPurge: () -> Unit) {
    StorageCard(stringResource(Res.string.storage_unreferenced)) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            pluralStringResource(Res.plurals.storage_unreferenced_line, files.size, files.size, sizeLabel(files.sumOf { it.size })),
            color = muted,
        )
        val months = stringArrayResource(Res.array.months_genitive)
        files.take(UNREFERENCED_SHOWN).forEach { file ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${sizeLabel(file.size)} · ${dateLabel(dayOf(file.since), months)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
            }
        }
        if (files.size >
            UNREFERENCED_SHOWN
        ) {
            Text(stringResource(Res.string.storage_unreferenced_more, files.size - UNREFERENCED_SHOWN), color = muted)
        }
        TextButton(onPurge, Modifier.hand()) { Text(stringResource(Res.string.trash_purge), color = MaterialTheme.colorScheme.error) }
    }
}

private const val UNREFERENCED_SHOWN = 5

/** What this device keeps against its cap, what may go now, and the button that lets it go. */
@Composable
private fun DeviceCard(services: AppServices) {
    StorageCard(stringResource(Res.string.storage_device)) {
        val usage by remember { services.media.usage() }.collectAsStateWithLifecycle(null)
        val now = usage ?: return@StorageCard
        val colors = MaterialTheme.colorScheme
        Text(stringResource(Res.string.storage_device_line, sizeLabel(now.total), sizeLabel(services.mediaCacheBytes)))
        val track = max(now.total, services.mediaCacheBytes)
        StackedBar(listOf(Part(colors.onSurfaceVariant, "", now.total - now.freeable), Part(colors.primary, "", now.freeable)), track)
        Text(
            if (now.freeable > 0) {
                stringResource(Res.string.storage_device_freeable, sizeLabel(now.freeable))
            } else {
                stringResource(Res.string.storage_device_nothing)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        val scope = rememberCoroutineScope()
        var freeing by remember { mutableStateOf(false) }
        Button(
            {
                freeing = true
                scope.launch {
                    try {
                        services.freeDevice()
                    } finally {
                        freeing = false
                    }
                }
            },
            enabled = now.freeable > 0 && !freeing,
            modifier = Modifier.hand(),
        ) { Text(stringResource(freeLabel(services.platform))) }
    }
}

private fun freeLabel(platform: String): StringResource = when (platform) {
    "android" -> Res.string.storage_free_phone
    "web" -> Res.string.storage_free_browser
    else -> Res.string.storage_free_computer
}

@Composable
private fun BackupsCard(report: StorageReport) {
    StorageCard(stringResource(Res.string.storage_backups)) {
        KeyValue(stringResource(Res.string.storage_database), sizeLabel(report.database))
        KeyValue(stringResource(Res.string.storage_snapshots), sizeLabel(report.snapshots))
        KeyValue(
            stringResource(Res.string.storage_mirror),
            report.mirror?.let { stringResource(Res.string.storage_mirror_free, sizeLabel(it.free), sizeLabel(it.total)) }
                ?: stringResource(Res.string.storage_mirror_none),
        )
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
