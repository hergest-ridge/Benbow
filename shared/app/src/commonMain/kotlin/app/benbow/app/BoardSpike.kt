package app.benbow.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.benbow.app.resources.Res
import app.benbow.app.resources.board_cards
import app.benbow.app.resources.board_checked
import app.benbow.app.resources.board_close
import app.benbow.app.resources.board_columns
import app.benbow.app.resources.board_columns_auto
import app.benbow.app.resources.board_frames
import app.benbow.app.resources.board_others
import app.benbow.app.resources.board_pinned
import app.benbow.app.resources.board_spike
import app.benbow.app.resources.board_text_hint
import app.benbow.app.resources.board_title_hint
import app.benbow.app.resources.tab_board
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// The board's K0 spike (ADR-040): made-up notes in a staggered grid, to feel dragging, opening and the columns on the
// phone, the desktop and the web before any data exists. Nothing here is saved; K2 replaces it with the real board.

@Stable
internal class SpikeNote(
    val id: String,
    title: String,
    text: String,
    checks: List<SpikeCheck>,
    /** A made-up picture's width to height, or null. */
    val picture: Float?,
    val color: String?,
) {
    var title by mutableStateOf(title)
    var text by mutableStateOf(text)
    val checks = checks.toMutableStateList()
}

@Stable
internal class SpikeCheck(val text: String, checked: Boolean) {
    var checked by mutableStateOf(checked)
}

/** Pinned notes first, then the others; a card moves only among its own kind. */
@Stable
internal class SpikeBoard(count: Int) {
    val pinned = mutableStateListOf<SpikeNote>()
    val others = mutableStateListOf<SpikeNote>()

    init {
        fill(count)
    }

    fun fill(count: Int) {
        val notes = spikeNotes(count)
        pinned.clear()
        others.clear()
        pinned += notes.take(PINNED)
        others += notes.drop(PINNED)
    }

    fun note(id: String) = pinned.firstOrNull { it.id == id } ?: others.first { it.id == id }

    private fun listOf(id: Any) = if (pinned.any { it.id == id }) pinned else others

    fun canSwap(a: Any, b: Any) = listOf(a) === listOf(b)

    fun move(lifted: Any, other: Any) {
        val list = listOf(lifted)
        val to = list.indexOfFirst { it.id == other }
        list.add(to, list.removeAt(list.indexOfFirst { it.id == lifted }))
    }
}

private const val PINNED = 4

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun BoardScreen(board: SpikeBoard = remember { SpikeBoard(300) }) {
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = openId != null) { openId = null }
    val close = { openId = null }
    // A surface for the text's color in both themes, then the layout that lets a card grow into the open note.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = LocalWide.current
                val tallest = maxHeight * 0.85f
                Board(board, openId) { id ->
                    editing = id
                    openId = id
                }
                AnimatedVisibility(openId != null, enter = HOLD_IN, exit = HOLD_OUT) {
                    val id = editing ?: return@AnimatedVisibility
                    // Opening, the note's sheet lies above the card's; closing, below, so the card's words come back.
                    val opening = transition.targetState == EnterExitState.Visible
                    val sheet = Modifier.sharedBounds(
                        rememberSharedContentState("sheet-$id"),
                        this,
                        enter = HOLD_IN,
                        exit = HOLD_OUT,
                        boundsTransform = SHEET,
                        zIndexInOverlay = if (opening) 1f else 0f,
                        clipInOverlayDuringTransition = OverlayClip(CARD_SHAPE),
                    )
                    val words = Modifier.animateEnterExit(enter = WORDS_IN, exit = WORDS_OUT)
                    if (wide) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.fillMaxSize()
                                    .animateEnterExit(enter = fadeIn(tween(SHEET_MILLIS)), exit = fadeOut(tween(SHEET_MILLIS)))
                                    .background(Color.Black.copy(alpha = 0.32f))
                                    .clickable(MutableInteractionSource(), indication = null, onClick = close),
                            )
                            SpikeEditor(
                                board.note(id),
                                CARD_SHAPE,
                                close,
                                Modifier.padding(24.dp).widthIn(max = 600.dp).heightIn(max = tallest).then(sheet),
                                words,
                            )
                        }
                    } else {
                        SpikeEditor(board.note(id), RoundedCornerShape(0.dp), close, Modifier.fillMaxSize().then(sheet), words)
                    }
                }
            }
        }
    }
}

private val CARD_SHAPE = RoundedCornerShape(12.dp)

// Keep-like opening (owner: no fades): one sheet grows from the card into the note and shrinks back into its place.
// Both sides stay opaque for the whole move, one sheet over the other in the same color; only the words fade.
private const val SHEET_MILLIS = 300

@OptIn(ExperimentalSharedTransitionApi::class)
private val SHEET = BoundsTransform { _, _ -> tween(SHEET_MILLIS, easing = FastOutSlowInEasing) }

/** Keeps a side of the move on screen for the sheet's whole way without changing how it looks. */
private val HOLD_IN = fadeIn(tween(SHEET_MILLIS), initialAlpha = 1f)
private val HOLD_OUT = fadeOut(tween(SHEET_MILLIS), targetAlpha = 1f)
private val WORDS_IN = fadeIn(tween(200, delayMillis = 60))
private val WORDS_OUT = fadeOut(tween(60))

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.Board(board: SpikeBoard, openId: String?, onOpen: (String) -> Unit) {
    val grid = rememberLazyStaggeredGridState()
    val drag = remember(grid, board) {
        BoardDrag(grid, isCard = { (it as? String)?.startsWith("n") == true }, canSwap = board::canSwap, move = board::move)
    }
    var columns by remember { mutableStateOf<Int?>(null) }
    var count by remember { mutableIntStateOf(board.pinned.size + board.others.size) }
    var chosen by remember { mutableStateOf(setOf<Any>()) }
    val choose = { key: Any -> chosen = if (key in chosen) chosen - key else chosen + key }
    NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = chosen.isNotEmpty() && openId == null,
    ) { chosen = emptySet() }
    var frames by remember { mutableStateOf<FrameSummary?>(null) }
    val moving by remember { derivedStateOf { drag.key != null || grid.isScrollInProgress } }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val times = ArrayList<Long>()
        try {
            while (true) times += withFrameNanos { it }
        } finally {
            frames = FrameSummary.of(times) ?: frames
        }
    }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val wide = LocalWide.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = if (wide) 16.dp else 8.dp
        // A phone takes one or two columns (owner: more are too many there), a wide window up to six.
        val most = if (wide) 6 else 2
        val lanes = (columns ?: autoLanes(maxWidth - side * 2, if (wide) 220.dp else 160.dp)).coerceAtMost(most)
        val open = { id: String -> if (chosen.isEmpty()) onOpen(id) else choose(id) }
        Column(Modifier.fillMaxSize()) {
            BoardTop(columns, most, count, frames, onColumns = { columns = it }) {
                count = it
                board.fill(it)
                scope.launch { grid.scrollToItem(0) }
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
                        onPinch = { step -> columns = (lanes + step).coerceIn(1, most) },
                    ),
            ) {
                LazyVerticalStaggeredGrid(
                    StaggeredGridCells.Fixed(lanes),
                    Modifier.fillMaxSize().onGloballyPositioned { grid ->
                        drag.board?.let { drag.gridBounds = it.localBoundingBoxOf(grid, clipBounds = false) }
                    },
                    state = grid,
                    contentPadding = PaddingValues(start = side, end = side, top = 4.dp, bottom = 24.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (board.pinned.isNotEmpty()) {
                        item("h-pinned", span = StaggeredGridItemSpan.FullLine) { Header(stringResource(Res.string.board_pinned)) }
                        items(board.pinned, key = { it.id }, contentType = { "note" }) { note ->
                            NoteSlot(note, drag, openId, note.id in chosen, open, Modifier.animateItem(placementSpec = GLIDE))
                        }
                        item("h-others", span = StaggeredGridItemSpan.FullLine) { Header(stringResource(Res.string.board_others)) }
                    }
                    items(board.others, key = { it.id }, contentType = { "note" }) { note ->
                        NoteSlot(note, drag, openId, note.id in chosen, open, Modifier.animateItem(placementSpec = GLIDE))
                    }
                }
                drag.key?.let { key -> LiftedCard(board.note(key as String), drag) }
            }
        }
    }
}

private val GLIDE = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

private fun autoLanes(width: Dp, narrowest: Dp, gap: Dp = 8.dp) = max(1, ((width + gap) / (narrowest + gap)).toInt())

@Composable
private fun BoardTop(columns: Int?, most: Int, count: Int, frames: FrameSummary?, onColumns: (Int?) -> Unit, onCount: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(Res.string.tab_board), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(Res.string.board_spike),
                Modifier.padding(bottom = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        frames?.let {
            Text(
                stringResource(Res.string.board_frames, it.frames, it.median, it.late, it.worst),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.board_columns), style = MaterialTheme.typography.labelLarge)
            FilterChip(columns == null, { onColumns(null) }, { Text(stringResource(Res.string.board_columns_auto)) })
            (1..min(most, 5)).forEach { n -> FilterChip(columns == n, { onColumns(n) }, { Text("$n") }) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.board_cards), style = MaterialTheme.typography.labelLarge)
            listOf(60, 300, 1000).forEach { n -> FilterChip(count == n, { onCount(n) }, { Text("$n") }) }
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

/** A card's place in the grid: blank while it is lifted, held at its height while it is open as the editor. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.NoteSlot(
    note: SpikeNote,
    drag: BoardDrag,
    openId: String?,
    chosen: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val open = openId == note.id
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(
        modifier
            .then(if (open && height > 0) Modifier.height(with(density) { height.toDp() }) else Modifier)
            .onSizeChanged { if (!open) height = it.height }
            .onGloballyPositioned { if (drag.key == note.id) drag.slot = drag.board?.localBoundingBoxOf(it, clipBounds = false) }
            .alpha(if (drag.key == note.id) 0f else 1f)
            .testTag(note.id),
    ) {
        AnimatedVisibility(!open, enter = HOLD_IN, exit = HOLD_OUT) {
            SpikeCard(
                note,
                chosen,
                Modifier.sharedBounds(
                    rememberSharedContentState("sheet-${note.id}"),
                    this,
                    enter = HOLD_IN,
                    exit = HOLD_OUT,
                    boundsTransform = SHEET,
                    zIndexInOverlay = 0.5f,
                    clipInOverlayDuringTransition = OverlayClip(CARD_SHAPE),
                ).clip(CARD_SHAPE).hand().clickable { onOpen(note.id) },
                Modifier.animateEnterExit(enter = WORDS_IN, exit = WORDS_OUT),
            )
        }
    }
}

/** The lifted card above the grid: a little larger, with a shadow. */
@Composable
private fun LiftedCard(note: SpikeNote, drag: BoardDrag) {
    val density = LocalDensity.current
    SpikeCard(
        note,
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

@Composable
private fun SpikeCard(note: SpikeNote, chosen: Boolean, modifier: Modifier = Modifier, words: Modifier = Modifier) {
    val color = swatch(note.color)
    val outline = when {
        chosen -> MaterialTheme.colorScheme.primary
        color == null -> MaterialTheme.colorScheme.outlineVariant
        else -> Color.Transparent
    }
    Column(
        modifier.fillMaxWidth().clip(CARD_SHAPE).background(color ?: MaterialTheme.colorScheme.surface)
            .border(if (chosen) 2.dp else 1.dp, outline, CARD_SHAPE),
    ) {
        Column(words.fillMaxWidth()) { CardWords(note) }
    }
}

@Composable
private fun CardWords(note: SpikeNote) {
    note.picture?.let { SpikePicture(it, note.id) }
    val open = note.checks.filter { !it.checked }
    if (note.title.isBlank() && note.text.isBlank() && note.checks.isEmpty()) return
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (note.title.isNotBlank()) {
            Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (note.text.isNotBlank()) {
            Text(note.text, style = MaterialTheme.typography.bodyMedium, maxLines = 10, overflow = TextOverflow.Ellipsis)
        }
        open.take(8).forEach { CheckLine(it, 22.dp, Modifier) }
        val done = note.checks.size - open.size
        if (done > 0) {
            Text(
                stringResource(Res.string.board_checked, done),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CheckLine(check: SpikeCheck, box: Dp, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (check.checked) Icons.CheckBox else Icons.CheckBoxBlank,
            null,
            Modifier.size(box),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            check.text,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (check.checked) TextDecoration.LineThrough else null,
            color = if (check.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A made-up picture: a gradient with the note's proportions, never a real photo. */
@Composable
private fun SpikePicture(ratio: Float, id: String) {
    val (from, to) = PICTURES[(id.hashCode() and Int.MAX_VALUE) % PICTURES.size]
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Brush.linearGradient(listOf(Color(from), Color(to)))))
}

private val PICTURES = listOf(
    0xFFF7C59F to 0xFFE76F51,
    0xFF8ECAE6 to 0xFF219EBC,
    0xFFB7E4C7 to 0xFF40916C,
    0xFFE9D8A6 to 0xFFBB3E03,
    0xFFCDB4DB to 0xFF6D597A,
    0xFFADB5BD to 0xFF343A40,
)

/** The open note, Keep-like: it opens for writing; Back, Esc or the arrow close it. */
@Composable
private fun SpikeEditor(note: SpikeNote, shape: RoundedCornerShape, onClose: () -> Unit, modifier: Modifier, words: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier.clip(shape).background(swatch(note.color) ?: MaterialTheme.colorScheme.surface)
            .onPreviewKeyEvent { event ->
                (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onClose() }
            }
            .focusRequester(focus).focusable(),
    ) { Column(words.verticalScroll(rememberScrollState())) { EditorWords(note, onClose) } }
}

@Composable
private fun EditorWords(note: SpikeNote, onClose: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    IconButton(onClose, Modifier.statusBarsPadding().padding(4.dp)) { Icon(Icons.Back, stringResource(Res.string.board_close)) }
    note.picture?.let { SpikePicture(it, note.id) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicTextField(
            note.title,
            { note.title = it },
            Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.titleLarge.copy(color = ink),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { field -> Hinted(note.title.isEmpty(), stringResource(Res.string.board_title_hint), field) },
        )
        BasicTextField(
            note.text,
            { note.text = it },
            Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { field -> Hinted(note.text.isEmpty(), stringResource(Res.string.board_text_hint), field) },
        )
        note.checks.forEach { check ->
            CheckLine(
                check,
                26.dp,
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(4.dp)).hand()
                    .clickable { check.checked = !check.checked },
            )
        }
    }
}

@Composable
private fun Hinted(empty: Boolean, hint: String, field: @Composable () -> Unit) {
    Box {
        if (empty) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        field()
    }
}

/** Frame times while something moves (spike instrument): a frame later than 1.5 medians counts as late. */
internal class FrameSummary(val frames: Int, val median: String, val late: Int, val worst: Int) {
    companion object {
        fun of(times: List<Long>): FrameSummary? {
            val gaps = times.zipWithNext { a, b -> (b - a) / 1e6f }
            if (gaps.size < 10) return null
            val median = gaps.sorted()[gaps.size / 2]
            return FrameSummary(
                gaps.size,
                ((median * 10).roundToInt() / 10f).toString(),
                gaps.count { it > median * 1.5f },
                gaps.max().roundToInt(),
            )
        }
    }
}

/** Made-up notes, the same every time: titles, lines of text, checklists, pictures and colors in Keep's mix. */
internal fun spikeNotes(count: Int): List<SpikeNote> {
    val random = Random(40)
    return List(count) { i ->
        val kind = random.nextInt(10)
        val checks = if (kind < 3) {
            List(3 + random.nextInt(9)) { SpikeCheck(ITEMS[random.nextInt(ITEMS.size)], random.nextInt(10) < 3) }
        } else {
            emptyList()
        }
        val text = if (kind in
            3..8
        ) {
            List(1 + random.nextInt(if (kind == 8) 14 else 4)) { LINES[random.nextInt(LINES.size)] }.joinToString("\n")
        } else {
            ""
        }
        SpikeNote(
            id = "n$i",
            title = if (random.nextInt(4) == 0) "" else TITLES[random.nextInt(TITLES.size)],
            text = text,
            checks = checks,
            picture = if (kind == 9 || random.nextInt(8) == 0) RATIOS[random.nextInt(RATIOS.size)] else null,
            color = if (random.nextInt(5) < 2) COLORS[random.nextInt(COLORS.size)] else null,
        )
    }
}

private val COLORS = listOf("coral", "peach", "sand", "mint", "sage", "fog", "storm", "dusk", "blossom", "clay")
private val RATIOS = listOf(4f / 3f, 1f, 3f / 4f, 16f / 9f, 2f / 3f)
private val TITLES = listOf(
    "Продукты", "Купить в хозяйственном", "Идеи подарков", "Книги", "Фильмы на выходные", "Рецепт сырников", "Размеры",
    "Дела на неделю", "Цитата", "К врачу", "Отпуск: что взять", "Мысли", "Код от домофона у Лены", "Посадить весной",
    "Спросить у мастера", "Подкасты", "Велосипед", "Английский: слова", "Вернуть долг", "Ремонт ванной",
)
private val LINES = listOf(
    "Позвонить до пятницы, иначе запись сгорит.",
    "Взять зарядку, наушники и запасные носки.",
    "Попробовать с ванилью и щепоткой соли — так вкуснее.",
    "«Делай, что должно, и будь что будет».",
    "Ширина ниши 62 см, глубина 58, розетка слева.",
    "Тот сорт томатов, что был у соседей: розовые, крупные.",
    "Спросить про гарантию и сроки доставки.",
    "Посмотреть отзывы, но не залипать больше 10 минут.",
    "В субботу утром рынок, потом в парк.",
    "Подшипник на заднем колесе шумит.",
    "Слова: reluctant, thorough, eventually, whereas.",
    "Не забыть полить фикус.",
    "Идея: сделать полку над столом из той доски.",
    "Поменять масло, проверить тормозные колодки.",
)
private val ITEMS = listOf(
    "молоко", "хлеб", "яйца", "сыр", "яблоки", "кофе", "гречка", "батарейки AA", "лампочка E27", "мешки для мусора",
    "зубная паста", "бананы", "помидоры", "овсянка", "масло сливочное", "корм коту", "губки", "салфетки",
)
