package app.benbow.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.benbow.app.resources.Res
import app.benbow.app.resources.background_none
import app.benbow.app.resources.board_archive
import app.benbow.app.resources.board_as_grid
import app.benbow.app.resources.board_as_list
import app.benbow.app.resources.board_choose
import app.benbow.app.resources.board_chosen
import app.benbow.app.resources.board_color
import app.benbow.app.resources.board_empty
import app.benbow.app.resources.board_empty_hint
import app.benbow.app.resources.board_hint
import app.benbow.app.resources.board_new_list
import app.benbow.app.resources.board_others
import app.benbow.app.resources.board_pin
import app.benbow.app.resources.board_pinned
import app.benbow.app.resources.board_trash
import app.benbow.app.resources.board_unchoose
import app.benbow.app.resources.board_unpin
import app.benbow.app.resources.tab_board
import app.benbow.client.BoardNote
import app.benbow.client.BoardNotes
import app.benbow.client.Media
import app.benbow.client.Settings
import app.benbow.client.archiveOnBoard
import app.benbow.client.board
import app.benbow.client.pinOnBoard
import app.benbow.client.placeOnBoard
import app.benbow.client.tickOnBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

/**
 * «Записки» (ADR-040): notes beside the journal, Keep-like. Pinned ones first, then the others, in the owner's order,
 * as a grid of cards as tall as their words (two columns at most on a phone) or a list; a pinch or the title bar's
 * button switches. A card carried to another place keeps it ([BoardDrag], then [placeOnBoard]); a tap opens the note
 * as its card's sheet growing into the editor ([BoardSceneStrategy]); the quick bar starts a note or a list.
 */
@Composable
internal fun BoardScreen(services: AppServices, nav: Navigator) {
    val data by remember { services.repo.board() }.collectAsStateWithLifecycle(null)
    val view = remember { BoardView(services.settings) }
    val order = remember { BoardOrder() }
    data?.let { order.stored = it }
    val grid = rememberLazyStaggeredGridState()
    val drag = remember(grid, order) { BoardDrag(grid, isCard = ::isNoteKey, canSwap = order::sameKind, move = order::move) }
    val swipe = remember { BoardSwipe() }
    // A card swiped away stays out of sight until it has left the board.
    LaunchedEffect(data) {
        swipe.key?.let { key ->
            if (data?.let { (it.pinned + it.others).none { n -> n.id == key } } ==
                true
            ) {
                swipe.gone()
            }
        }
    }
    val stage = LocalBoardStage.current
    val haptic = LocalHapticFeedback.current
    var chosen by remember { mutableStateOf(setOf<Any>()) }
    var coloring by remember { mutableStateOf(false) }
    val choose = remember {
        { key: Any ->
            haptic.performHapticFeedback(if (key in chosen) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            chosen = if (key in chosen) chosen - key else chosen + key
            if (chosen.isEmpty()) coloring = false
        }
    }
    val unchoose = {
        chosen = emptySet()
        coloring = false
    }
    NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = chosen.isNotEmpty() && stage?.open != true,
    ) { unchoose() }
    val chosenNotes = { order.parts().let { (pinned, others) -> (pinned + others).filter { it.id in chosen } } }
    val archive: (List<String>) -> Unit = { ids ->
        services.scope.launch { ids.forEach { services.repo.archiveOnBoard(it, true) } }
        services.messages.tryEmit(AppMessage.BoardArchived(ids))
        unchoose()
    }
    val trash: (List<String>) -> Unit = { ids ->
        services.scope.launch { ids.forEach { services.repo.moveToTrash(it) } }
        services.messages.tryEmit(AppMessage.BoardTrashed(ids))
        unchoose()
    }
    val pin: () -> Unit = {
        val notes = chosenNotes()
        val pinned = notes.all { it.pinned }
        services.scope.launch { notes.forEach { services.repo.pinOnBoard(it.id, !pinned) } }
        unchoose()
    }
    // One card chosen moves a place back or on with Ctrl and the arrows (desktop and web).
    val step: (String, Int) -> Unit = { id, by ->
        val (pinned, others) = order.parts()
        val part = if (pinned.any { it.id == id }) pinned else others
        val at = part.indexOfFirst { it.id == id }
        val to = (at + by).coerceIn(0, part.lastIndex)
        if (at >= 0 && to != at) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            services.scope.launch { services.repo.placeOnBoard(id, to) }
        }
    }
    // A box ticked right on its card: the note gets a revision with only that box changed (it moves to «Отмечено»).
    val tick: (String, String) -> Unit = { id, key ->
        haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
        services.scope.launch { services.repo.tickOnBoard(id, key, true) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(chosen.isNotEmpty()) { if (chosen.isNotEmpty()) runCatching { focus.requestFocus() } }
    val keys = Modifier.onKeyEvent { event ->
        val ids = chosen.map { it as String }
        if (event.type != KeyEventType.KeyDown || ids.isEmpty()) return@onKeyEvent false
        when {
            event.key == Key.Escape -> unchoose()
            event.key == Key.Delete || event.key == Key.Backspace -> trash(ids)
            event.key == Key.E && !event.isCtrlPressed -> archive(ids)
            event.key == Key.F && !event.isCtrlPressed -> pin()
            event.isCtrlPressed && ids.size == 1 && (event.key == Key.DirectionUp || event.key == Key.DirectionLeft) -> step(ids[0], -1)
            event.isCtrlPressed && ids.size == 1 && (event.key == Key.DirectionDown || event.key == Key.DirectionRight) -> step(ids[0], 1)
            else -> return@onKeyEvent false
        }
        true
    }
    // The carried order stays until the stored one has caught up (or, after a change from elsewhere, a moment later).
    LaunchedEffect(order.carried, data, order.pending, drag.key) {
        if (order.carried == null || order.pending || drag.key != null) return@LaunchedEffect
        if (!order.caughtUp()) delay(600)
        order.carried = null
    }
    val open: (BoardNote) -> Unit = { note ->
        if (chosen.isNotEmpty()) {
            choose(note.id)
        } else {
            stage?.opening(note.id, note.entry.background)
            nav.open(Route.Editor(note.id, note.entry.day, board = true))
        }
    }
    val create: (Boolean) -> Unit = { list ->
        stage?.opening(null, null)
        nav.open(Route.Editor(null, services.today(), opened = services.now(), board = true, checklist = list))
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val wide = LocalWide.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = if (wide) 16.dp else 8.dp
            val lanes = if (view.list) 1 else autoLanes(maxWidth - side * 2, if (wide) 240.dp else 160.dp).coerceAtMost(if (wide) 6 else 2)
            // A list in a wide window is a readable column in the middle.
            val inset = if (view.list) max(side.value, (maxWidth.value - LIST_WIDTH) / 2).dp else side
            Column(Modifier.fillMaxSize().then(keys).focusRequester(focus).focusable()) {
                if (chosen.isEmpty()) {
                    BoardTop(view.list) { view.change(!view.list) }
                } else {
                    val notes = chosenNotes()
                    ChoiceBar(
                        chosen.size,
                        pinned = notes.isNotEmpty() && notes.all { it.pinned },
                        onClear = unchoose,
                        onPin = pin,
                        onColor = { coloring = !coloring },
                        onArchive = { archive(chosen.map { it as String }) },
                        onTrash = { trash(chosen.map { it as String }) },
                    )
                    if (coloring) {
                        SwatchPicker(
                            selected = notes.map { it.entry.background }.distinct().singleOrNull(),
                            noneLabel = stringResource(Res.string.background_none),
                            onPick = { color -> services.scope.launch { notes.forEach { services.repo.setBackground(it.id, color) } } },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (wide) QuickBar(create, Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally))
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onGloballyPositioned { drag.board = it }
                        .boardGestures(
                            drag,
                            scope,
                            haptic,
                            edge = with(density) { 64.dp.toPx() },
                            fastest = with(density) { 1600.dp.toPx() },
                            onChoose = choose,
                            onPinch = { step -> view.change(step < 0) },
                            swipe = swipe,
                            onSwiped = { key -> archive(listOf(key as String)) },
                            onDrop = { key ->
                                order.placeOf(key as String)?.let { at ->
                                    order.pending = true
                                    services.scope.launch {
                                        try {
                                            services.repo.placeOnBoard(key, at)
                                        } finally {
                                            order.pending = false
                                        }
                                    }
                                }
                            },
                        ),
                ) {
                    val (pinned, others) = order.parts()
                    if (data != null && pinned.isEmpty() && others.isEmpty()) {
                        EmptyBoard(Modifier.align(Alignment.Center))
                    } else {
                        LazyVerticalStaggeredGrid(
                            StaggeredGridCells.Fixed(lanes),
                            Modifier.fillMaxSize().onGloballyPositioned { grid ->
                                drag.board?.let { drag.gridBounds = it.localBoundingBoxOf(grid, clipBounds = false) }
                            },
                            state = grid,
                            // Room above the first row for a card's corner mark.
                            contentPadding = PaddingValues(start = inset, end = inset, top = 10.dp, bottom = 16.dp),
                            verticalItemSpacing = 8.dp,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (pinned.isNotEmpty()) {
                                item("h-pinned", span = StaggeredGridItemSpan.FullLine) { Header(stringResource(Res.string.board_pinned)) }
                                items(pinned, key = { it.id }, contentType = { "note" }) { note ->
                                    NoteSlot(
                                        note,
                                        drag,
                                        swipe,
                                        services.media,
                                        note.id in chosen,
                                        chosen.isNotEmpty(),
                                        open,
                                        choose,
                                        tick,
                                        Modifier.animateItem(placementSpec = GLIDE),
                                    )
                                }
                                if (others.isNotEmpty()) {
                                    item("h-others", span = StaggeredGridItemSpan.FullLine) {
                                        Header(stringResource(Res.string.board_others))
                                    }
                                }
                            }
                            items(others, key = { it.id }, contentType = { "note" }) { note ->
                                NoteSlot(
                                    note,
                                    drag,
                                    swipe,
                                    services.media,
                                    note.id in chosen,
                                    chosen.isNotEmpty(),
                                    open,
                                    choose,
                                    tick,
                                    Modifier.animateItem(placementSpec = GLIDE),
                                )
                            }
                        }
                    }
                    drag.key?.let { key -> (pinned + others).firstOrNull { it.id == key }?.let { LiftedCard(it, drag, services.media) } }
                }
                if (!wide) QuickBar(create)
            }
        }
    }
}

private const val LIST_WIDTH = 640f

private val GLIDE = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

private fun isNoteKey(key: Any) = key is String && !key.startsWith("h-")

private fun autoLanes(width: Dp, narrowest: Dp, gap: Dp = 8.dp) = max(1, ((width + gap) / (narrowest + gap)).toInt())

/** A grid or a list: this device's own choice, as Keep's. */
@Stable
private class BoardView(private val settings: Settings) {
    var list by mutableStateOf(settings.get(KEY) == "list")
        private set

    fun change(list: Boolean) {
        if (list == this.list) return
        this.list = list
        settings.put(KEY, if (list) "list" else "grid")
    }

    private companion object {
        const val KEY = "board.view"
    }
}

/**
 * The board's order as shown: the stored one, or while a card is carried (and until the move is stored) the
 * carried one, each card a place in its part (pinned or not).
 */
@Stable
private class BoardOrder {
    var stored by mutableStateOf(BoardNotes())
    var carried by mutableStateOf<List<List<String>>?>(null)
    var pending by mutableStateOf(false)

    private fun storedIds() = listOf(stored.pinned.map { it.id }, stored.others.map { it.id })

    fun parts(): Pair<List<BoardNote>, List<BoardNote>> {
        val ids = carried ?: return stored.pinned to stored.others
        val byId = (stored.pinned + stored.others).associateBy { it.id }
        return ids[0].mapNotNull(byId::get) to ids[1].mapNotNull(byId::get)
    }

    fun sameKind(a: Any, b: Any): Boolean = (carried ?: storedIds()).any { a in it && b in it }

    fun move(lifted: Any, other: Any) {
        val parts = (carried ?: storedIds()).map { it.toMutableList() }
        val part = parts.first { lifted in it }
        part.add(part.indexOf(other), part.removeAt(part.indexOf(lifted)))
        carried = parts
    }

    fun caughtUp() = carried == storedIds()

    /** Where a card let go now is in its part, or null when it is where it was (nothing to store). */
    fun placeOf(id: String): Int? {
        val parts = carried ?: return null
        if (caughtUp()) return null
        return parts.first { id in it }.indexOf(id)
    }
}

@Composable
private fun BoardTop(list: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.tab_board), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        IconButton(onToggle, Modifier.hand()) {
            Icon(
                if (list) Icons.Blocks else Icons.ViewAgenda,
                stringResource(if (list) Res.string.board_as_grid else Res.string.board_as_list),
            )
        }
    }
}

/** What is chosen and what can be done with it, in place of the title (Keep's bar). */
@Composable
private fun ChoiceBar(
    count: Int,
    pinned: Boolean,
    onClear: () -> Unit,
    onPin: () -> Unit,
    onColor: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClear, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.board_unchoose)) }
        Text(stringResource(Res.string.board_chosen, count), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onPin, Modifier.hand()) {
            Icon(Icons.Pin, stringResource(if (pinned) Res.string.board_unpin else Res.string.board_pin))
        }
        IconButton(onColor, Modifier.hand()) { Icon(Icons.Palette, stringResource(Res.string.board_color)) }
        IconButton(onArchive, Modifier.hand()) { Icon(Icons.Archive, stringResource(Res.string.board_archive)) }
        IconButton(onTrash, Modifier.hand()) { Icon(Icons.Delete, stringResource(Res.string.board_trash)) }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyBoard(modifier: Modifier) {
    Column(modifier.padding(32.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(Res.string.board_empty), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(Res.string.board_empty_hint),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A card's place in the grid: blank while it is carried, held at its height while it is open as the editor. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NoteSlot(
    note: BoardNote,
    drag: BoardDrag,
    swipe: BoardSwipe,
    media: Media,
    chosen: Boolean,
    choosing: Boolean,
    onOpen: (BoardNote) -> Unit,
    onChoose: (Any) -> Unit,
    onTick: (String, String) -> Unit,
    modifier: Modifier,
) {
    val tick = if (choosing) null else { key: String -> onTick(note.id, key) }
    val stage = LocalBoardStage.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val swiped = swipe.key == note.id
    val shared = LocalBoardShared.current
    val open = stage?.open == true && stage.key == sheetKey(note.id)
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(
        modifier
            .then(if (open && height > 0) Modifier.height(with(density) { height.toDp() }) else Modifier)
            .onSizeChanged { if (!open) height = it.height }
            .onGloballyPositioned { if (drag.key == note.id) drag.slot = drag.board?.localBoundingBoxOf(it, clipBounds = false) }
            .alpha(if (drag.key == note.id) 0f else 1f)
            .zIndex(if (swiped) 1f else 0f)
            .graphicsLayer {
                if (swiped) {
                    translationX = swipe.dx
                    alpha = swipe.alpha
                }
            }
            .hoverable(hover)
            .testTag(note.id),
    ) {
        val click = Modifier.clip(CARD_SHAPE).hand().clickable { onOpen(note) }
        if (shared == null) {
            BoardCard(note, media, chosen, click, onTick = tick)
        } else {
            with(shared) {
                AnimatedVisibility(!open, enter = HOLD_IN, exit = HOLD_OUT) {
                    BoardCard(
                        note,
                        media,
                        chosen,
                        Modifier.sharedBounds(
                            rememberSharedContentState(sheetKey(note.id)),
                            this,
                            enter = HOLD_IN,
                            exit = HOLD_OUT,
                            boundsTransform = SHEET,
                            zIndexInOverlay = 0.5f,
                            clipInOverlayDuringTransition = OverlayClip(CARD_SHAPE),
                        ).then(click),
                        Modifier.animateEnterExit(enter = WORDS_IN, exit = WORDS_OUT),
                        tick,
                    )
                }
            }
        }
        // A chosen card wears its frame (Keep on a phone); with a mouse a mark on its corner chooses it, shown on hover,
        // on every card while choosing, checked on the chosen ones.
        if (!open && LocalPagerArrows.current && (chosen || hovered || choosing)) {
            ChooseMark(chosen, { onChoose(note.id) }, Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp))
        }
    }
}

@Composable
private fun ChooseMark(chosen: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.size(26.dp).clip(CircleShape).background(if (chosen) colors.primary else colors.surface.copy(alpha = 0.9f))
            .border(1.dp, if (chosen) colors.primary else colors.outline, CircleShape)
            .hand().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Check,
            stringResource(Res.string.board_choose),
            Modifier.size(18.dp),
            tint = if (chosen) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

/** The carried card above the grid: a little larger, with a shadow. */
@Composable
private fun LiftedCard(note: BoardNote, drag: BoardDrag, media: Media) {
    val density = LocalDensity.current
    BoardCard(
        note,
        media,
        chosen = false,
        Modifier.offset { drag.offset.round() }
            .requiredSize(with(density) { drag.size.width.toDp() }, with(density) { drag.size.height.toDp() })
            .graphicsLayer {
                val scale = 1f + 0.04f * drag.lift.value
                scaleX = scale
                scaleY = scale
                shadowElevation = 10.dp.toPx() * drag.lift.value
                shape = CARD_SHAPE
                clip = true
            },
    )
}

/** «Заметка…» and ☑: a new note or a new list, growing out of the bar as its sheet. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun QuickBar(create: (list: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val stage = LocalBoardStage.current
    val shared = LocalBoardShared.current
    val open = stage?.open == true && stage.key == sheetKey(null)
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(52.dp)) {
        if (shared == null) {
            Bar(create, Modifier)
        } else {
            with(shared) {
                AnimatedVisibility(!open, enter = HOLD_IN, exit = HOLD_OUT) {
                    Bar(
                        create,
                        Modifier.sharedBounds(
                            rememberSharedContentState(sheetKey(null)),
                            this,
                            enter = HOLD_IN,
                            exit = HOLD_OUT,
                            boundsTransform = SHEET,
                            zIndexInOverlay = 0.5f,
                            clipInOverlayDuringTransition = OverlayClip(BAR_SHAPE),
                        ),
                        Modifier.animateEnterExit(enter = WORDS_IN, exit = WORDS_OUT),
                    )
                }
            }
        }
    }
}

private val BAR_SHAPE = RoundedCornerShape(26.dp)

@Composable
private fun Bar(create: (list: Boolean) -> Unit, modifier: Modifier, words: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), shape = BAR_SHAPE, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(words.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).fillMaxSize().clip(BAR_SHAPE).hand().clickable { create(false) }.padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(Res.string.board_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton({ create(true) }, Modifier.hand()) { Icon(Icons.CheckBox, stringResource(Res.string.board_new_list)) }
        }
    }
}
