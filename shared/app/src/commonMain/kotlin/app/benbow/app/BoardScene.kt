package app.benbow.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

// The board and the note open on it as one scene (ADR-040). The board stays as it was underneath; the note's sheet
// grows from its card (a new note's from the quick bar) into the editor and shrinks back into its place, opaque all
// the way, only the words fading (owner: no fades of the whole, as in Keep). Closing plays the shrink first and pops
// the note's entry after it, so the editor lives (and commits) until it is out of sight.

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

/** What the board and the open note share: which note's sheet is out, its color, and how to close it. */
@Stable
internal class BoardStage {
    /** The open note's id; null for a new one until its first words make it. */
    var entryId by mutableStateOf<String?>(null)

    /** The open note's color (its card's, then the editor's as it changes). */
    var color by mutableStateOf<String?>(null)

    /**
     * The open note's sheet: its card's from the start, the + button's for a new note until it closes into its new
     * card. It never changes while the note is out, so nothing plays twice when a new note gets its id.
     */
    var sheet by mutableStateOf(sheetKey(null))
        internal set

    /** A new note from the + button: it comes out quicker. */
    val fresh: Boolean get() = sheet == sheetKey(null)

    /** A new note closed empty: its sheet fades where it is (the + button stays as it is, as Keep's). */
    val away: Boolean get() = sheet == SHEET_AWAY

    /** A note is out (or coming out): its card or the + button give their sheet to it. */
    val open: Boolean get() = visibility?.targetState == true

    /** The card of [id] stands for the open note (it waits blank in its place). */
    fun holds(id: String): Boolean = open && entryId == id

    internal var visibility: MutableTransitionState<Boolean>? = null

    /** Shrinks the note back into its place, then leaves it. */
    var close: () -> Unit = {}
        internal set

    /** Before a card (or the + button, with null) opens its note. */
    fun opening(id: String?, color: String?) {
        entryId = id
        this.color = color
        sheet = sheetKey(id)
    }
}

internal fun sheetKey(id: String?) = "sheet-" + (id ?: "new")

/** No card and no button: the sheet of a new note closed empty. */
private const val SHEET_AWAY = "sheet-away"

internal val LocalBoardStage = compositionLocalOf<BoardStage?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalBoardShared = compositionLocalOf<SharedTransitionScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun BoardStageContent(board: NavEntry<Route>, note: NavEntry<Route>?, back: () -> Unit) {
    val stage = remember { BoardStage() }
    val focus = LocalFocusManager.current
    // A note opened from elsewhere (an address, a restored screen) names itself; a new one has no id yet.
    LaunchedEffect(note) {
        (note?.metadata?.get(BOARD_NOTE) as? String)?.takeIf { it.isNotEmpty() && it != stage.entryId }?.let { stage.opening(it, null) }
    }
    var closing by remember { mutableStateOf(false) }
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = note != null && !closing
    stage.visibility = visible
    stage.close = {
        focus.clearFocus(force = true)
        // A new note that got its words shrinks into its new card; an empty one fades (owner: never back into the + button,
        // which stretched over the screen).
        stage.sheet = stage.entryId?.let(::sheetKey) ?: SHEET_AWAY
        closing = true
    }
    // Once the sheet is back in its place the note's entry goes; one popped from elsewhere just goes.
    LaunchedEffect(visible.isIdle, visible.currentState, closing) {
        if (closing && visible.isIdle && !visible.currentState) {
            closing = false
            if (note != null) back()
        }
    }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = note != null && !closing) {
        stage.close()
    }
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBoardStage provides stage, LocalBoardShared provides this) {
            board.Content()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = LocalWide.current
                val tallest = maxHeight * 0.88f
                val fresh = stage.fresh
                val exit = if (stage.away) AWAY_OUT else HOLD_OUT
                AnimatedVisibility(visible, enter = if (fresh) QUICK_HOLD_IN else HOLD_IN, exit = exit) {
                    // During a pop from elsewhere (the browser's Back) the entry is already gone: nothing to show.
                    val entry = note ?: return@AnimatedVisibility
                    // Opening, the note's sheet lies above its card's; closing, below, so the card's words come back.
                    val opening = transition.targetState == EnterExitState.Visible
                    val shape = if (wide) CARD_SHAPE else RoundedCornerShape(0.dp)
                    // The card's own color all the way (owner: no lines moving with it).
                    val sheetColor = swatch(stage.color) ?: boardCardColor()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (wide) {
                            Box(
                                Modifier.fillMaxSize()
                                    .animateEnterExit(enter = fadeIn(tween(SHEET_MILLIS)), exit = fadeOut(tween(SHEET_MILLIS)))
                                    .background(Color.Black.copy(alpha = 0.32f))
                                    .clickable(MutableInteractionSource(), indication = null) { stage.close() },
                            )
                        }
                        val frame = if (wide) {
                            Modifier.padding(
                                24.dp,
                            ).widthIn(max = 720.dp).fillMaxWidth().height(tallest)
                        } else {
                            Modifier.fillMaxSize()
                        }
                        Box(frame) {
                            Box(
                                Modifier.matchParentSize()
                                    .sharedBounds(
                                        rememberSharedContentState(stage.sheet),
                                        this@AnimatedVisibility,
                                        enter = if (fresh) QUICK_HOLD_IN else HOLD_IN,
                                        exit = exit,
                                        boundsTransform = if (fresh) QUICK_SHEET else SHEET,
                                        zIndexInOverlay = if (opening) 1f else 0f,
                                        clipInOverlayDuringTransition = OverlayClip(CARD_SHAPE),
                                    )
                                    .background(sheetColor, shape),
                            )
                            Box(
                                Modifier.matchParentSize().animateEnterExit(
                                    enter = if (fresh) QUICK_EDITOR_IN else EDITOR_IN,
                                    exit = EDITOR_OUT,
                                ),
                            ) { entry.Content() }
                        }
                    }
                }
            }
        }
    }
}

internal val CARD_SHAPE = RoundedCornerShape(12.dp)

internal const val SHEET_MILLIS = 300

@OptIn(ExperimentalSharedTransitionApi::class)
internal val SHEET = BoundsTransform { _, _ -> tween(SHEET_MILLIS, easing = FastOutSlowInEasing) }

/** Keeps a side of the move on screen for the sheet's whole way without changing how it looks. */
internal val HOLD_IN = fadeIn(tween(SHEET_MILLIS), initialAlpha = 1f)
internal val HOLD_OUT = fadeOut(tween(SHEET_MILLIS), targetAlpha = 1f)

/** A card's words: gone at once as it opens, back as its sheet settles. */
internal val WORDS_IN = fadeIn(tween(180, delayMillis = 120))
internal val WORDS_OUT = fadeOut(tween(60))

/** The editor: in as the sheet nearly fills its place, out before it shrinks. */
private val EDITOR_IN = fadeIn(tween(160, delayMillis = 180))
private val EDITOR_OUT = fadeOut(tween(90))

/** A new note from the + button (owner: the first take was too slow): the same, quicker. */
internal const val QUICK_MILLIS = 180

@OptIn(ExperimentalSharedTransitionApi::class)
internal val QUICK_SHEET = BoundsTransform { _, _ -> tween(QUICK_MILLIS, easing = FastOutSlowInEasing) }
internal val QUICK_HOLD_IN = fadeIn(tween(QUICK_MILLIS), initialAlpha = 1f)
internal val QUICK_HOLD_OUT = fadeOut(tween(QUICK_MILLIS), targetAlpha = 1f)
private val QUICK_EDITOR_IN = fadeIn(tween(110, delayMillis = 80))
private val AWAY_OUT = fadeOut(tween(QUICK_MILLIS))
