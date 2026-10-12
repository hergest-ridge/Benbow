package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.benbow.ui.theme.BenbowTheme

internal sealed interface Route {
    data object Journal : Route

    /** [entryId] = null opens a new entry; [opened] keeps two new-entry screens apart. */
    data class Editor(val entryId: String?, val day: String, val opened: Long = 0) : Route

    data class History(val entryId: String) : Route

    data class Revision(val entryId: String, val revisionId: String, val createdAt: Long) : Route

    data object Trash : Route

    data object Server : Route
}

internal class Navigator(private val stack: SnapshotStateList<Route>) {
    fun open(route: Route) {
        stack += route
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Pops until [predicate] matches the top (or only the root is left). */
    fun backTo(predicate: (Route) -> Boolean) {
        while (stack.size > 1 && !predicate(stack.last())) stack.removeAt(stack.lastIndex)
    }
}

/** Root of the Android and desktop apps. */
@Composable
fun BenbowApp(services: AppServices) {
    BenbowTheme {
        LifecycleEventEffect(Lifecycle.Event.ON_START) { services.sync.foreground = true }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { services.sync.foreground = false }
        val backStack = remember { mutableStateListOf<Route>(Route.Journal) }
        val nav = remember(backStack) { Navigator(backStack) }
        NavDisplay(
            backStack = backStack,
            onBack = nav::back,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<Route.Journal> { JournalScreen(services, nav) }
                entry<Route.Editor> { EditorScreen(services, nav, it) }
                entry<Route.History> { HistoryScreen(services, nav, it) }
                entry<Route.Revision> { RevisionScreen(services, nav, it) }
                entry<Route.Trash> { TrashScreen(services, nav) }
                entry<Route.Server> { ServerScreen(services, nav) }
            },
        )
    }
}
