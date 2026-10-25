package app.benbow.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.benbow.client.BoardNote
import app.benbow.client.Media
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// The board and the note open on it as one scene (ADR-040). The board stays as it was underneath; the note opens as a
// container transform (owner, 0.15.0: the old sheet grew dark and empty and the note then appeared unscaled, its
// parts jumping): one rounded box in the note's color grows from the card into the note's place, and both faces
// scale with it, the card's fading out over the first third and the note's page, laid out once at its full size,
// fading in after. Closing plays it back into wherever the card is now; the note's entry is popped after it, so the
// editor lives (and commits) until it is out of sight.

/** Marks the board's entry and the notes opened on it (the value: the note's id, or "" for a new one). */
internal const val BOARD_ENTRY = "benbow.board"
internal const val BOARD_NOTE = "benbow.boardNote"

/** Picks the board's scene: the board alone, or with a note open on it; anything else is another scene's. */
internal class BoardSceneStrategy(private val back: () -> Unit) : SceneStrategy<Route> {
    override fun SceneStrategyScope<Route>.calculateScene(entries: List<NavEntry<Route>>): Scene<Route>? {
        val boardAt = entries.indexOfLast { it.metadata[BOARD_ENTRY] == true }
        if (boardAt < 0) return null
        val note = entries.getOrNull(boardAt + 1)?.takeIf { boardAt + 1 == entries.lastIndex && BOARD_NOTE in it.metadata }
        if (note == null && boardAt != entries.lastIndex) return null
        return BoardScene(entries[boardAt], note, entries.subList(0, if (note == null) boardAt else entries.lastIndex), back)
    }
}

/** Keyed by the board alone (like the list-detail scene), so opening and closing a note never rebuilds the board. */
private class BoardScene(
    private val board: NavEntry<Route>,
    private val note: NavEntry<Route>?,
    override val previousEntries: List<NavEntry<Route>>,
    private val back: () -> Unit,
) : Scene<Route> {
    override val key: Any = "board" to board.contentKey
    override val entries: List<NavEntry<Route>> = listOfNotNull(board, note)
    override val metadata: Map<String, Any> = (note ?: board).metadata
    override val content: @Composable () -> Unit = { BoardStageContent(board, note, back) }
}

/** What the board and the open note share: which note is out, its color, where it grows from, and how to close it. */
@Stable
internal class BoardStage {
    /** The open note's id; null for a new one until its first words make it. */
    var entryId by mutableStateOf<String?>(null)

    /** The open note's color (its card's, then the editor's as it changes). */
    var color by mutableStateOf<String?>(null)

    /** A new note from the + button: it comes out quicker, from the button. */
    var fresh by mutableStateOf(false)
        private set

    /** A note is out: opening, open or closing. */
    var open by mutableStateOf(false)
        internal set

    /** The card of [id] stands for the open note (it waits unseen in its place). */
    fun holds(id: String): Boolean = open && entryId == id

    /** Shrinks the note back into its card, then leaves it. */
    var close: () -> Unit = {}
        internal set

    /** Where the note grows from and shrinks into (window coordinates): its card, or the + button. */
    internal var from by mutableStateOf<Rect?>(null)

    /** 0: the card; 1: the open note. */
    internal val progress = Animatable(0f)

    /** Where each card is on screen now, and the + button (not state: read when a note opens or closes). */
    private val places = HashMap<String, Rect>()
    internal var button: Rect? = null

    /** The card of a note as the board shows it now, the face the note's box wears near the card; the board's media. */
    internal var face: (String) -> BoardNote? = { null }
    internal var media: Media? = null

    fun place(id: String, bounds: Rect) {
        places[id] = bounds
    }

    internal fun placeOf(id: String?): Rect? = id?.let(places::get)

    /** Before a card (or the + button, with null) opens its note. */
    fun opening(id: String?, color: String?) {
        entryId = id
        this.color = color
        fresh = id == null
        from = placeOf(id) ?: button
    }
}

internal val LocalBoardStage = compositionLocalOf<BoardStage?> { null }

@Composable
private fun BoardStageContent(board: NavEntry<Route>, note: NavEntry<Route>?, back: () -> Unit) {
    val stage = remember { BoardStage() }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // The note on the stage, kept through its closing until it is back in its card.
    var shown by remember { mutableStateOf<NavEntry<Route>?>(null) }
    var closing by remember { mutableStateOf(false) }
    // A new note closed empty fades where it is (owner: never back into the + button).
    val fade = remember { Animatable(1f) }
    LaunchedEffect(note) {
        if (note == null) {
            // Gone from elsewhere (the trash, the archive, the browser's Back): no card to shrink into.
            if (!closing) {
                shown = null
                stage.open = false
                stage.progress.snapTo(0f)
            }
            return@LaunchedEffect
        }
        // A note opened from elsewhere (an address, a restored screen) names itself; a new one has no id yet.
        (note.metadata[BOARD_NOTE] as? String)?.takeIf { it.isNotEmpty() && it != stage.entryId }?.let { stage.opening(it, null) }
        shown = note
        stage.open = true
        fade.snapTo(1f)
        stage.progress.animateTo(1f, tween(if (stage.fresh) QUICK_MILLIS else SHEET_MILLIS, easing = FastOutSlowInEasing))
    }
    stage.close = close@{
        if (closing || shown == null) return@close
        focus.clearFocus(force = true)
        closing = true
        scope.launch {
            val into = stage.placeOf(stage.entryId)
            if (into == null) {
                fade.animateTo(0f, tween(QUICK_MILLIS))
            } else {
                stage.from = into
                stage.progress.animateTo(0f, tween(SHEET_MILLIS, easing = FastOutSlowInEasing))
            }
            shown = null
            stage.open = false
            stage.progress.snapTo(0f)
            closing = false
            if (note != null) back()
        }
    }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = note != null && !closing) {
        stage.close()
    }
    CompositionLocalProvider(LocalBoardStage provides stage) {
        Box(Modifier.fillMaxSize()) {
            board.Content()
            shown?.let { NoteBox(stage, it, fade.value) }
        }
    }
}

/**
 * The open note's box at [BoardStage.progress]: from the card's place to the note's (the whole board on a phone, a
 * centered sheet over a scrim in a wide window), rounded like a card on its way, both faces scaled to its width.
 */
@Composable
private fun NoteBox(stage: BoardStage, entry: NavEntry<Route>, alpha: Float) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        val density = LocalDensity.current
        val wide = LocalWide.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val full = if (wide) {
            val w = minOf(with(density) { 720.dp.toPx() }, width - with(density) { 48.dp.toPx() })
            val h = height * 0.88f
            Rect(Offset((width - w) / 2, (height - h) / 2), androidx.compose.ui.geometry.Size(w, h))
        } else {
            Rect(0f, 0f, width, height)
        }
        // Without a place to grow from (opened from an address), it comes out of the middle a little smaller.
        val start = stage.from?.translate(-origin) ?: Rect(full.center, 0f).inflate(minOf(full.width, full.height) * 0.42f)
        val p = stage.progress.value
        val box = lerp(start, full, p)
        if (wide) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { this.alpha = p * alpha }.background(Color.Black.copy(alpha = 0.32f))
                    .clickable(MutableInteractionSource(), indication = null) { stage.close() },
            )
        }
        val corner = if (wide) 12.dp else lerp(12.dp, 0.dp, p)
        Box(
            Modifier.offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                .graphicsLayer {
                    this.alpha = alpha
                    shape = RoundedCornerShape(corner)
                    clip = true
                }
                .background(swatch(stage.color) ?: boardCardColor()),
        ) {
            val face = stage.entryId?.let(stage.face)
            val from = stage.from
            val faceAlpha = 1f - (p / FACE_SHARE).coerceIn(0f, 1f)
            if (face != null && from != null && faceAlpha > 0f && from.width > 0f) {
                Box(
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(with(density) { from.width.toDp() }, with(density) { from.height.toDp() })
                        .graphicsLayer {
                            val scale = box.width / from.width
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                            this.alpha = faceAlpha
                        },
                ) { BoardCard(face, stage.media, chosen = false) }
            }
            Box(
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(with(density) { full.width.toDp() }, with(density) { full.height.toDp() })
                    .graphicsLayer {
                        val scale = box.width / full.width
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                        this.alpha = ((p - PAGE_FROM) / (1f - PAGE_FROM)).coerceIn(0f, 1f)
                    },
            ) { entry.Content() }
        }
    }
}

internal val CARD_SHAPE = RoundedCornerShape(12.dp)

internal const val SHEET_MILLIS = 300

/** A new note from the + button (owner: the first take was too slow): quicker. */
internal const val QUICK_MILLIS = 200

/** The share of the way over which the card's face fades out, and from where the note's page fades in. */
private const val FACE_SHARE = 0.35f
private const val PAGE_FROM = 0.3f
