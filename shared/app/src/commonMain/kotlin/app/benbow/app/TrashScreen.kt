package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.benbow.app.resources.Res
import app.benbow.app.resources.gentle_count
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
import app.benbow.app.resources.trash_days_ago
import app.benbow.app.resources.trash_empty
import app.benbow.app.resources.trash_from_entry
import app.benbow.app.resources.trash_heaviest
import app.benbow.app.resources.trash_hint
import app.benbow.app.resources.trash_journal
import app.benbow.app.resources.trash_latest
import app.benbow.app.resources.trash_media_total
import app.benbow.app.resources.trash_oldest
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
import app.benbow.client.trashedTags
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
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
 * kept them all along), and the calendar's (ADR-027). Nothing here is gone: everything comes back from here.
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
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.trash)) })
    }) { padding ->
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
        val sections = listOf(items, notes, off).count { it.isNotEmpty() }
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
                    }
                }
            }
            if (items.isNotEmpty() && sections > 1) item(key = "journal") { TrashHeader(stringResource(Res.string.trash_journal)) }
            items(journal, key = { it.id }) { entry ->
                EntryCard(entry, onClick = { nav.open(Route.Editor(entry.id, entry.day)) }) {
                    TrashedTrailing(weight(entry), now) { services.scope.launch { services.repo.restore(entry.id) } }
                }
            }
            if (off.isNotEmpty()) item(key = "attachments") { TrashHeader(stringResource(Res.string.trash_attachments)) }
            items(attachments, key = { "a" + it.entryId + "/" + it.attachment.meta.id }) { item ->
                TakenOffRow(
                    services,
                    item,
                    now,
                    curtained = item.veil != null && gentle && item.postId !in opened,
                    onReveal = { opened = opened + item.postId },
                    onOpen = { nav.open(Route.Editor(item.postId, item.day, readOnly = true)) },
                ) { services.scope.launch { services.repo.restoreAttachment(item.entryId, item.attachment.meta.id) } }
            }
            if (notes.isNotEmpty()) item(key = "board") { TrashHeader(stringResource(Res.string.trash_board)) }
            items(board, key = { "n" + it.id }) { note ->
                EntryCard(note, onClick = { nav.open(Route.Editor(note.id, note.day, board = true)) }) {
                    TrashedTrailing(weight(note), now) { services.scope.launch { services.repo.restore(note.id) } }
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
@Composable
private fun TakenOffRow(
    services: AppServices,
    item: LibraryItem,
    now: Long,
    curtained: Boolean,
    onReveal: () -> Unit,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val meta = item.attachment.meta
    val months = stringArrayResource(Res.array.months_genitive)
    Row(
        Modifier.fillMaxWidth().hand().clickable(onClick = onOpen).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
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
        TextButton(onRestore, modifier = Modifier.hand()) { Text(stringResource(Res.string.restore)) }
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
