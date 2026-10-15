package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import app.benbow.app.resources.Res
import app.benbow.app.resources.detail_empty
import app.benbow.app.resources.new_entry
import org.jetbrains.compose.resources.stringResource

// Wide windows (ux.md#desktop-and-web): the tab's screen stays on the left while what it opened shows on the right,
// like a mail client or Telegram on a computer. Opening another item from the list replaces the right side; Back closes
// it. The media viewer and the drawing canvas still take the whole window.

/** From here (rail included) the list and what it opened sit side by side. */
internal val TWO_PANE = 1040.dp

/** The list's width beside an entry: a phone's column, so every tab screen fits as designed. */
private val LIST_WIDTH = 400.dp

/** Screens that cover the whole window even when the list could stay. */
internal val FULL_WINDOW = mapOf("benbow.full" to true)

/** The entry open beside the list, so the list can mark it. */
internal val LocalOpenEntry = compositionLocalOf<String?> { null }

internal class ListDetailStrategy(private val empty: @Composable () -> Unit) : SceneStrategy<Route> {
    override fun SceneStrategyScope<Route>.calculateScene(entries: List<NavEntry<Route>>): Scene<Route>? {
        val top = entries.last()
        if (top.metadata["benbow.full"] == true) return null
        val listAt = entries.indexOfLast { it.metadata["benbow.tab"] == true }
        if (listAt < 0) return null
        val detail = top.takeIf { listAt < entries.lastIndex }
        return ListDetailScene(entries[listAt], detail, entries.subList(0, if (detail == null) listAt else entries.lastIndex), empty)
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
    private val empty: @Composable () -> Unit,
) : Scene<Route> {
    override val key: Any = "list-detail" to list.contentKey
    override val entries: List<NavEntry<Route>> = listOfNotNull(list, detail)
    override val metadata: Map<String, Any> = (detail ?: list).metadata

    override val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(LIST_WIDTH).fillMaxHeight()) { list.Content() }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = READABLE).fillMaxHeight()) {
                    if (detail == null) empty() else detail.Content()
                }
            }
        }
    }

    override fun equals(other: Any?) = other is ListDetailScene && other.list == list && other.detail == detail

    override fun hashCode() = 31 * list.hashCode() + detail.hashCode()
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
            FilledTonalButton(onNewEntry) {
                Icon(Icons.Edit, null)
                Text(stringResource(Res.string.new_entry), Modifier.padding(start = 8.dp))
            }
        }
    }
}
