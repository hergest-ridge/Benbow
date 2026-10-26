package app.benbow.app

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import app.benbow.app.resources.Res
import app.benbow.app.resources.detail_empty
import app.benbow.app.resources.new_entry
import app.benbow.client.Settings
import org.jetbrains.compose.resources.stringResource

// Wide windows (ux.md#desktop-and-web): the tab's screen stays on the left while what it opened shows on the right,
// like a mail client or Telegram on a computer. Opening another item from the list replaces the right side; Back closes
// it. The media viewer and the drawing canvas still take the whole window.

/** From here (rail included) the list and what it opened sit side by side. */
internal val TWO_PANE = 1040.dp

/** The list's width beside an entry: a phone's column, so every tab screen fits as designed; the divider moves it. */
private val LIST_WIDTH = 400.dp
private val LIST_MIN = 300.dp
private val LIST_MAX = 640.dp

/** What stays for the entry at the least when the list is dragged wider. */
private val DETAIL_MIN = 480.dp

/** The list's width on this device: dragged by the divider, kept in the device's settings. */
internal class ListPane(private val settings: Settings) {
    var width by mutableStateOf(settings.get(KEY)?.toFloatOrNull()?.dp?.coerceIn(LIST_MIN, LIST_MAX) ?: LIST_WIDTH)

    fun save() = settings.put(KEY, width.value.toString())

    fun reset() {
        width = LIST_WIDTH
        save()
    }

    private companion object {
        const val KEY = "ui.listWidth"
    }
}

/** The pointer over the divider: a left-right arrow where the platform has one. */
internal expect val ResizeColumnIcon: PointerIcon

/** Screens that cover the whole window even when the list could stay. */
internal val FULL_WINDOW = mapOf("benbow.full" to true)

/** The entry open beside the list, so the list can mark it. */
internal val LocalOpenEntry = compositionLocalOf<String?> { null }

internal class ListDetailStrategy(private val pane: ListPane, private val empty: @Composable () -> Unit) : SceneStrategy<Route> {
    override fun SceneStrategyScope<Route>.calculateScene(entries: List<NavEntry<Route>>): Scene<Route>? {
        val top = entries.last()
        if (top.metadata["benbow.full"] == true) return null
        val listAt = entries.indexOfLast { it.metadata["benbow.tab"] == true }
        if (listAt < 0 || entries[listAt].metadata["benbow.full"] == true) return null // the board is no list
        val detail = top.takeIf { listAt < entries.lastIndex }
        return ListDetailScene(entries[listAt], detail, entries.subList(0, if (detail == null) listAt else entries.lastIndex), pane, empty)
    }
}

/**
 * Keyed by the list alone, so opening, switching or closing the item on the right never rebuilds the list (its scroll
 * and state stay) and swaps the right side at once.
 */
private class ListDetailScene(
    private val list: NavEntry<Route>,
    private val detail: NavEntry<Route>?,
    override val previousEntries: List<NavEntry<Route>>,
    private val pane: ListPane,
    private val empty: @Composable () -> Unit,
) : Scene<Route> {
    override val key: Any = "list-detail" to list.contentKey
    override val entries: List<NavEntry<Route>> = listOfNotNull(list, detail)
    override val metadata: Map<String, Any> = (detail ?: list).metadata

    override val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widest = (maxWidth - DETAIL_MIN).coerceIn(LIST_MIN, LIST_MAX)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(pane.width.coerceIn(LIST_MIN, widest)).fillMaxHeight()) { list.Content() }
                PaneDivider(pane, widest)
                Detail()
            }
        }
    }

    /**
     * The entry on the right, at a readable width: the open note's light over the whole side (PaneGlow) and a line down
     * each side of it, like the list's edge (owner), each as set in «Оформление».
     */
    @Composable
    private fun RowScope.Detail() {
        val appearance = LocalAppearance.current
        val glow = remember { PaneGlow() }
        val ink = MaterialTheme.colorScheme.onSurface
        val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight()
                .onGloballyPositioned { glow.bounds = it.boundsInWindow() }
                .drawBehind { if (appearance.lightBeyondPage) drawLights(glow.lights, glow.dark, ink, 1f) },
            contentAlignment = Alignment.TopCenter,
        ) {
            val framed = appearance.pageBorders && detail != null && maxWidth > READABLE
            Box(
                Modifier.widthIn(max = READABLE).fillMaxHeight().drawWithContent {
                    drawContent()
                    if (framed) {
                        val px = 1.dp.toPx()
                        drawLine(line, Offset(px / 2, 0f), Offset(px / 2, size.height), px)
                        drawLine(line, Offset(size.width - px / 2, 0f), Offset(size.width - px / 2, size.height), px)
                    }
                },
            ) {
                CompositionLocalProvider(LocalPaneGlow provides glow.takeIf { appearance.lightBeyondPage }) {
                    if (detail == null) empty() else detail.Content()
                }
            }
        }
    }

    override fun equals(other: Any?) = other is ListDetailScene && other.list == list && other.detail == detail

    override fun hashCode() = 31 * list.hashCode() + detail.hashCode()
}

/**
 * The line between the list and the entry: dragged left or right within reason (the list from 300 to 640 dp, the entry
 * keeps at least 480), a double click puts it back. A strip a little wider than the line takes the pointer.
 */
@Composable
private fun PaneDivider(pane: ListPane, widest: Dp) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val drag = rememberDraggableState { delta ->
        pane.width = (pane.width.coerceAtMost(widest) + with(density) { delta.toDp() }).coerceIn(LIST_MIN, widest)
    }
    Box(
        Modifier.width(9.dp).fillMaxHeight()
            .hoverable(interaction)
            .pointerHoverIcon(ResizeColumnIcon)
            // The double click outside the drag: a tap detector inside it would take the press the drag starts from.
            .pointerInput(pane) { detectTapGestures(onDoubleTap = { pane.reset() }) }
            .draggable(drag, Orientation.Horizontal, interactionSource = interaction, onDragStopped = { pane.save() }),
        contentAlignment = Alignment.Center,
    ) {
        val colors = MaterialTheme.colorScheme
        VerticalDivider(
            thickness = if (hovered || dragged) 3.dp else 1.dp,
            color = if (hovered || dragged) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

/** The right side before anything is open: what it's for, and a way to start writing. */
@Composable
internal fun DetailPlaceholder(onNewEntry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Wordmark()
            Text(
                stringResource(Res.string.detail_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            FilledTonalButton(onNewEntry, modifier = Modifier.hand()) {
                Icon(Icons.Edit, null)
                Text(stringResource(Res.string.new_entry), Modifier.padding(start = 8.dp))
            }
        }
    }
}
