package app.benbow.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.gentle_count
import app.benbow.app.resources.library_chosen
import app.benbow.app.resources.library_file
import app.benbow.app.resources.media_audio
import app.benbow.app.resources.media_kind_drawing
import app.benbow.app.resources.media_kind_image
import app.benbow.app.resources.media_kind_video
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.restore
import app.benbow.app.resources.trash
import app.benbow.app.resources.trash_attachments
import app.benbow.app.resources.trash_board
import app.benbow.app.resources.trash_calendar
import app.benbow.app.resources.trash_calendar_item
import app.benbow.app.resources.trash_category_item
import app.benbow.app.resources.trash_choose
import app.benbow.app.resources.trash_choose_all
import app.benbow.app.resources.trash_choose_none
import app.benbow.app.resources.trash_chosen_up_to
import app.benbow.app.resources.trash_days_ago
import app.benbow.app.resources.trash_empty
import app.benbow.app.resources.trash_free_choose
import app.benbow.app.resources.trash_free_pick
import app.benbow.app.resources.trash_free_text
import app.benbow.app.resources.trash_free_title
import app.benbow.app.resources.trash_from_entry
import app.benbow.app.resources.trash_heaviest
import app.benbow.app.resources.trash_helper_big
import app.benbow.app.resources.trash_helper_free
import app.benbow.app.resources.trash_helper_old
import app.benbow.app.resources.trash_helper_video
import app.benbow.app.resources.trash_hint
import app.benbow.app.resources.trash_journal
import app.benbow.app.resources.trash_latest
import app.benbow.app.resources.trash_media_total
import app.benbow.app.resources.trash_oldest
import app.benbow.app.resources.trash_purge
import app.benbow.app.resources.trash_tag_item
import app.benbow.app.resources.trash_tags
import app.benbow.app.resources.trash_today
import app.benbow.app.resources.trash_yesterday
import app.benbow.app.resources.voice_note
import app.benbow.client.EntrySummary
import app.benbow.client.LibraryItem
import app.benbow.client.TrashMedia
import app.benbow.client.TrashWeight
import app.benbow.client.boardTrash
import app.benbow.client.mediaInTrash
import app.benbow.client.restoreCategory
import app.benbow.client.restoreTag
import app.benbow.client.takenOff
import app.benbow.client.trashItems
import app.benbow.client.trashedTags
import app.benbow.core.api.AttachmentRef
import app.benbow.core.api.PurgeRequest
import app.benbow.ui.Hinted
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** How the trash lists its entries, notes and attachments: the latest thrown away first, the longest there first, the heaviest. */
internal enum class TrashOrder(val label: StringResource) {
    Latest(Res.string.trash_latest),
    Oldest(Res.string.trash_oldest),
    Heaviest(Res.string.trash_heaviest),
    ;

    fun <T> of(items: List<T>, since: (T) -> Long?, bytes: (T) -> Long): List<T> = when (this) {
        Latest -> items.sortedByDescending { since(it) ?: 0 }
        Oldest -> items.sortedBy { since(it) ?: Long.MAX_VALUE }
        Heaviest -> items.sortedWith(compareByDescending<T> { bytes(it) }.thenByDescending { since(it) ?: 0 })
    }
}

/**
 * The journal's trash with what each entry weighs and how long it has been there, then the attachments taken off
 * entries (ADR-041: they wait here with their files), the board's notes, tags and categories (ADR-032: their entries
 * kept them all along), and the calendar's (ADR-027). Everything comes back from here; «Выбрать» then «Удалить навсегда»
 * deletes chosen entries and attachments for good, through the server's count and a held button ([PurgeSheet]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashScreen(services: AppServices, nav: Navigator) {
    val entries by services.repo.trash().collectAsStateWithLifecycle(null)
    // The board's notes in a part of their own (ADR-040), never among the journal's entries.
    val notes by remember { services.repo.boardTrash() }.collectAsStateWithLifecycle(emptyList())
    val off by remember { services.repo.takenOff() }.collectAsStateWithLifecycle(emptyList())
    val media by remember { services.repo.mediaInTrash() }.collectAsStateWithLifecycle(TrashMedia())
    val events by services.calendar.trashedEvents().collectAsStateWithLifecycle(emptyList())
    val calendars by services.calendar.trashedCalendars().collectAsStateWithLifecycle(emptyList())
    val tags by services.repo.trashedTags().collectAsStateWithLifecycle(emptyList())
    val categories = LocalTags.current.trashedCategories
    var order by rememberSaveable { mutableStateOf(TrashOrder.Latest) }
    var opened by remember { mutableStateOf(setOf<String>()) }
    val gentle = LocalGentle.current
    val now = remember { services.now() }
    // Choosing (ADR-041): "e:<entry>" and "a:<entry>/<attachment>"; nothing final happens before the sheet's held button.
    var choosing by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var purging by remember { mutableStateOf<PurgeRequest?>(null) }
    var freeing by remember { mutableStateOf(false) }
    // Everything waiting here with its kind, for the «Видео» helper (an entry holding a video counts).
    val waiting by remember { services.repo.trashItems() }.collectAsStateWithLifecycle(null)
    val toggle = { key: String -> chosen = if (key in chosen) chosen - key else chosen + key }
    // Everything that can be chosen, for «Выбрать все» (owner): the journal's entries, the board's notes, the attachments.
    val everything = remember(entries, notes, off) {
        entries.orEmpty().map { "e:" + it.id }.toSet() + notes.map { "e:" + it.id } +
            off.map { "a:" + it.entryId + "/" + it.attachment.meta.id }
    }
    val stopChoosing = {
        choosing = false
        chosen = emptySet()
    }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = choosing) { stopChoosing() }
    Scaffold(topBar = {
        if (!choosing) {
            TopAppBar(
                navigationIcon = { BackButton(nav::back) },
                title = { Text(stringResource(Res.string.trash)) },
                actions = {
                    val any = entries.orEmpty().isNotEmpty() || notes.isNotEmpty() || off.isNotEmpty()
                    if (any) TextButton({ choosing = true }, Modifier.hand()) { Text(stringResource(Res.string.trash_choose)) }
                },
            )
        } else {
            TopAppBar(
                navigationIcon = { IconButton(stopChoosing, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.cancel)) } },
                title = { Text(stringResource(Res.string.library_chosen, chosen.size)) },
                actions = {
                    val all = everything.isNotEmpty() && chosen.containsAll(everything)
                    val hint = stringResource(if (all) Res.string.trash_choose_none else Res.string.trash_choose_all)
                    Hinted(hint) {
                        IconButton({ chosen = if (all) emptySet() else everything }, Modifier.hand()) {
                            Icon(Icons.SelectAll, hint, tint = if (all) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                    }
                    IconButton({
                        val keys = chosen
                        services.scope.launch {
                            keys.forEach { key ->
                                if (key.startsWith("e:")) services.repo.restore(key.drop(2))
                                if (key.startsWith("a:")) key.drop(2).split('/').let { services.repo.restoreAttachment(it[0], it[1]) }
                            }
                        }
                        stopChoosing()
                    }, Modifier.hand(), enabled = chosen.isNotEmpty()) { Icon(Icons.Undo, stringResource(Res.string.restore)) }
                    IconButton({
                        purging = PurgeRequest(
                            entries = chosen.filter { it.startsWith("e:") }.map { it.drop(2) },
                            attachments = chosen.filter { it.startsWith("a:") }.map { key ->
                                key.drop(2).split('/').let { AttachmentRef(it[0], it[1]) }
                            },
                        )
                    }, Modifier.hand(), enabled = chosen.isNotEmpty()) {
                        Icon(Icons.Delete, stringResource(Res.string.trash_purge), tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }
    }) { padding ->
        if (freeing) {
            val all = remember(entries, notes, off, media, waiting) {
                val weights = { id: String -> media.entries[id]?.bytes ?: 0 }
                entries.orEmpty().map { TrashPick("e:" + it.id, weights(it.id), null, false) } +
                    notes.map { TrashPick("e:" + it.id, weights(it.id), null, false) } +
                    off.map { TrashPick("a:" + it.entryId + "/" + it.attachment.meta.id, it.size, it.since, false) }
            }
            FreeDialog(all, onDismiss = { freeing = false }) { keys ->
                freeing = false
                chosen = keys
                choosing = true
            }
        }
        purging?.let { request ->
            PurgeSheet(services, request, onDone = { done ->
                purging = null
                stopChoosing()
                services.purged(done)
            }, onDismiss = { purging = null })
        }
        val items = entries ?: return@Scaffold
        val listed = items.isNotEmpty() || notes.isNotEmpty() || off.isNotEmpty()
        if (!listed && events.isEmpty() && calendars.isEmpty() && tags.isEmpty() && categories.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.trash_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val weight = { entry: EntrySummary -> media.entries[entry.id] ?: TrashWeight(0, null) }
        val journal = remember(items, media, order) { order.of(items, { weight(it).since }, { weight(it).bytes }) }
        val board = remember(notes, media, order) { order.of(notes, { weight(it).since }, { weight(it).bytes }) }
        val attachments = remember(off, order) { order.of(off, { it.since }, { it.size }) }
        // What the helpers choose from: every entry, note and attachment here with its weight, age and whether it is video.
        val picks = remember(items, notes, off, media, waiting) {
            val video = waiting.orEmpty().filter { it.attachment.meta.kind == "video" }.mapTo(HashSet()) { it.postId }
            (items + notes).map { TrashPick("e:" + it.id, weight(it).bytes, weight(it).since, it.id in video) } +
                off.map {
                    TrashPick("a:" + it.entryId + "/" + it.attachment.meta.id, it.size, it.since, it.attachment.meta.kind == "video")
                }
        }
        val choose = { keys: Set<String> ->
            chosen = keys
            choosing = true
        }
        val sections = listOf(items, notes, off).count { it.isNotEmpty() }
        // By time, each part under its days (owner): today, yesterday, this week, then months; by weight, one run.
        val zone = remember { TimeZone.currentSystemDefault() }
        val thisYear = remember(now) { Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).year }
        val grouped = order != TrashOrder.Heaviest
        fun <T> runs(list: List<T>, since: (T) -> Long?) = if (grouped) trashRuns(list, now, zone, since) else listOf(null to list)
        val journalRuns = remember(journal, grouped) { runs(journal) { weight(it).since } }
        val attachmentRuns = remember(attachments, grouped) { runs(attachments) { it.since } }
        val boardRuns = remember(board, grouped) { runs(board) { weight(it).since } }
        val groupHeader = @Composable { group: TrashGroup, bytes: Long, keys: List<String> ->
            TrashGroupHeader(trashGroupLabel(group, thisYear), bytes, if (choosing) chosen.containsAll(keys) else null) {
                chosen = if (chosen.containsAll(keys)) chosen - keys.toSet() else chosen + keys
            }
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(Res.string.trash_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            if (listed) {
                item(key = "order") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (media.bytes > 0) {
                            Text(
                                stringResource(Res.string.trash_media_total, sizeLabel(media.bytes)),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TrashOrder.entries.forEach { value ->
                                FilterChip(order == value, { order = value }, { Text(stringResource(value.label)) }, Modifier.hand())
                            }
                        }
                        // Helpers (ADR-041): each chooses at once; nothing final happens before the sheet's held button.
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val old = now - OLD_MILLIS
                            val helper = @Composable { label: StringResource, keys: () -> Set<String> ->
                                val found = keys()
                                AssistChip({
                                    choose(found)
                                }, { Text(stringResource(label)) }, Modifier.hand(), enabled = found.isNotEmpty())
                            }
                            AssistChip({ freeing = true }, { Text(stringResource(Res.string.trash_helper_free)) }, Modifier.hand())
                            helper(Res.string.trash_helper_video) { picks.filter { it.video }.mapTo(LinkedHashSet()) { it.key } }
                            helper(Res.string.trash_helper_old) {
                                picks.filter { (it.since ?: now) < old }.mapTo(LinkedHashSet()) { it.key }
                            }
                            helper(Res.string.trash_helper_big) { picks.filter { it.bytes > BIG_BYTES }.mapTo(LinkedHashSet()) { it.key } }
                        }
                        if (choosing && chosen.isNotEmpty()) {
                            val bytes = picks.filter { it.key in chosen }.sumOf { it.bytes }
                            Text(
                                stringResource(Res.string.trash_chosen_up_to, sizeLabel(bytes)),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            if (items.isNotEmpty() && sections > 1) item(key = "journal") { TrashHeader(stringResource(Res.string.trash_journal)) }
            journalRuns.forEach { (group, run) ->
                if (group != null) {
                    item(key = "gj$group") { groupHeader(group, run.sumOf { weight(it).bytes }, run.map { "e:" + it.id }) }
                }
                items(run, key = { it.id }) { entry ->
                    EntryCard(entry, onClick = { if (choosing) toggle("e:" + entry.id) else nav.open(Route.Editor(entry.id, entry.day)) }) {
                        if (choosing) {
                            ChoiceMark("e:" + entry.id in chosen, Modifier.padding(8.dp))
                        } else {
                            TrashedTrailing(weight(entry), now) { services.scope.launch { services.repo.restore(entry.id) } }
                        }
                    }
                }
            }
            if (off.isNotEmpty()) item(key = "attachments") { TrashHeader(stringResource(Res.string.trash_attachments)) }
            attachmentRuns.forEach { (group, run) ->
                if (group != null) {
                    item(key = "ga$group") {
                        groupHeader(group, run.sumOf { it.size }, run.map { "a:" + it.entryId + "/" + it.attachment.meta.id })
                    }
                }
                items(run, key = { "a" + it.entryId + "/" + it.attachment.meta.id }) { item ->
                    val key = "a:" + item.entryId + "/" + item.attachment.meta.id
                    TakenOffRow(
                        services,
                        item,
                        now,
                        curtained = item.veil != null && gentle && item.postId !in opened,
                        chosen = if (choosing) key in chosen else null,
                        onReveal = { opened = opened + item.postId },
                        onOpen = { if (choosing) toggle(key) else nav.open(Route.Editor(item.postId, item.day, readOnly = true)) },
                        onLongPress = {
                            choosing = true
                            toggle(key)
                        },
                    ) { services.scope.launch { services.repo.restoreAttachment(item.entryId, item.attachment.meta.id) } }
                }
            }
            if (notes.isNotEmpty()) item(key = "board") { TrashHeader(stringResource(Res.string.trash_board)) }
            boardRuns.forEach { (group, run) ->
                if (group != null) {
                    item(key = "gn$group") { groupHeader(group, run.sumOf { weight(it).bytes }, run.map { "e:" + it.id }) }
                }
                items(run, key = { "n" + it.id }) { note ->
                    EntryCard(note, onClick = {
                        if (choosing) toggle("e:" + note.id) else nav.open(Route.Editor(note.id, note.day, board = true))
                    }) {
                        if (choosing) {
                            ChoiceMark("e:" + note.id in chosen, Modifier.padding(8.dp))
                        } else {
                            TrashedTrailing(weight(note), now) { services.scope.launch { services.repo.restore(note.id) } }
                        }
                    }
                }
            }
            if (tags.isNotEmpty() || categories.isNotEmpty()) item(key = "tags") { TrashHeader(stringResource(Res.string.trash_tags)) }
            items(tags, key = { "t" + it.id }) { tag ->
                TrashRow(
                    stringResource(Res.string.trash_tag_item, tag.name),
                    pluralStringResource(Res.plurals.gentle_count, tag.uses, tag.uses),
                ) {
                    services.scope.launch { services.repo.restoreTag(tag.id) }
                }
            }
            items(categories, key = { "g" + it.id }) { category ->
                TrashRow(stringResource(Res.string.trash_category_item, category.name), null) {
                    services.scope.launch { services.repo.restoreCategory(category.id) }
                }
            }
            if (events.isNotEmpty() || calendars.isNotEmpty()) {
                item(key = "calendar") { TrashHeader(stringResource(Res.string.trash_calendar)) }
            }
            items(calendars, key = { "c" + it.id }) { calendar ->
                TrashRow(stringResource(Res.string.trash_calendar_item, calendarName(calendar)), null) {
                    services.scope.launch { services.calendar.restoreCalendar(calendar.id) }
                }
            }
            items(events, key = { "e" + it.id }) { event ->
                TrashRow(event.title, dayLabel(event.day)) { services.scope.launch { services.calendar.restore(event.id) } }
            }
        }
    }
}

/** One thing the helpers may choose: its key ("e:" or "a:"), what it holds, since when it is here, whether it is video. */
internal data class TrashPick(val key: String, val bytes: Long, val since: Long?, val video: Boolean)

/** The heaviest first until they hold at least [target] bytes (empty ones never). */
internal fun heaviestUpTo(picks: List<TrashPick>, target: Long): Set<String> {
    val out = LinkedHashSet<String>()
    var sum = 0L
    for (pick in picks.sortedByDescending { it.bytes }) {
        if (sum >= target || pick.bytes <= 0) break
        out += pick.key
        sum += pick.bytes
    }
    return out
}

/**
 * «Освободить…»: a slider up to everything the trash holds (5 GB to begin with, or all of it if less), and how many of
 * the heaviest that takes; «Выбрать» chooses them. Each holds up to its weight: the server's sheet says what truly goes.
 */
@Composable
private fun FreeDialog(picks: List<TrashPick>, onDismiss: () -> Unit, onChoose: (Set<String>) -> Unit) {
    val total = picks.sumOf { it.bytes }.coerceAtLeast(1)
    var share by remember { mutableFloatStateOf((FREE_DEFAULT.toDouble() / total).coerceAtMost(1.0).toFloat()) }
    val target = (total * share.toDouble()).toLong()
    val keys = remember(picks, target) { heaviestUpTo(picks, target) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.trash_free_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.trash_free_text, sizeLabel(target)))
                Slider(share, { share = it }, Modifier.hand())
                val chosen = picks.filter { it.key in keys }.sumOf { it.bytes }
                Text(
                    stringResource(Res.string.trash_free_pick, keys.size, sizeLabel(chosen)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton({
                onChoose(keys)
            }, Modifier.hand(), enabled = keys.isNotEmpty()) { Text(stringResource(Res.string.trash_free_choose)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}

private const val OLD_MILLIS = 30L * 24 * 3_600_000
private const val BIG_BYTES = 100L * 1024 * 1024
private const val FREE_DEFAULT = 5L * 1024 * 1024 * 1024

@Composable
private fun TrashHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun TrashRow(title: String, detail: String?, onRestore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(onRestore, modifier = Modifier.hand()) { Text(stringResource(Res.string.restore)) }
    }
}

/** An entry's weight (when it holds files) and how long ago it went to the trash, over its «Восстановить». */
@Composable
private fun TrashedTrailing(weight: TrashWeight, now: Long, onRestore: () -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        val line = listOfNotNull(weight.bytes.takeIf { it > 0 }?.let { sizeLabel(it) }, weight.since?.let { trashAge(it, now) })
        if (line.isNotEmpty()) {
            Text(
                line.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        TextButton(onRestore, modifier = Modifier.hand()) { Text(stringResource(Res.string.restore)) }
    }
}

/**
 * An attachment taken off its entry: its picture (frosted while its entry is veiled, until a tap), what it is, its
 * weight, the entry's day and how long ago; a tap opens the entry, «Восстановить» puts it back on it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TakenOffRow(
    services: AppServices,
    item: LibraryItem,
    now: Long,
    curtained: Boolean,
    /** Null while not choosing; else whether this one is chosen. */
    chosen: Boolean?,
    onReveal: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onRestore: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val meta = item.attachment.meta
    val months = stringArrayResource(Res.array.months_genitive)
    Row(
        Modifier.fillMaxWidth().hand().combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh)
                .then(if (curtained) Modifier.clickable(onClick = onReveal) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val visual = item.attachment.visual
            when {
                curtained -> Icon(Icons.Eye, null, tint = colors.onSurfaceVariant)

                meta.kind == "drawing" -> DrawingPicture(services.media, item.attachment, Modifier.fillMaxSize().padding(4.dp))

                visual != null -> {
                    val load by rememberMediaFile(services.media, visual)
                    (load as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }

                item.attachment.isSound -> Icon(Icons.Mic, null, tint = colors.primary)

                else -> Icon(Icons.Attach, null, tint = colors.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                meta.name ?: kindLabel(meta.kind),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val line = listOfNotNull(
                sizeLabel(meta.size),
                stringResource(Res.string.trash_from_entry, dateLabel(item.day, months)),
                item.since?.let { trashAge(it, now) },
            )
            Text(line.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (chosen != null) {
            ChoiceMark(chosen, Modifier.padding(8.dp))
        } else {
            TextButton(onRestore, modifier = Modifier.hand()) { Text(stringResource(Res.string.restore)) }
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = stringResource(
    when (kind) {
        "image" -> Res.string.media_kind_image
        "video" -> Res.string.media_kind_video
        "drawing" -> Res.string.media_kind_drawing
        "voice" -> Res.string.voice_note
        "audio" -> Res.string.media_audio
        else -> Res.string.library_file
    },
)

/** How long ago something went to the trash: «сегодня», «вчера», «5 дней назад». */
@Composable
private fun trashAge(since: Long, now: Long): String {
    val days = ((now - since) / DAY_MILLIS).toInt().coerceAtLeast(0)
    return when (days) {
        0 -> stringResource(Res.string.trash_today)
        1 -> stringResource(Res.string.trash_yesterday)
        else -> pluralStringResource(Res.plurals.trash_days_ago, days, days)
    }
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
