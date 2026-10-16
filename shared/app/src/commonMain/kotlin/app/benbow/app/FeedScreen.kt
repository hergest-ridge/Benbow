package app.benbow.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.comments
import app.benbow.app.resources.feed_empty
import app.benbow.app.resources.feed_title
import app.benbow.app.resources.months_nominative
import app.benbow.client.EntrySummary
import app.benbow.client.FeedItem
import app.benbow.client.effectiveVeil
import app.benbow.core.sync.AttachmentState
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** A row of the feed: an entry, or the day line drawn above a day's entries. */
internal sealed interface FeedRow {
    val key: String

    /** Rows of a kind reuse each other's composition while scrolling; posts with pictures are their own kind. */
    val contentType: Int get() = when (this) {
        is Month -> 0
        is Day -> 1
        is Item -> if (item.media.isEmpty()) 2 else 3
    }

    /** [body] is the post's text, parsed off the main thread. */
    data class Item(val item: FeedItem, val body: FeedBody? = null) : FeedRow {
        override val key get() = item.summary.id
    }

    data class Day(val day: String) : FeedRow {
        override val key get() = "day:$day"
    }

    /** "YYYY-MM": a month's title above its newest day. */
    data class Month(val month: String) : FeedRow {
        override val key get() = "month:$month"
    }
}

/** Newest first, top down: each month's title, then each day's line above its entries. */
internal fun feedRows(items: List<FeedItem>, body: (FeedItem) -> FeedBody? = { null }): List<FeedRow> = buildList {
    items.forEachIndexed { i, item ->
        val month = item.summary.day.take(7)
        if (items.getOrNull(i - 1)?.summary?.day?.take(7) != month) add(FeedRow.Month(month))
        if (items.getOrNull(i - 1)?.summary?.day != item.summary.day) add(FeedRow.Day(item.summary.day))
        add(FeedRow.Item(item, body(item)))
    }
}

internal val REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🔥")

internal class FeedViewModel(private val services: AppServices) : ViewModel() {
    // Each post's text is parsed here, once per change, not while it scrolls into view.
    private val parsed = HashMap<String, Pair<String, FeedBody?>>()

    val rows: StateFlow<List<FeedRow>?> = combine(services.repo.feed(), services.gentle.on) { items, gentle ->
        items.withoutQuiet(gentle) { it.summary }
    }.map { items ->
        val rows = feedRows(items) { item ->
            val id = item.summary.id
            parsed[id]?.takeIf { it.first == item.body }?.second ?: feedBody(item.body).also { parsed[id] = item.body to it }
        }
        parsed.keys.retainAll(items.mapTo(HashSet()) { it.summary.id })
        rows
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun react(id: String, emoji: String, on: Boolean) {
        services.scope.launch { services.repo.toggleReaction(id, emoji, on) }
    }
}

/** Everything as a stream, newest on top; long-press to react. Writing happens on Home. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun FeedScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { FeedViewModel(services) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val appearance by services.appearance.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val status by services.sync.status.collectAsStateWithLifecycle()
    val gentle by services.gentle.on.collectAsStateWithLifecycle()
    // Бережно's switch (ADR-031) waits above the newest post, out of sight: on a phone a pull down at the top brings it
    // (like Telegram's archive); with a mouse, where the wheel would bring it every time, only the title does.
    val pull = remember(services.platform) { StripPull(pulls = services.platform == "android") }
    val stripHidden by remember(pull) { derivedStateOf { pull.shown <= 0f } }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(Res.string.feed_title),
                        Modifier.hand().clickable(interactionSource = null, indication = null) { scope.launch { pull.toggle() } },
                    )
                },
                actions = { SyncBadge(status) { nav.open(Route.Server) } },
            )
        },
    ) { padding ->
        val list = rows ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.feed_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        // Posts a screen ahead (and half a screen behind) are composed in the spare time between frames, so a post
        // scrolling in is already built: composing pictures and text in the frame it appears in drops frames.
        val state = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f))
        Box(Modifier.padding(padding).fillMaxSize().clipToBounds().nestedScroll(pull)) {
            LazyColumn(
                Modifier.fillMaxSize().offset { IntOffset(0, pull.shown.roundToInt()) },
                state = state,
                contentPadding = PaddingValues(vertical = if (appearance.feedCards) 8.dp else 0.dp),
            ) {
                // Posts that turn quiet or come back slide out and in where they are; the post in view stays put.
                items(list, key = { it.key }, contentType = { it.contentType }) { row ->
                    Box(Modifier.animateItem()) {
                        when (row) {
                            is FeedRow.Day -> DayHeader(row.day)

                            is FeedRow.Month -> MonthHeader(row.month)

                            is FeedRow.Item -> FeedPost(
                                services,
                                row.item,
                                row.body,
                                cards = appearance.feedCards,
                                expanded = expanded[row.item.summary.id] == true,
                                onExpand = { expanded[row.item.summary.id] = true },
                                onOpen = { nav.open(Route.Editor(row.item.summary.id, row.item.summary.day, readOnly = true)) },
                                onComments = { nav.open(Route.Editor(row.item.summary.id, row.item.summary.day, readOnly = true)) },
                                onOpenMedia = { nav.open(Route.Media(row.item.summary.id, it.meta.id)) },
                            ) { emoji, on ->
                                vm.react(row.item.summary.id, emoji, on)
                            }
                        }
                    }
                }
            }
            // As tall as it is pulled out: nothing of it lies outside, so a hidden strip takes no taps (or screen readers).
            Box(
                Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                    val strip = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    pull.height = strip.height.toFloat()
                    val shown = pull.shown.roundToInt().coerceIn(0, strip.height)
                    layout(strip.width, shown) { strip.place(0, shown - strip.height) }
                }.then(if (stripHidden) Modifier.clearAndSetSemantics {} else Modifier),
            ) { GentleStrip(gentle, services.gentle::set) }
        }
    }
}

/**
 * How much of Бережно's strip shows above the feed. On a phone a drag down at the very top pulls it in; a fling never
 * does, and scrolling the feed up tucks it away first. Let go, it settles open past half its height, closed otherwise.
 */
@Stable
private class StripPull(private val pulls: Boolean) : NestedScrollConnection {
    var shown by mutableFloatStateOf(0f)
        private set

    /** The strip's full height, measured as it is laid out. */
    var height = 0f

    suspend fun toggle() = settle(if (shown > 0f) 0f else height)

    private suspend fun settle(target: Float) {
        animate(shown, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> shown = value }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f || shown <= 0f) return Offset.Zero
        val used = maxOf(available.y, -shown)
        shown += used
        return Offset(0f, used)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!pulls || source != NestedScrollSource.UserInput || available.y <= 0f || shown >= height) return Offset.Zero
        shown = minOf(height, shown + available.y * PULL_RESISTANCE)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (shown <= 0f) return Velocity.Zero
        // A flick at the top (flinging up to the newest post, again) is no pull on purpose: the strip goes back.
        val flick = available.y > FLICK_VELOCITY
        if (!flick && shown >= height) return Velocity.Zero
        settle(if (!flick && shown > height / 2) height else 0f)
        return available
    }
}

private const val PULL_RESISTANCE = 0.6f

/** px per second: a pull let go faster than this was a flick (a slow, deliberate pull ends well below it). */
private const val FLICK_VELOCITY = 3000f

/**
 * One post. With [cards], a rounded card with inset media; without, edge to edge: media across the full width, the
 * text in a narrow margin, posts parted by a thin band (more of each post on screen). Long-press for reactions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedPost(
    services: AppServices,
    item: FeedItem,
    body: FeedBody?,
    cards: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onOpen: () -> Unit,
    onComments: () -> Unit,
    onOpenMedia: (AttachmentState) -> Unit,
    onReact: (String, Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val entry = item.summary
    var picking by remember { mutableStateOf(false) }
    var postponing by remember { mutableStateOf(false) }
    // A curtain opens in place and closes again once the post scrolls away (this state goes with it).
    var opened by remember(entry.id) { mutableStateOf(false) }
    val curtained = entry.curtained(LocalGentle.current) && !opened
    val tint = if (curtained) null else swatch(entry.background)
    val open = Modifier.hand().combinedClickable(onClick = { if (curtained) opened = true else onOpen() }, onLongClick = { picking = true })
    val shown = LocalOpenEntry.current == entry.id // open beside the feed (a wide window)
    Box(
        Modifier.fillMaxWidth().then(
            when {
                cards -> Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                shown -> Modifier.background(colors.primary.copy(alpha = 0.08f))
                else -> Modifier
            },
        ),
    ) {
        if (cards) {
            Surface(
                color = tint ?: colors.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp),
                border = if (shown) BorderStroke(2.dp, colors.primary) else null,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).then(open),
            ) {
                Revealing(curtained, entry) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.media.isNotEmpty()) MediaCarousel(services.media, item.media, onOpenMedia)
                        PostBody(services, item, body, expanded, onExpand, onComments, onReact)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().background(tint ?: colors.background).then(open)) {
                Revealing(curtained, entry, Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Column {
                        if (item.media.isNotEmpty()) MediaCarousel(services.media, item.media, onOpenMedia, rounded = false)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PostBody(services, item, body, expanded, onExpand, onComments, onReact)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 4.dp), thickness = 0.5.dp, color = colors.outlineVariant)
            }
        }
        DropdownMenu(picking, { picking = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.comments)) }, onClick = {
                picking = false
                onComments()
            }, modifier = Modifier.hand())
            VeilMenuItems(effectiveVeil(entry.ownVeil, entry.ruleVeil), entry.ruleVeil) {
                picking = false
                services.setVeil(entry.id, it, entry.ownVeil)
            }
            PostponeMenuItem(entry.until) { choose ->
                picking = false
                if (choose) postponing = true else services.postpone(entry.id, null, entry.until)
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                REACTIONS.forEach { emoji ->
                    val on = emoji in item.reactions
                    DropdownMenuItem(text = { Text(emoji, style = MaterialTheme.typography.titleLarge) }, onClick = {
                        picking = false
                        onReact(emoji, !on)
                    }, modifier = Modifier.size(width = 52.dp, height = 48.dp).hand())
                }
            }
        }
        if (postponing) {
            PostponeDialog(onPick = {
                postponing = false
                services.postpone(entry.id, it.toString(), entry.until)
            }, onDismiss = { postponing = false })
        }
    }
}

/**
 * A post's content, or while [curtained] only its time under a curtain at one compact height (no tags, mood, pictures
 * or length). Opening fades the content in as the card grows to it.
 */
@Composable
private fun Revealing(
    curtained: Boolean,
    entry: EntrySummary,
    curtainPadding: Modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable () -> Unit,
) {
    AnimatedContent(
        curtained,
        transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = true) },
        label = "curtain",
    ) { closed ->
        if (!closed) {
            content()
        } else {
            Column(curtainPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Curtain()
                Text(
                    timeLabel(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/** The text (lightly formatted), tags, and a line of reactions, comments, mood and time. */
@Composable
private fun PostBody(
    services: AppServices,
    item: FeedItem,
    body: FeedBody?,
    expanded: Boolean,
    onExpand: () -> Unit,
    onComments: () -> Unit,
    onReact: (String, Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val entry = item.summary
    FeedText(body, expanded, onExpand)
    item.audio.forEach { VoiceRow(services, item.summary.id, it, item.transcripts[it.meta.id]) }
    if (entry.tags.isNotEmpty()) TagRibbon(entry.tags)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item.reactions.sorted().forEach { emoji ->
            Surface(
                onClick = { onReact(emoji, false) },
                color = colors.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.hand(),
            ) { Text(emoji, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
        }
        if (item.comments > 0) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).hand().clickable(onClick = onComments).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Comment, stringResource(Res.string.comments), Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Text("${item.comments}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
        Box(Modifier.weight(1f))
        if (entry.favorite) Text("★", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        moodEmoji(entry.mood)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        Text(timeLabel(entry.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

/** A season's sign for each month, January first. */
private val MONTH_SIGNS = listOf("❄️", "🌨️", "🌱", "🌷", "🌸", "☀️", "🌻", "🍉", "🍁", "🍂", "🌧️", "🎄")

/** "🍂 Октябрь" above a month's posts; the year too when it isn't this one. */
@Composable
private fun MonthHeader(month: String) {
    val year = month.take(4).toIntOrNull() ?: return
    val index = (month.drop(5).toIntOrNull() ?: return) - 1
    val name = stringArrayResource(Res.array.months_nominative).getOrNull(index) ?: return
    val thisYear = journalToday().year
    Text(
        "${MONTH_SIGNS[index]} $name" + if (year != thisYear) " $year" else "",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 2.dp),
    )
}
