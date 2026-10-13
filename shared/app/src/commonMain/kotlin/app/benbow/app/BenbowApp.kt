package app.benbow.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.benbow.app.resources.Res
import app.benbow.app.resources.attach_failed
import app.benbow.ui.theme.BenbowTheme
import org.jetbrains.compose.resources.getString

internal sealed interface Route {
    data object Journal : Route

    /** [entryId] = null opens a new entry; [opened] keeps two new-entry screens apart. */
    data class Editor(val entryId: String?, val day: String, val opened: Long = 0) : Route

    data class History(val entryId: String) : Route

    data class Revision(val entryId: String, val revisionId: String, val createdAt: Long) : Route

    data class Media(val entryId: String, val attachmentId: String) : Route

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
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            services.messages.collect { message ->
                when (message) {
                    is AppMessage.AttachFailed -> snackbar.showSnackbar(getString(Res.string.attach_failed, message.reason))
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            AppNavigation(services, backStack, nav)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
        }
    }
}

@Composable
private fun AppNavigation(services: AppServices, backStack: SnapshotStateList<Route>, nav: Navigator) {
    NavDisplay(
        backStack = backStack,
        onBack = nav::back,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<Route.Journal> { JournalScreen(services, nav) }
            entry<Route.Editor> { EditorScreen(services, nav, it) }
            entry<Route.History> { HistoryScreen(services, nav, it) }
            entry<Route.Revision> { RevisionScreen(services, nav, it) }
            entry<Route.Media> { MediaScreen(services, nav, it) }
            entry<Route.Trash> { TrashScreen(services, nav) }
            entry<Route.Server> { ServerScreen(services, nav) }
        },
    )
}
