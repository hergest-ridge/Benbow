package app.benbow.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.benbow.app.resources.Res
import app.benbow.app.resources.attach_failed
import app.benbow.app.resources.copied
import app.benbow.app.resources.tab_calendar
import app.benbow.app.resources.tab_feed
import app.benbow.app.resources.tab_home
import app.benbow.app.resources.tab_more
import app.benbow.app.resources.tab_search
import app.benbow.client.TagLooks
import app.benbow.ui.theme.BenbowTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

internal sealed interface Route {
    data object Home : Route

    data object Feed : Route

    /**
     * [entryId] = null opens a new entry ([template] fills it); [opened] keeps two new-entry screens apart.
     * [readOnly] opens it as a post with its comments (from the feed); the menu switches to editing.
     */
    data class Editor(
        val entryId: String?,
        val day: String,
        val opened: Long = 0,
        val template: String? = null,
        val readOnly: Boolean = false,
    ) : Route

    data class History(val entryId: String) : Route

    data class Revision(val entryId: String, val revisionId: String, val createdAt: Long) : Route

    data class Media(val entryId: String, val attachmentId: String) : Route

    data object Search : Route

    data object Calendar : Route

    data class Comments(val entryId: String) : Route

    data object Tags : Route

    /** [day] preselects the date (from the calendar). */
    data class Reminders(val day: String? = null) : Route

    data object Trash : Route

    data object Server : Route

    /** The last tab: everything that doesn't need a tab of its own. */
    data object More : Route
}

internal class Navigator(private val stack: SnapshotStateList<Route>) {
    fun open(route: Route) {
        stack += route
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Top-level tabs keep Home underneath, so Back from Feed or Search lands on Home. */
    fun selectTab(tab: Route) {
        stack.clear()
        stack += Route.Home
        if (tab != Route.Home) stack += tab
    }

    /** Pops until [predicate] matches the top (or only the root is left). */
    fun backTo(predicate: (Route) -> Boolean) {
        while (stack.size > 1 && !predicate(stack.last())) stack.removeAt(stack.lastIndex)
    }
}

private class Tab(val route: Route, val label: StringResource, val icon: ImageVector)

private val TABS = listOf(
    Tab(Route.Home, Res.string.tab_home, Icons.Home),
    Tab(Route.Feed, Res.string.tab_feed, Icons.Feed),
    Tab(Route.Calendar, Res.string.tab_calendar, Icons.Calendar),
    Tab(Route.Search, Res.string.tab_search, Icons.Search),
    Tab(Route.More, Res.string.tab_more, Icons.MoreHoriz),
)

/** Tabs switch in place; screens on top fade in over the one below and fade away on Back (ux.md#motion). */
private val TAB = mapOf("benbow.tab" to true)

private fun Scene<*>.isTab() = metadata["benbow.tab"] == true

private const val FADE_MILLIS = 220

private fun AnimatedContentTransitionScope<Scene<Route>>.betweenTabs() = initialState.isTab() && targetState.isTab()

private val still = EnterTransition.None togetherWith ExitTransition.None

/** Root of the Android and desktop apps. */
@Composable
fun BenbowApp(services: AppServices) {
    val looks by remember { services.repo.tagLooks() }.collectAsStateWithLifecycle(TagLooks())
    BenbowTheme {
        CompositionLocalProvider(LocalTagLooks provides looks) { AppRoot(services) }
    }
}

@Composable
private fun AppRoot(services: AppServices) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) { services.sync.foreground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { services.sync.foreground = false }
    val backStack = remember { mutableStateListOf<Route>(Route.Home) }
    val nav = remember(backStack) { Navigator(backStack) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        services.messages.collect { message ->
            when (message) {
                is AppMessage.AttachFailed -> snackbar.showSnackbar(getString(Res.string.attach_failed, message.reason))
                AppMessage.Copied -> snackbar.showSnackbar(getString(Res.string.copied))
            }
        }
    }
    // The tab bar stays put on the top-level screens; the keyboard slides over it and off it again (no blink).
    // Screens above see the keyboard's inset minus the bar, so a composer still sits right on the keyboard.
    val top = backStack.lastOrNull()
    val tabs = top in TABS.map { it.route }
    var barHeight by remember { mutableIntStateOf(0) }
    val bar = with(LocalDensity.current) { barHeight.toDp() }
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).then(
                if (tabs) {
                    Modifier.consumeWindowInsets(WindowInsets.navigationBars).consumeWindowInsets(PaddingValues(bottom = bar))
                } else {
                    Modifier
                },
            ),
        ) {
            AppNavigation(services, backStack, nav)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
        }
        if (tabs) {
            ShortNavigationBar(Modifier.onSizeChanged { barHeight = it.height }) {
                TABS.forEach { tab ->
                    val label = stringResource(tab.label)
                    ShortNavigationBarItem(
                        selected = top == tab.route,
                        onClick = { nav.selectTab(tab.route) },
                        icon = { Icon(tab.icon, label) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavigation(services: AppServices, backStack: SnapshotStateList<Route>, nav: Navigator) {
    NavDisplay(
        backStack = backStack,
        onBack = nav::back,
        transitionSpec = {
            if (betweenTabs()) still else fadeIn(tween(FADE_MILLIS)) togetherWith ExitTransition.KeepUntilTransitionsFinished
        },
        popTransitionSpec = { if (betweenTabs()) still else EnterTransition.None togetherWith fadeOut(tween(FADE_MILLIS)) },
        predictivePopTransitionSpec = { if (betweenTabs()) still else EnterTransition.None togetherWith fadeOut() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<Route.Home>(metadata = TAB) { HomeScreen(services, nav) }
            entry<Route.Feed>(metadata = TAB) { FeedScreen(services, nav) }
            entry<Route.Editor> { EditorScreen(services, nav, it) }
            entry<Route.History> { HistoryScreen(services, nav, it) }
            entry<Route.Revision> { RevisionScreen(services, nav, it) }
            entry<Route.Media> { MediaScreen(services, nav, it) }
            entry<Route.Search>(metadata = TAB) { SearchScreen(services, nav) }
            entry<Route.Calendar>(metadata = TAB) { CalendarScreen(services, nav) }
            entry<Route.More>(metadata = TAB) { MoreScreen(services, nav) }
            entry<Route.Comments> { CommentsScreen(services, nav, it) }
            entry<Route.Tags> { TagsScreen(services, nav) }
            entry<Route.Reminders> { RemindersScreen(services, nav, it) }
            entry<Route.Trash> { TrashScreen(services, nav) }
            entry<Route.Server> { ServerScreen(services, nav) }
        },
    )
}
