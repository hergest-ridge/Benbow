package app.benbow.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import app.benbow.app.resources.board_archive_empty
import app.benbow.app.resources.board_archive_title
import app.benbow.app.resources.board_as_grid
import app.benbow.app.resources.board_as_list
import app.benbow.app.resources.board_choose
import app.benbow.app.resources.board_chosen
import app.benbow.app.resources.board_color
import app.benbow.app.resources.board_dial_close
import app.benbow.app.resources.board_empty
import app.benbow.app.resources.board_empty_hint
import app.benbow.app.resources.board_found_none
import app.benbow.app.resources.board_in_archive
import app.benbow.app.resources.board_kind_drawings
import app.benbow.app.resources.board_kind_lists
import app.benbow.app.resources.board_kind_pictures
import app.benbow.app.resources.board_kind_sounds
import app.benbow.app.resources.board_more_in_journal
import app.benbow.app.resources.board_new_list
import app.benbow.app.resources.board_new_note
import app.benbow.app.resources.board_new_text
import app.benbow.app.resources.board_others
import app.benbow.app.resources.board_pin
import app.benbow.app.resources.board_pinned
import app.benbow.app.resources.board_search_clear
import app.benbow.app.resources.board_search_hint
import app.benbow.app.resources.board_to_journal
import app.benbow.app.resources.board_trash
import app.benbow.app.resources.board_unarchive
import app.benbow.app.resources.board_unchoose
import app.benbow.app.resources.board_unpin
import app.benbow.app.resources.cancel
import app.benbow.app.resources.former_back
import app.benbow.app.resources.former_delete
import app.benbow.app.resources.former_many
import app.benbow.app.resources.former_one
import app.benbow.app.resources.former_title
import app.benbow.app.resources.menu
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.tab_board
import app.benbow.client.BoardNote
import app.benbow.client.BoardNotes
import app.benbow.client.Media
import app.benbow.client.SWATCHES
import app.benbow.client.SearchQuery
import app.benbow.client.Settings
import app.benbow.client.archiveOnBoard
import app.benbow.client.board
import app.benbow.client.boardArchive
import app.benbow.client.pinOnBoard
import app.benbow.client.placeOnBoard
import app.benbow.client.searchBoard
import app.benbow.client.tickOnBoard
import app.benbow.ui.sideScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
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
    // The board, its archive or a search on it; each keeps its own scroll.
    var mode by rememberSaveable { mutableStateOf(BOARD) }
    var words by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf(BoardFilter()) }
    LaunchedEffect(Unit) {
        // «Ещё N в записках» in the journal's search opens the board's search on the same words.
        services.boardSearchFor.filterNotNull().collect {
            words = it
            filter = BoardFilter()
            mode = SEARCH
            services.boardSearchFor.value = null
        }
    }
    val archived by remember(mode) { if (mode == ARCHIVE) services.repo.boardArchive() else flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val found by remember(mode, words) { if (mode == SEARCH) services.repo.searchBoard(words) else flowOf(null) }
        .collectAsStateWithLifecycle(null)
    val inJournal by remember(mode, words) {
        if (mode == SEARCH && words.isNotBlank()) services.repo.search(SearchQuery(words)).map { it.size } else flowOf(0)
    }.collectAsStateWithLifecycle(0)
    // The pinned notes changing (one pinned, the last unpinned) start the board's grid afresh where it was: a staggered
    // grid keeps its notes in the columns it once gave them, and «Другие» began in the third (owner, 0.15.0).
    val pinnedIds = data?.pinned?.mapTo(HashSet()) { it.id }
    val lastGrid = remember { arrayOfNulls<LazyStaggeredGridState>(1) }
    val boardGrid = remember(pinnedIds) {
        val old = lastGrid[0]
        LazyStaggeredGridState(old?.firstVisibleItemIndex ?: 0, old?.firstVisibleItemScrollOffset ?: 0).also { lastGrid[0] = it }
    }
    val archiveGrid = rememberLazyStaggeredGridState()
    val searchGrid = rememberLazyStaggeredGridState()
    val grid = when (mode) {
        ARCHIVE -> archiveGrid
        SEARCH -> searchGrid
        else -> boardGrid
    }
    val drag = remember(grid, order) {
        BoardDrag(grid, isCard = ::isNoteKey, canSwap = { a, b -> mode == BOARD && order.sameKind(a, b) }, move = order::move)
    }
    val swipe = remember { BoardSwipe() }
    // Cards glide to new places while one is carried and for a moment after the board changed, never while it only
    // scrolls (owner: a short card beside a long one flew down the screen). A card that has just scrolled out of a
    // staggered grid in the shared transition's lookahead "moves away" to the grid's end, and a glide showed it going.
    var changing by remember { mutableStateOf(true) }
    // A board at its top stays there as notes come and go: a new note shows on top and the columns start level
    // (owner: a staggered grid kept its old first note in place, a new one above it, and the columns out of step).
    var atTop by remember { mutableStateOf(true) }
    LaunchedEffect(boardGrid) {
        snapshotFlow { boardGrid.isScrollInProgress }.collect { scrolling -> if (!scrolling) atTop = !boardGrid.canScrollBackward }
    }
    LaunchedEffect(data) { if (atTop && drag.key == null && mode == BOARD) boardGrid.requestScrollToItem(0) }
    // A card swiped away stays out of sight until it has left the board.
    LaunchedEffect(data) {
        val key = swipe.key ?: return@LaunchedEffect
        if (data?.let { (it.pinned + it.others).none { n -> n.id == key } } == true) swipe.gone()
    }
    val stage = LocalBoardStage.current
    val haptic = LocalHapticFeedback.current
    var chosen by remember { mutableStateOf(setOf<Any>()) }
    var coloring by remember { mutableStateOf(false) }
    // The + button's choice (Keep's): words or a list.
    var dial by remember { mutableStateOf(false) }
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
    LaunchedEffect(mode) { unchoose() }
    NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = (chosen.isNotEmpty() || mode != BOARD || dial) && stage?.open != true,
    ) {
        when {
            dial -> dial = false
            chosen.isNotEmpty() -> unchoose()
            else -> mode = BOARD
        }
    }
    val pinnedLabel = stringResource(Res.string.board_pinned)
    val othersLabel = stringResource(Res.string.board_others)
    val archivedLabel = stringResource(Res.string.board_in_archive)
    val sections = when (mode) {
        ARCHIVE -> listOf(Section("h-archive", null, archived))

        SEARCH -> found.orEmpty().filter(filter::matches).let { all ->
            listOfNotNull(
                Section("h-found", null, all.filter { !it.archived }),
                all.filter { it.archived }.takeIf { it.isNotEmpty() }?.let { Section("h-archived", archivedLabel, it) },
            )
        }

        else -> order.parts().let { (pinned, others) ->
            listOfNotNull(
                pinned.takeIf { it.isNotEmpty() }?.let { Section("h-pinned", pinnedLabel, it) },
                Section("h-others", othersLabel.takeIf { pinned.isNotEmpty() }, others),
            )
        }
    }
    val shown = sections.flatMap { it.notes }
    // The open note's box wears its card's face near the card: the card as the board shows it now.
    stage?.face = { id -> shown.firstOrNull { it.id == id } ?: data?.let { (it.pinned + it.others).firstOrNull { n -> n.id == id } } }
    stage?.media = services.media
    val chosenNotes = { shown.filter { it.id in chosen } }
    val archive: (List<String>, Boolean) -> Unit = { ids, into ->
        services.scope.launch { ids.forEach { services.repo.archiveOnBoard(it, into) } }
        if (into) services.messages.tryEmit(AppMessage.BoardArchived(ids))
        unchoose()
    }
    val trashNow: (List<String>) -> Unit = { ids ->
        services.scope.launch { ids.forEach { services.repo.moveToTrash(it) } }
        services.messages.tryEmit(AppMessage.BoardTrashed(ids))
        unchoose()
    }
    // Notes that were journal entries ask first: back into the journal, or into the trash anyway (ADR-040).
    var guarding by remember { mutableStateOf<List<String>?>(null) }
    val trash: (List<String>) -> Unit = { ids ->
        if (shown.any { it.id in ids && it.fromJournal }) guarding = ids else trashNow(ids)
    }
    val toJournal: (List<String>) -> Unit = { ids ->
        val notes = shown.filter { it.id in ids }
        notes.firstOrNull()?.let { first -> services.toJournal(notes.map { it.id }, first.entry.day) }
        unchoose()
    }
    guarding?.let { ids ->
        val former = shown.filter { it.id in ids && it.fromJournal }
        FormerEntryDialog(
            count = former.size,
            day = former.singleOrNull()?.takeIf { ids.size == 1 }?.entry?.day,
            onBack = {
                guarding = null
                toJournal(former.map { it.id })
                (ids - former.map { it.id }.toSet()).takeIf { it.isNotEmpty() }?.let(trashNow)
            },
            onDelete = {
                guarding = null
                trashNow(ids)
            },
            onDismiss = { guarding = null },
        )
    }
    // «Открыть» after a move onto the board: the board scrolls to the note and it glows a moment.
    var glowing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        services.showOnBoard.filterNotNull().collect { id ->
            mode = BOARD
            glowing = id
            services.showOnBoard.value = null
        }
    }
    val glowAt = glowing?.let { id -> gridIndex(sections, id) }
    LaunchedEffect(glowing, glowAt) {
        val at = glowAt ?: return@LaunchedEffect
        runCatching { boardGrid.animateScrollToItem(at) }
        delay(1600)
        glowing = null
    }
    val pin: () -> Unit = {
        val notes = chosenNotes()
        val pinned = notes.all { it.pinned }
        services.scope.launch { notes.forEach { services.repo.pinOnBoard(it.id, !pinned) } }
        unchoose()
    }
    // One card chosen moves a place back or on with Ctrl and the arrows (desktop and web), on the board itself.
    val step: (String, Int) -> Unit = { id, by ->
        val (pinned, others) = order.parts()
        val part = if (pinned.any { it.id == id }) pinned else others
        val at = part.indexOfFirst { it.id == id }
        val to = (at + by).coerceIn(0, part.lastIndex)
        if (mode == BOARD && at >= 0 && to != at) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            services.scope.launch { services.repo.placeOnBoard(id, to) }
        }
    }
    // A box ticked right on its card: the note gets a revision with only that box changed (it moves to «Отмечено», or,
    // with the ticked boxes kept in place, is crossed out there and can be unticked the same way).
    val tick: (String, String, Boolean) -> Unit = { id, key, checked ->
        haptic.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        services.scope.launch { services.repo.tickOnBoard(id, key, checked) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(chosen.isNotEmpty()) { if (chosen.isNotEmpty()) runCatching { focus.requestFocus() } }
    val keys = Modifier.onKeyEvent { event ->
        val ids = chosen.map { it as String }
        if (event.type != KeyEventType.KeyDown || ids.isEmpty()) return@onKeyEvent false
        when {
            event.key == Key.Escape -> unchoose()
            event.key == Key.Delete || event.key == Key.Backspace -> trash(ids)
            event.key == Key.E && !event.isCtrlPressed -> archive(ids, chosenNotes().any { !it.archived })
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
        dial = false
        stage?.opening(null, null)
        nav.open(Route.Editor(null, services.today(), opened = services.now(), board = true, list = list))
    }
    val toJournalSearch = {
        services.searchFor.value = SearchQuery(words)
        nav.open(Route.Search)
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val wide = LocalWide.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = if (wide) 16.dp else 8.dp
            val lanes = if (view.list) 1 else autoLanes(maxWidth - side * 2, if (wide) 240.dp else 160.dp).coerceAtMost(if (wide) 6 else 2)
            remember(data, lanes) { changing = true }
            LaunchedEffect(data, lanes) {
                delay(1_000)
                changing = false
            }
            val glide = GLIDE.takeIf { changing || drag.key != null || order.carried != null }
            // A list in a wide window is a readable column in the middle.
            val inset = if (view.list) max(side.value, (maxWidth.value - LIST_WIDTH) / 2).dp else side
            Column(Modifier.fillMaxSize().then(keys).focusRequester(focus).focusable()) {
                when {
                    chosen.isNotEmpty() -> {
                        val notes = chosenNotes()
                        val inArchive = notes.isNotEmpty() && notes.all { it.archived }
                        ChoiceBar(
                            chosen.size,
                            pinned = notes.isNotEmpty() && notes.all { it.pinned },
                            archived = inArchive,
                            onClear = unchoose,
                            onJournal = { toJournal(chosen.map { it as String }) },
                            onPin = pin,
                            onColor = { coloring = !coloring },
                            onArchive = { archive(chosen.map { it as String }, !inArchive) },
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

                    mode == SEARCH -> {
                        SearchTop(words, onWords = { words = it }, onBack = { mode = BOARD })
                        FilterRow(found.orEmpty(), filter) { filter = it }
                    }

                    mode == ARCHIVE -> SubTop(stringResource(Res.string.board_archive_title), view.list, onBack = { mode = BOARD }) {
                        view.change(!view.list)
                    }

                    else -> BoardTop(view.list, onSearch = { mode = SEARCH }, onArchive = { mode = ARCHIVE }) { view.change(!view.list) }
                }
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
                            choosing = { chosen.isNotEmpty() },
                            onPinch = { step -> view.change(step < 0) },
                            swipe = swipe.takeIf { mode == BOARD },
                            onSwiped = { key -> archive(listOf(key as String), true) },
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
                    val loaded = if (mode == SEARCH) found != null else data != null
                    if (loaded && shown.isEmpty()) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            when (mode) {
                                SEARCH -> EmptyNote(stringResource(Res.string.board_found_none))
                                ARCHIVE -> EmptyNote(stringResource(Res.string.board_archive_empty))
                                else -> EmptyBoard(Modifier)
                            }
                            if (mode == SEARCH &&
                                inJournal > 0
                            ) {
                                ElsewhereHint(stringResource(Res.string.board_more_in_journal, inJournal), toJournalSearch)
                            }
                        }
                    } else {
                        LazyVerticalStaggeredGrid(
                            StaggeredGridCells.Fixed(lanes),
                            Modifier.fillMaxSize().onGloballyPositioned { grid ->
                                drag.board?.let { drag.gridBounds = it.localBoundingBoxOf(grid, clipBounds = false) }
                            },
                            state = grid,
                            // Room above the first row for a card's corner mark.
                            // Room under the last row for the + button.
                            contentPadding = PaddingValues(start = inset, end = inset, top = 10.dp, bottom = 96.dp),
                            verticalItemSpacing = 8.dp,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            sections.forEach { section ->
                                section.label?.let { label ->
                                    item(section.key, span = StaggeredGridItemSpan.FullLine) { Header(label) }
                                }
                                items(section.notes, key = { it.id }, contentType = { "note" }) { note ->
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
                                        Modifier.animateItem(placementSpec = glide).glow(note.id == glowing),
                                    )
                                }
                            }
                            if (mode == SEARCH && inJournal > 0) {
                                item("h-journal", span = StaggeredGridItemSpan.FullLine) {
                                    ElsewhereHint(stringResource(Res.string.board_more_in_journal, inJournal), toJournalSearch)
                                }
                            }
                        }
                    }
                    drag.key?.let { key -> shown.firstOrNull { it.id == key }?.let { LiftedCard(it, drag, services.media) } }
                    if (mode == BOARD) NewNoteDial(dial, onDial = { dial = it }, onNew = create, Modifier.matchParentSize())
                }
            }
        }
    }
}

/** Where a note sits among the grid's items (headers included), for scrolling to it. */
private fun gridIndex(sections: List<Section>, id: String): Int? {
    var at = 0
    sections.forEach { section ->
        if (section.label != null) at++
        val i = section.notes.indexOfFirst { it.id == id }
        if (i >= 0) return at + i
        at += section.notes.size
    }
    return null
}

/** A ring that shows a card a moment and fades (after «Открыть»). */
@Composable
private fun Modifier.glow(on: Boolean): Modifier {
    val glow by animateFloatAsState(if (on) 1f else 0f, tween(if (on) 200 else 900))
    val color = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        if (glow > 0f) {
            drawRoundRect(color.copy(alpha = glow), cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(3.dp.toPx()))
        }
    }
}

/** Deleting notes that were journal entries: the journal back first (ADR-040), or the trash anyway. */
@Composable
internal fun FormerEntryDialog(count: Int, day: String?, onBack: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val months = stringArrayResource(Res.array.months_genitive)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.former_title)) },
        text = {
            Text(
                if (day != null) {
                    stringResource(Res.string.former_one, dateLabel(day, months))
                } else {
                    stringResource(Res.string.former_many, count)
                },
            )
        },
        confirmButton = { TextButton(onBack, Modifier.hand()) { Text(stringResource(Res.string.former_back)) } },
        dismissButton = {
            Row {
                TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) }
                TextButton(onDelete, Modifier.hand()) { Text(stringResource(Res.string.former_delete)) }
            }
        },
    )
}

private const val BOARD = "board"
private const val ARCHIVE = "archive"
private const val SEARCH = "search"

/** A part of the grid: its notes under a header (none for the first part). */
private class Section(val key: String, val label: String?, val notes: List<BoardNote>)

/** The board search's filters (Keep's): a kind of note, a color, a tag; null is any. */
private data class BoardFilter(val kind: String? = null, val color: String? = null, val tag: String? = null) {
    fun matches(note: BoardNote) = (kind == null || note.isKind(kind)) && (color == null || note.entry.background == color) &&
        (tag == null || note.entry.tags.any { it.id == tag })
}

private fun BoardNote.isKind(kind: String) = when (kind) {
    LISTS -> card.ticked > 0 || card.lines.any { it.box }
    PICTURES -> picture != null
    DRAWINGS -> "drawing" in kinds
    else -> "voice" in kinds || "audio" in kinds
}

private const val LISTS = "lists"
private const val PICTURES = "pictures"
private const val DRAWINGS = "drawings"
private const val SOUNDS = "sounds"

/** The search's words, ← back to the board, ✕ to clear them. */
@Composable
private fun SearchTop(words: String, onWords: (String) -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        TextField(
            words,
            onWords,
            Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(stringResource(Res.string.board_search_hint)) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        if (words.isNotEmpty()) {
            IconButton({ onWords("") }, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.board_search_clear)) }
        }
    }
}

/** Keep's search filters: kinds of notes, then the colors and tags the found notes have. */
@Composable
private fun FilterRow(found: List<BoardNote>, filter: BoardFilter, onFilter: (BoardFilter) -> Unit) {
    val colors = found.mapNotNull { it.entry.background }.distinct().let { present -> SWATCHES.filter { it in present } }
    val tags = found.flatMap { it.entry.tags }.distinctBy { it.id }
    Row(
        Modifier.fillMaxWidth().sideScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            LISTS to Res.string.board_kind_lists,
            PICTURES to Res.string.board_kind_pictures,
            DRAWINGS to Res.string.board_kind_drawings,
            SOUNDS to Res.string.board_kind_sounds,
        ).forEach { (kind, label) ->
            val on = filter.kind == kind
            FilterChip(on, { onFilter(filter.copy(kind = kind.takeUnless { on })) }, { Text(stringResource(label)) }, Modifier.hand())
        }
        colors.forEach { color ->
            val on = filter.color == color
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(swatch(color) ?: Color.Transparent)
                    .border(
                        if (on) 2.dp else 1.dp,
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .hand().clickable { onFilter(filter.copy(color = color.takeUnless { on })) },
            )
        }
        tags.forEach { tag ->
            val on = filter.tag == tag.id
            FilterChip(on, {
                onFilter(filter.copy(tag = tag.id.takeUnless { on }))
            }, { Text(LocalTags.current.name(tag)) }, Modifier.hand())
        }
    }
}

/** A sub-page of the board (its archive): ← back, its title, the grid or list switch. */
@Composable
private fun SubTop(title: String, list: Boolean, onBack: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onToggle, Modifier.hand()) {
            Icon(
                if (list) Icons.Blocks else Icons.ViewAgenda,
                stringResource(if (list) Res.string.board_as_grid else Res.string.board_as_list),
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** «Ещё N в дневнике» / «Ещё N в записках»: what the other side has for the same words, a tap away. */
@Composable
internal fun ElsewhereHint(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        TextButton(onClick, Modifier.hand()) { Text(text) }
    }
}

private const val LIST_WIDTH = 640f

/** The title bar's height, and the choosing bar's in its place. */
private val BAR_HEIGHT = 60.dp

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
private fun BoardTop(list: Boolean, onSearch: () -> Unit, onArchive: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp).height(BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.tab_board), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        IconButton(onSearch, Modifier.hand()) { Icon(Icons.Search, stringResource(Res.string.board_search_hint)) }
        IconButton(onArchive, Modifier.hand()) { Icon(Icons.Archive, stringResource(Res.string.board_archive_title)) }
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
    archived: Boolean,
    onClear: () -> Unit,
    onJournal: () -> Unit,
    onPin: () -> Unit,
    onColor: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
) {
    Row(
        // As tall as the title bar it stands in for: the cards never move (owner).
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 8.dp).height(BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClear, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.board_unchoose)) }
        // Keep's way: the number alone, so the actions fit a phone; read out in full.
        val said = stringResource(Res.string.board_chosen, count)
        Text("$count", Modifier.weight(1f).semantics { contentDescription = said }, style = MaterialTheme.typography.titleLarge)

        IconButton(onArchive, Modifier.hand()) {
            if (archived) {
                Icon(Icons.Unarchive, stringResource(Res.string.board_unarchive))
            } else {
                Icon(Icons.Archive, stringResource(Res.string.board_archive))
            }
        }
        IconButton(onTrash, Modifier.hand()) { Icon(Icons.Delete, stringResource(Res.string.board_trash)) }
        // What is needed less (owner): under ⋮.
        var more by remember { mutableStateOf(false) }
        Box {
            IconButton({ more = true }, Modifier.hand()) { Icon(Icons.More, stringResource(Res.string.menu)) }
            DropdownMenu(more, { more = false }) {
                if (!archived) {
                    DropdownMenuItem(
                        { Text(stringResource(if (pinned) Res.string.board_unpin else Res.string.board_pin)) },
                        {
                            more = false
                            onPin()
                        },
                        Modifier.hand(),
                    )
                }
                DropdownMenuItem({ Text(stringResource(Res.string.board_color)) }, {
                    more = false
                    onColor()
                }, Modifier.hand())
                DropdownMenuItem({ Text(stringResource(Res.string.board_to_journal)) }, {
                    more = false
                    onJournal()
                }, Modifier.hand())
            }
        }
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
internal fun EmptyBoard(modifier: Modifier) {
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

/**
 * A card's place in the grid: blank while it is carried, and while it is open as the editor (held at its height; the
 * note's box grows from where it tells the stage it is).
 */
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
    onTick: (String, String, Boolean) -> Unit,
    modifier: Modifier,
) {
    val tick = if (choosing) null else { key: String, checked: Boolean -> onTick(note.id, key, checked) }
    val stage = LocalBoardStage.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val swiped = swipe.key == note.id
    val open = stage?.holds(note.id) == true
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(
        modifier
            .then(if (open && height > 0) Modifier.height(with(density) { height.toDp() }) else Modifier)
            .onSizeChanged { if (!open) height = it.height }
            .onGloballyPositioned {
                stage?.place(note.id, it.boundsInRoot())
                if (drag.key == note.id) drag.slot = drag.board?.localBoundingBoxOf(it, clipBounds = false)
            }
            .alpha(if (drag.key == note.id || open) 0f else 1f)
            .zIndex(if (swiped) 1f else 0f)
            .graphicsLayer {
                if (swiped) {
                    translationX = swipe.dx
                    alpha = swipe.alpha
                }
            }
            .hoverable(hover)
            .testTag(note.id)
            .then(if (open) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
        BoardCard(note, media, chosen, Modifier.clip(CARD_SHAPE).hand().clickable { onOpen(note) }, onTick = tick)
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

/** The carried card above the grid, with a shadow (owner: no growing). */
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
                shadowElevation = 10.dp.toPx() * drag.lift.value
                shape = CARD_SHAPE
                clip = true
            },
    )
}

/**
 * Keep's + button: a tap shows what to start above it, «Текст» or «Список» (the + turns into ×; a tap beside or Back
 * puts them away). The new note's sheet grows out of the button, quicker than a card's.
 */
@Composable
private fun NewNoteDial(open: Boolean, onDial: (Boolean) -> Unit, onNew: (list: Boolean) -> Unit, modifier: Modifier) {
    Box(modifier) {
        AnimatedVisibility(open, enter = fadeIn(tween(DIAL_MILLIS)), exit = fadeOut(tween(DIAL_MILLIS))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onDial(false) },
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val from = TransformOrigin(1f, 1f)
            listOf(true to Res.string.board_new_list, false to Res.string.board_new_text).forEach { (list, label) ->
                AnimatedVisibility(
                    open,
                    enter = fadeIn(tween(DIAL_MILLIS)) + scaleIn(tween(DIAL_MILLIS), initialScale = 0.8f, transformOrigin = from),
                    exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.8f, transformOrigin = from),
                ) {
                    DialOption(if (list) Icons.CheckBox else Icons.TextFields, stringResource(label)) { onNew(list) }
                }
            }
            NewNoteButton(open) { onDial(!open) }
        }
    }
}

@Composable
private fun DialOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(48.dp).hand(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * The + button. A new note's box grows out of it (it tells the stage where it is); the button stays as it is (owner:
 * as Keep's; growing back into it, the old sheet stretched over the screen).
 */
@Composable
private fun NewNoteButton(dial: Boolean, onClick: () -> Unit) {
    val stage = LocalBoardStage.current
    val turn by animateFloatAsState(if (dial) 45f else 0f, tween(DIAL_MILLIS))
    val label = stringResource(if (dial) Res.string.board_dial_close else Res.string.board_new_note)
    val icon = Modifier.graphicsLayer { rotationZ = turn }
    Box(Modifier.size(FAB_SIZE).onGloballyPositioned { stage?.button = it.boundsInRoot() }) { Fab(onClick, Modifier, label, icon) }
}

private const val DIAL_MILLIS = 150

private val FAB_SIZE = 56.dp
private val FAB_SHAPE = RoundedCornerShape(16.dp)

@Composable
private fun Fab(onClick: () -> Unit, modifier: Modifier, label: String, icon: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(FAB_SIZE).hand(),
        shape = FAB_SHAPE,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Add, label, icon.size(28.dp)) }
    }
}
