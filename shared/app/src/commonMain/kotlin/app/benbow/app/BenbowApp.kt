package app.benbow.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import app.benbow.app.resources.saved
import app.benbow.app.resources.speech_failed
import app.benbow.app.resources.tab_calendar
import app.benbow.app.resources.tab_feed
import app.benbow.app.resources.tab_home
import app.benbow.app.resources.tab_more
import app.benbow.app.resources.tab_search
import app.benbow.client.TagLooks
import app.benbow.client.Templates
import kotlinx.coroutines.flow.filterNotNull
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

    /** A drawing to make ([attachmentId] null) or to change. */
    data class Drawing(val entryId: String, val attachmentId: String?) : Route

    data object Search : Route

    data object Calendar : Route

    data class Comments(val entryId: String) : Route

    data object Tags : Route

    /** [day] preselects the date (from the calendar). */
    data class Reminders(val day: String? = null) : Route

    data object Trash : Route

    data object Server : Route

    data object Export : Route

    data object Lock : Route

    data object Speech : Route

    data object Appearance : Route

    data object Shortcuts : Route

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
    AppTheme(services) {
        CompositionLocalProvider(LocalTagLooks provides looks, LocalAudio provides rememberAudioPlayback()) { AppRoot(services) }
    }
}

@Composable
private fun AppRoot(services: AppServices) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        services.sync.foreground = true
        services.lock.onReturn()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        services.sync.foreground = false
        services.lock.onLeave()
    }
    val backStack = remember { mutableStateListOf<Route>(Route.Home) }
    val nav = remember(backStack) { Navigator(backStack) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        services.launch.filterNotNull().collect { request ->
            services.launch.value = null
            when (request) {
                AppLaunch.NewEntry, AppLaunch.AnswerPrompt -> {
                    nav.selectTab(Route.Home)
                    val template = Templates.PROMPT.id.takeIf { request == AppLaunch.AnswerPrompt }
                    nav.open(Route.Editor(null, services.today(), opened = services.now(), template = template))
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        services.shortcuts.collect { shortcut ->
            if (services.lock.locked.value) return@collect
            when (shortcut) {
                Shortcut.NewEntry -> nav.open(Route.Editor(null, services.today(), opened = services.now()))
                Shortcut.Search -> nav.selectTab(Route.Search)
                Shortcut.Back -> nav.back()
                is Shortcut.Tab -> TABS.getOrNull(shortcut.index)?.let { nav.selectTab(it.route) }
                Shortcut.Save -> Unit // the open editor takes it
            }
        }
    }
    LaunchedEffect(Unit) {
        services.messages.collect { message ->
            when (message) {
                is AppMessage.AttachFailed -> snackbar.showSnackbar(getString(Res.string.attach_failed, message.reason))
                is AppMessage.TranscriptionFailed -> snackbar.showSnackbar(getString(Res.string.speech_failed, message.reason))
                AppMessage.Copied -> snackbar.showSnackbar(getString(Res.string.copied))
                AppMessage.Saved -> snackbar.showSnackbar(getString(Res.string.saved))
            }
        }
    }
    // The lock covers everything, hides it from accessibility and takes focus away, so a keyboard can't type behind it.
    val locked by services.lock.locked.collectAsStateWithLifecycle()
    val auth = rememberDeviceAuth()
    val focus = LocalFocusManager.current
    LaunchedEffect(locked) { if (locked) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize()) {
        AppFrame(services, backStack, nav, snackbar, if (locked) Modifier.clearAndSetSemantics {} else Modifier)
        if (locked) LockScreen(auth) { services.lock.unlock() }
    }
}

@Composable
private fun AppFrame(
    services: AppServices,
    backStack: SnapshotStateList<Route>,
    nav: Navigator,
    snackbar: SnackbarHostState,
    modifier: Modifier,
) {
    // Phones: the tab bar stays put at the bottom; the keyboard slides over it and off it again (no blink), and
    // screens above see the keyboard's inset minus the bar, so a composer still sits right on the keyboard.
    // Wide windows (desktop, tablets): a rail on the left and the screens centred at a readable width. The tree keeps
    // the same shape in both, so resizing across the line never rebuilds the screens.
    val top = backStack.lastOrNull()
    val tabs = top in TABS.map { it.route }
    var barHeight by remember { mutableIntStateOf(0) }
    val bar = with(LocalDensity.current) { barHeight.toDp() }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= WIDE
        Row(Modifier.fillMaxSize()) {
            if (wide && tabs) {
                NavigationRail {
                    Spacer(Modifier.height(8.dp))
                    TABS.forEach { tab ->
                        val label = stringResource(tab.label)
                        NavigationRailItem(
                            selected = top == tab.route,
                            onClick = { nav.selectTab(tab.route) },
                            icon = { Icon(tab.icon, label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val bottomBar = tabs && !wide
                Box(
                    Modifier.weight(1f).fillMaxWidth().then(
                        if (bottomBar) {
                            Modifier.consumeWindowInsets(WindowInsets.navigationBars).consumeWindowInsets(PaddingValues(bottom = bar))
                        } else {
                            Modifier
                        },
                    ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.fillMaxHeight().widthIn(max = if (wide) READABLE else Dp.Infinity)) {
                        AppNavigation(services, backStack, nav)
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
                }
                if (bottomBar) {
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
    }
}

/** From here a window gets the side rail (Material's "expanded" width). */
private val WIDE = 840.dp

/** How wide a screen grows in a big window: lines of text stay readable. */
private val READABLE = 820.dp

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
            entry<Route.Drawing> { DrawingScreen(services, nav, it) }
            entry<Route.Search>(metadata = TAB) { SearchScreen(services, nav) }
            entry<Route.Calendar>(metadata = TAB) { CalendarScreen(services, nav) }
            entry<Route.More>(metadata = TAB) { MoreScreen(services, nav) }
            entry<Route.Comments> { CommentsScreen(services, nav, it) }
            entry<Route.Tags> { TagsScreen(services, nav) }
            entry<Route.Reminders> { RemindersScreen(services, nav, it) }
            entry<Route.Trash> { TrashScreen(services, nav) }
            entry<Route.Server> { ServerScreen(services, nav) }
            entry<Route.Export> { ExportScreen(services, nav) }
            entry<Route.Lock> { LockSettingsScreen(services, nav) }
            entry<Route.Speech> { SpeechScreen(services, nav) }
            entry<Route.Appearance> { AppearanceScreen(services, nav) }
            entry<Route.Shortcuts> { ShortcutsScreen(nav) }
        },
    )
}
