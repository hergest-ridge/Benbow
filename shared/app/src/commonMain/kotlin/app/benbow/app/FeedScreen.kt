package app.benbow.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
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
import app.benbow.app.resources.entry_from_board
import app.benbow.app.resources.entry_to_board
import app.benbow.app.resources.feed_empty
import app.benbow.app.resources.feed_filter
import app.benbow.app.resources.feed_filter_empty
import app.benbow.app.resources.feed_filter_reset
import app.benbow.app.resources.feed_jump_desc
import app.benbow.app.resources.feed_title
import app.benbow.app.resources.feed_to_today
import app.benbow.app.resources.months_nominative
import app.benbow.client.EntrySummary
import app.benbow.client.FeedItem
import app.benbow.client.FeedKey
import app.benbow.client.Veil
import app.benbow.client.effectiveVeil
import app.benbow.client.tagRegistry
import app.benbow.core.sync.AttachmentState
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** A row of the feed: a post, or the day line or month title drawn above a day's or a month's posts. */
internal sealed interface FeedRow {
    val key: String

    /** "YYYY-MM": the month the row belongs to. */
    val month: String

    /** Rows of a kind reuse each other's composition while scrolling; posts with pictures and placeholders are their own kinds. */
    val contentType: Int get() = when (this) {
        is Month -> 0

        is Day -> 1

        is Item -> when {
            item == null -> 4
            item.media.isEmpty() -> 2
            else -> 3
        }
    }

    /**
     * A post: [place] is where it stands, [pos] its place among the posts; [item] is the post in full once its window
     * has been read (ADR-036), a placeholder of about its height until then; [body] is its text, parsed off the main thread.
     */
    data class Item(val place: FeedKey, val pos: Int, val item: FeedItem? = null, val body: FeedBody? = null) : FeedRow {
        override val key get() = place.id
        override val month get() = place.day.take(7)
    }

    data class Day(val day: String) : FeedRow {
        override val key get() = "day:$day"
        override val month get() = day.take(7)
    }

    /** A month's title above its newest day. */
    data class Month(override val month: String) : FeedRow {
        override val key get() = "month:$month"
    }
}

/** Newest first, top down: each month's title, then each day's line above its posts; [items] are those read in full. */
internal fun feedRows(
    keys: List<FeedKey>,
    items: Map<String, FeedItem> = emptyMap(),
    body: (FeedItem) -> FeedBody? = { null },
): List<FeedRow> = buildList {
    keys.forEachIndexed { i, key ->
        val previous = keys.getOrNull(i - 1)?.day
        if (previous == null || !previous.regionMatches(0, key.day, 0, 7)) add(FeedRow.Month(key.day.take(7)))
        if (previous != key.day) add(FeedRow.Day(key.day))
        val item = items[key.id]
        add(FeedRow.Item(key, i, item, item?.let(body)))
    }
}

/**
 * The feed's queue from a post on (ADR-035): its sounds, then those of the posts below it (older ones) as far as they
 * have been read (a window reaches a chunk or more past the screen, ADR-036), leaving out posts under a curtain or
 * quiet (ADR-031); the post it starts from was opened by the tap itself.
 */
internal fun TrackNames.feedQueue(rows: List<FeedRow>, fromId: String, gentle: Boolean): List<Track> {
    val posts = rows.filterIsInstance<FeedRow.Item>()
    val start = posts.indexOfFirst { it.key == fromId }
    if (start < 0) return emptyList()
    val items = posts.drop(start).asSequence().map { it.item }.takeWhile { it != null }.filterNotNull()
    return items.filterIndexed { i, item -> i == 0 || !item.summary.curtained(gentle) }
        .flatMap { queue(it.summary.id, it.audio, it.tracks) }.toList()
}

internal val REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🔥")

/**
 * The feed over the whole journal (ADR-036): every post's place from a light index, and the posts in full a window at
 * a time, [FEED_CHUNK] posts to a chunk: the chunks on screen and one on each side.
 */
internal class FeedViewModel(private val services: AppServices) : ViewModel() {
    // Each post's text is parsed here, once per change, not while it scrolls into view.
    private val parsed = HashMap<String, Pair<String, FeedBody?>>()

    // A row that didn't change keeps its instance, so the posts on screen skip recomposing when the window moves on.
    private var last: Map<String, FeedRow> = emptyMap()

    /** The chunks read in full: those around the screen, and while a jump gets ready, those around where it lands. */
    private val chunks = MutableStateFlow(listOf(0..1))
    private var jumping = false

    // One index for the rows and for what the filter offers.
    private val index = services.repo.feedIndex().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    private val registry = services.repo.tagRegistry().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    init {
        // A new filter is a new feed: it starts from its top.
        viewModelScope.launch { services.feedFilter.drop(1).collect { chunks.value = listOf(0..1) } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<FeedRow>?> = combine(index, services.gentle.on, chunks, services.feedFilter, registry) {
            keys,
            gentle,
            chunks,
            filter,
            registry,
        ->
        val shown = keys.filter { filter.shows(it, gentle, registry) }
        shown to feedWindow(shown, chunks)
    }.distinctUntilChanged().flatMapLatest { (keys, ids) ->
        // The index and its window come out together, so a new post never shows as a placeholder first.
        services.repo.feedItems(ids).map { items ->
            val read = items.associateBy { it.summary.id }
            parsed.keys.retainAll(read.keys)
            val rows = feedRows(keys, read) { item ->
                val id = item.summary.id
                parsed[id]?.takeIf { it.first == item.body }?.second ?: feedBody(item.body).also { parsed[id] = item.body to it }
            }.map { row -> last[row.key]?.takeIf { it == row } ?: row }
            last = rows.associateBy { it.key }
            rows
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What the filter offers: the feed's years, categories and most used tags, as it shows without a filter. */
    val choices: StateFlow<FeedChoices?> = combine(index, services.gentle.on, registry) { keys, gentle, registry ->
        FeedChoices.of(if (gentle) keys.filter { it.veil != Veil.Quiet } else keys, registry)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Rows [first]..[last] are on screen: the window follows them a chunk at a time. */
    fun showing(first: Int, last: Int) {
        if (jumping) return
        val rows = rows.value ?: return
        val from = rows.postNear(first) ?: return
        chunks.value = listOf(chunksAround(from, rows.postNear(last) ?: from))
    }

    /**
     * Reads the posts around [pos] before the list jumps there, so it lands on posts and not on placeholders; what is
     * on screen stays read meanwhile. [jump] scrolls, then the window follows the screen again.
     */
    suspend fun jumpTo(pos: Int, jump: suspend () -> Unit) {
        jumping = true
        try {
            chunks.value = (chunks.value.take(1) + listOf(chunksAround(pos, pos))).distinct()
            withTimeoutOrNull(JUMP_WAIT_MILLIS) {
                rows.first { rows -> rows?.any { it is FeedRow.Item && it.pos == pos && it.item != null } == true }
            }
            jump()
        } finally {
            jumping = false
        }
    }

    /**
     * Where post [id] is in the feed as it shows (its place, its row), once the feed has its rows; null when the feed
     * keeps it out. A filter that hides it is let go first («Показать в ленте» asks for the post itself, ADR-036 N6).
     */
    suspend fun find(id: String): Pair<Int, Int>? {
        fun List<FeedRow>.place(): Pair<Int, Int>? {
            val row = indexOfFirst { it is FeedRow.Item && it.key == id }
            return if (row < 0) null else (this[row] as FeedRow.Item).pos to row
        }
        rows.filterNotNull().first().place()?.let { return it }
        if (!services.feedFilter.value.active) return null
        services.feedFilter.value = FeedFilter()
        return withTimeoutOrNull(FIND_WAIT_MILLIS) { rows.filterNotNull().first { it.place() != null } }?.place()
    }

    fun react(id: String, emoji: String, on: Boolean) {
        services.scope.launch { services.repo.toggleReaction(id, emoji, on) }
    }
}

/** Posts to a chunk of the feed's window (ADR-036): a screen holds a few, so one chunk past it is many screens ahead. */
internal const val FEED_CHUNK = 40

private const val JUMP_WAIT_MILLIS = 1_000L
private const val FIND_WAIT_MILLIS = 2_000L

/** How long a post found by «Показать в ленте» glows, so the eye finds it. */
private const val GLOW_MILLIS = 1_600L

/** The chunks from the one before [from]'s to the one after [to]'s. */
internal fun chunksAround(from: Int, to: Int) = (from / FEED_CHUNK - 1).coerceAtLeast(0)..(to / FEED_CHUNK + 1)

/** The ids of the posts in [chunks], in feed order. */
internal fun feedWindow(keys: List<FeedKey>, chunks: List<IntRange>): List<String> =
    chunks.flatMap { it }.distinct().sorted().flatMap { chunk ->
        val from = (chunk * FEED_CHUNK).coerceAtMost(keys.size)
        keys.subList(from, (from + FEED_CHUNK).coerceAtMost(keys.size))
    }.map { it.id }

/** The place of the post at row [index], or of the nearest one below it (above it at the very end). */
internal fun List<FeedRow>.postNear(index: Int): Int? {
    for (i in index.coerceAtLeast(0) until size) (this[i] as? FeedRow.Item)?.let { return it.pos }
    for (i in (index - 1).coerceAtMost(lastIndex) downTo 0) (this[i] as? FeedRow.Item)?.let { return it.pos }
    return null
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
    // Posts a screen ahead (and half a screen behind) are composed in the spare time between frames, so a post
    // scrolling in is already built: composing pictures and text in the frame it appears in drops frames.
    val state = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f))
    // Where the feed is (ADR-036): the month of the top row once the newest posts have scrolled away.
    val month by remember(state) {
        derivedStateOf { if (state.firstVisibleItemIndex == 0) null else rows?.getOrNull(state.firstVisibleItemIndex)?.month }
    }
    val far by remember(state) { derivedStateOf { state.firstVisibleItemIndex >= TODAY_AFTER_ROWS } }
    // «К сегодня» shows only while the feed goes up toward today from far down, and goes once it stands still.
    var headingUp by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        var last = 0 to 0
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { now ->
            val up = now.first < last.first || (now.first == last.first && now.second < last.second)
            val down = now.first > last.first || (now.first == last.first && now.second > last.second)
            if (up) {
                headingUp = true
            } else if (down) {
                headingUp = false
            }
            last = now
        }
    }
    val moving = state.isScrollInProgress
    LaunchedEffect(moving, headingUp) {
        if (headingUp && !moving) {
            delay(TODAY_HIDE_MILLIS)
            headingUp = false
        }
    }
    LaunchedEffect(state, vm) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.let { (it.firstOrNull()?.index ?: 0) to (it.lastOrNull()?.index ?: 0) } }
            .collect { (first, last) -> vm.showing(first, last) }
    }
    // «Перейти» (ADR-036 N4): open with the month to start from (the one tapped, or the one in view).
    var jumping by remember { mutableStateOf(false) }
    var jumpFrom by remember { mutableStateOf<String?>(null) }
    val openJump = { from: String? ->
        jumpFrom = from
        jumping = true
    }
    // Back after five minutes away, the feed starts at today again, not deep in some past month.
    LaunchedEffect(state, vm) { services.cameBack.collect { vm.toToday(state, glide = false) } }
    // «Показать в ленте» (ADR-036 N6): the post among its neighbours (the one above it in sight too), glowing a moment.
    var glowing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state, vm) {
        services.showInFeed.filterNotNull().collect { id ->
            services.showInFeed.value = null
            val (pos, row) = vm.find(id) ?: run {
                services.messages.tryEmit(AppMessage.HiddenFromFeed)
                return@collect
            }
            vm.jumpTo(pos) { state.scrollToItem((row - 1).coerceAtLeast(0)) }
            glowing = id
            delay(GLOW_MILLIS)
            glowing = null
        }
    }
    // The filter (ADR-036 N5): a new one starts its feed from the top.
    val filter by services.feedFilter.collectAsStateWithLifecycle()
    val choices by vm.choices.collectAsStateWithLifecycle()
    var filtering by remember { mutableStateOf(false) }
    val seenFilter = remember { arrayOf(filter) }
    LaunchedEffect(filter) {
        if (filter != seenFilter[0]) {
            seenFilter[0] = filter
            state.scrollToItem(0)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.hand().clickable(interactionSource = null, indication = null) { scope.launch { pull.toggle() } }) {
                        Text(stringResource(Res.string.feed_title))
                        MonthInView(month) { openJump(month) }
                    }
                },
                actions = {
                    if (!rows.isNullOrEmpty() || filter.active) {
                        IconButton({ filtering = true }, Modifier.hand()) {
                            Icon(
                                Icons.Filter,
                                stringResource(Res.string.feed_filter),
                                tint = if (filter.active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                    }
                    if (!rows.isNullOrEmpty()) {
                        IconButton({ openJump(month) }, Modifier.hand()) { Icon(Icons.JumpTo, stringResource(Res.string.feed_jump_desc)) }
                    }
                    SyncBadge(status) { nav.open(Route.Server) }
                },
            )
        },
    ) { padding ->
        val offer = choices
        if (filtering && offer != null) {
            FeedFilterSheet(services, filter, offer, onChange = { services.feedFilter.value = it }, onDismiss = { filtering = false })
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (filter.active) FeedFilterRow(filter, { services.feedFilter.value = it })
            val list = rows ?: return@Column
            if (list.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(if (filter.active) Res.string.feed_filter_empty else Res.string.feed_empty),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (filter.active) {
                        TextButton({ services.feedFilter.value = FeedFilter() }, Modifier.hand()) {
                            Text(stringResource(Res.string.feed_filter_reset))
                        }
                    }
                }
                return@Column
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds().nestedScroll(pull).pageScrolling(
                    state,
                    enabled = services.platform != "android",
                    home = { vm.toToday(state, glide = true) },
                    end = { vm.toOldest(state) },
                ),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().offset { IntOffset(0, pull.shown.roundToInt()) },
                    state = state,
                    // Room below the very first post, so «К сегодня» never covers it.
                    contentPadding = PaddingValues(top = if (appearance.feedCards) 8.dp else 0.dp, bottom = 72.dp),
                ) {
                    // Posts that turn quiet or come back slide out and in where they are; the post in view stays put.
                    items(list, key = { it.key }, contentType = { it.contentType }) { row ->
                        Box(Modifier.animateItem()) {
                            when (row) {
                                is FeedRow.Day -> DayHeader(row.day)

                                is FeedRow.Month -> MonthHeader(row.month) { openJump(row.month) }

                                is FeedRow.Item -> {
                                    val item = row.item
                                    if (item == null) {
                                        PostPlaceholder(row.place.media, cards = appearance.feedCards)
                                    } else {
                                        val id = item.summary.id
                                        FeedPost(
                                            services,
                                            item,
                                            row.body,
                                            cards = appearance.feedCards,
                                            expanded = expanded[id] == true,
                                            onExpand = { expanded[id] = true },
                                            onOpen = { nav.open(Route.Editor(id, item.summary.day, readOnly = true)) },
                                            onComments = { nav.open(Route.Editor(id, item.summary.day, readOnly = true)) },
                                            onOpenMedia = { nav.open(Route.Media(id, it.meta.id)) },
                                            glow = glowing == id,
                                            // The rows as they are when tapped: a moving window doesn't recompose the post.
                                            queue = { feedQueue(vm.rows.value.orEmpty(), id, gentle) },
                                        ) { emoji, on ->
                                            vm.react(id, emoji, on)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (appearance.feedScrubber) {
                    FeedScrubber(
                        list,
                        state,
                        mouse = services.platform != "android",
                        jump = { pos, row -> vm.jumpTo(pos) { state.scrollToItem(row) } },
                    )
                }
                if (jumping) {
                    FeedJumpSheet(
                        list,
                        jumpFrom,
                        onJump = { row -> scope.launch { vm.jumpTo(list.postNear(row) ?: 0) { state.scrollToItem(row) } } },
                        onToday = { scope.launch { vm.toToday(state, glide = true) } },
                        onDismiss = { jumping = false },
                    )
                }
                ToToday(far && headingUp, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
                    scope.launch { vm.toToday(state, glide = true) }
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
    queue: TrackNames.() -> List<Track>,
    glow: Boolean = false,
    onReact: (String, Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val entry = item.summary
    // Found by «Показать в ленте»: a light comes up at once and fades.
    val glowAlpha by animateFloatAsState(if (glow) 0.22f else 0f, tween(if (glow) 150 else 1_200), label = "glow")
    val glowing = Modifier.drawWithContent {
        drawContent()
        if (glowAlpha > 0f) drawRect(colors.primary.copy(alpha = glowAlpha))
    }
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
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).then(glowing).then(open),
            ) {
                Revealing(curtained, entry) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.media.isNotEmpty()) MediaCarousel(services.media, item.media, onOpenMedia)
                        PostBody(services, item, body, expanded, onExpand, onComments, queue, onReact)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().background(tint ?: colors.background).then(glowing).then(open)) {
                Revealing(curtained, entry, Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Column {
                        if (item.media.isNotEmpty()) MediaCarousel(services.media, item.media, onOpenMedia, rounded = false)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PostBody(services, item, body, expanded, onExpand, onComments, queue, onReact)
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
            if (services.labs) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.entry_to_board)) }, onClick = {
                    picking = false
                    services.toBoard(listOf(entry.id))
                }, modifier = Modifier.hand())
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
    queue: TrackNames.() -> List<Track>,
    onReact: (String, Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val entry = item.summary
    FeedText(body, expanded, onExpand)
    // Every sound of the post, wherever it sits in the text (ADR-035).
    item.audio.forEach {
        VoiceRow(services, entry.id, it, item.transcripts[it.meta.id], tags = item.tracks[it.meta.id], queue = queue)
    }
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
        if (entry.favorite) FavoriteMark()
        moodEmoji(entry.mood)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        if (entry.fromBoard) {
            Text(
                stringResource(Res.string.entry_from_board) + " · ",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(timeLabel(entry.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

/** A season's sign for each month, January first. */
private val MONTH_SIGNS = listOf("❄️", "🌨️", "🌱", "🌷", "🌸", "☀️", "🌻", "🍉", "🍁", "🍂", "🌧️", "🎄")

/** "🍂 Октябрь" for "YYYY-MM", "🍂 Октябрь 2024" when it isn't this year (or [withYear]); null for anything else. */
@Composable
internal fun monthTitle(month: String, withYear: Boolean = false): String? {
    val names = stringArrayResource(Res.array.months_nominative)
    val year = month.take(4).toIntOrNull() ?: return null
    val index = (month.drop(5).toIntOrNull() ?: return null) - 1
    val name = names.getOrNull(index) ?: return null
    return "${MONTH_SIGNS[index]} $name" + if (withYear || year != journalToday().year) " $year" else ""
}

/** The month's title above its posts. */
@Composable
private fun MonthHeader(month: String, onClick: () -> Unit) {
    Text(
        monthTitle(month) ?: return,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 12.dp).clip(RoundedCornerShape(12.dp)).hand()
            .clickable(onClick = onClick).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** Under the feed's title, the month in view (ADR-036); it slides in once the newest posts scroll away. A tap: «Перейти». */
@Composable
private fun MonthInView(month: String?, onClick: () -> Unit) {
    // The last month shown stays while the line slides away.
    val last = remember { arrayOf("") }
    if (month != null) last[0] = month
    AnimatedVisibility(month != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        AnimatedContent(
            month ?: last[0],
            // Only the words change: no size animation, so a new month costs the bar no layout on every frame.
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) using null },
            label = "month in view",
        ) {
            Text(
                monthTitle(it).orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.hand().clickable(interactionSource = null, indication = null, onClick = onClick),
            )
        }
    }
}

/** «К сегодня»: one tap back to the newest post once the feed is a few screens down (ADR-036). */
@Composable
private fun ToToday(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 3.dp,
            modifier = Modifier.hand(),
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.ArrowUp, null, Modifier.size(18.dp))
                Text(stringResource(Res.string.feed_to_today), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * A post not read yet (ADR-036): about its room, taller with pictures or sounds. Windows reach well past the screen,
 * so it shows only after a long fling, for a moment.
 */
@Composable
private fun PostPlaceholder(media: Boolean, cards: Boolean) {
    val colors = MaterialTheme.colorScheme
    val height = if (media) 320.dp else 96.dp
    if (cards) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(height)
                .clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainerLow).clearAndSetSemantics {},
        )
    } else {
        Column(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            Box(Modifier.fillMaxWidth().height(height))
            HorizontalDivider(Modifier.padding(top = 4.dp), thickness = 0.5.dp, color = colors.outlineVariant)
        }
    }
}

/**
 * Up to the newest post: from far down a jump near it first, then a short glide; straight there when nobody is
 * looking ([glide] false: back after a while away).
 */
private suspend fun FeedViewModel.toToday(state: LazyListState, glide: Boolean) = jumpTo(0) {
    if (!glide) {
        state.scrollToItem(0)
    } else {
        if (state.firstVisibleItemIndex > GLIDE_ROWS) state.scrollToItem(GLIDE_ROWS)
        state.animateScrollToItem(0)
    }
}

/** Down to the very first post (End): its window is read first, so the list lands on posts. */
private suspend fun FeedViewModel.toOldest(state: LazyListState) {
    val oldest = rows.value?.lastOrNull { it is FeedRow.Item } as? FeedRow.Item ?: return
    jumpTo(oldest.pos) { state.scrollToItem(rows.value?.lastIndex ?: return@jumpTo) }
}

/** Rows down (titles and day lines count) before «К сегодня» shows: a few screens. */
private const val TODAY_AFTER_ROWS = 12

/** How long «К сегодня» stays once the feed stands still. */
private const val TODAY_HIDE_MILLIS = 1_500L

/** How far from the top «К сегодня» glides; from farther it jumps to here first. */
private const val GLIDE_ROWS = 8
