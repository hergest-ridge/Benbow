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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import app.benbow.app.resources.Res
import app.benbow.app.resources.attach_failed
import app.benbow.app.resources.copied
import app.benbow.app.resources.gentle_turned_off
import app.benbow.app.resources.gentle_turned_on
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.postponed
import app.benbow.app.resources.reminder_now
import app.benbow.app.resources.save_failed
import app.benbow.app.resources.saved
import app.benbow.app.resources.speech_failed
import app.benbow.app.resources.tab_calendar
import app.benbow.app.resources.tab_feed
import app.benbow.app.resources.tab_home
import app.benbow.app.resources.tab_more
import app.benbow.app.resources.tab_search
import app.benbow.app.resources.veil_undo
import app.benbow.app.resources.veiled_curtain
import app.benbow.app.resources.veiled_none
import app.benbow.app.resources.veiled_quiet
import app.benbow.client.TagRegistry
import app.benbow.client.Templates
import app.benbow.client.Veil
import app.benbow.client.tagRegistry
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import org.jetbrains.compose.resources.stringResource

internal sealed interface Route {
    data object Home : Route

    data object Feed : Route

    /**
     * [entryId] = null opens a new entry ([template] fills it, or [heading] starts it, e.g. an event's title);
     * [opened] keeps two new-entry screens apart. [readOnly] opens it as a post with its comments (from the feed);
     * the menu switches to editing.
     */
    data class Editor(
        val entryId: String?,
        val day: String,
        val opened: Long = 0,
        val template: String? = null,
        val readOnly: Boolean = false,
        val heading: String? = null,
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

    /** A calendar file's preview and import (7.3d); the file is in [AppServices.calendarImports]. */
    data object CalendarImport : Route

    data object Server : Route

    data object Export : Route

    data object Lock : Route

    /** "Ещё → Бережно" (ADR-031). */
    data object Gentle : Route

    /** A Бережно rule's entries, or the ones marked by hand ([ruleId] = null), everything open. */
    data class GentleEntries(val ruleId: String?) : Route

    data object Speech : Route

    data object Appearance : Route

    data object Shortcuts : Route

    /** The last tab: everything that doesn't need a tab of its own. */
    data object More : Route
}

/**
 * The app's back stack. The list screens' navigator ([forList]) replaces what is open beside the list on a wide window
 * (picking another entry), instead of stacking it.
 */
internal class Navigator(private val stack: SnapshotStateList<Route>, private val replacesDetail: () -> Boolean = { false }) {
    fun open(route: Route) {
        if (replacesDetail()) while (stack.size > 1 && stack.last() !in TAB_ROUTES) stack.removeAt(stack.lastIndex)
        stack += route
    }

    fun forList(twoPane: () -> Boolean) = Navigator(stack, twoPane)

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

private val TAB_ROUTES = setOf(Route.Home, Route.Feed, Route.Calendar, Route.Search, Route.More)

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

/** Root of the Android, desktop and web apps. */
@Composable
fun BenbowApp(services: AppServices) {
    val tags by remember { services.repo.tagRegistry() }.collectAsStateWithLifecycle(TagRegistry())
    val gentle by services.gentle.on.collectAsStateWithLifecycle()
    AppTheme(services) {
        CompositionLocalProvider(
            LocalTags provides tags,
            LocalGentle provides gentle,
            LocalAudio provides rememberAudioPlayback(),
            LocalPagerArrows provides (services.platform != "android"), // a mouse: side buttons on pictures
        ) { AppRoot(services) }
    }
}

@Composable
private fun AppRoot(services: AppServices) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        services.sync.foreground = true
        services.lock.onReturn()
        services.gentle.onReturn()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        services.sync.foreground = false
        services.lock.onLeave()
        services.gentle.onLeave()
    }
    val backStack = remember { mutableStateListOf<Route>(Route.Home) }
    val nav = remember(backStack) { Navigator(backStack) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    PlatformAddress(backStack, nav) { link ->
        when (link) {
            is Link.Tab -> nav.selectTab(link.route)

            is Link.Page -> {
                nav.selectTab(Route.More)
                nav.open(link.route)
            }

            Link.NewEntry -> nav.open(Route.Editor(null, services.today(), opened = services.now()))

            is Link.Entry -> scope.launch {
                val entry = services.repo.entry(link.id).first() ?: return@launch
                nav.selectTab(Route.Feed)
                nav.open(Route.Editor(entry.id, entry.day, readOnly = true))
                if (link.comments) nav.open(Route.Comments(entry.id))
            }
        }
    }
    LaunchedEffect(Unit) {
        services.launch.filterNotNull().collect { request ->
            services.launch.value = null
            when (request) {
                AppLaunch.NewEntry, AppLaunch.AnswerPrompt -> {
                    nav.selectTab(Route.Home)
                    val template = Templates.PROMPT.id.takeIf { request == AppLaunch.AnswerPrompt }
                    nav.open(Route.Editor(null, services.today(), opened = services.now(), template = template))
                }

                AppLaunch.CalendarImport -> {
                    nav.selectTab(Route.Calendar)
                    nav.open(Route.CalendarImport)
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

                is AppMessage.SaveFailed -> snackbar.showSnackbar(getString(Res.string.save_failed, message.reason))

                is AppMessage.Reminder -> snackbar.showSnackbar(getString(Res.string.reminder_now, message.text), withDismissAction = true)

                is AppMessage.Veiled -> {
                    val text = when (message.veil) {
                        Veil.Curtain -> Res.string.veiled_curtain
                        Veil.Quiet -> Res.string.veiled_quiet
                        Veil.Open, null -> Res.string.veiled_none
                    }
                    val result = snackbar.showSnackbar(getString(text), getString(Res.string.veil_undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) services.repo.setVeil(message.entryId, message.previous)
                }

                is AppMessage.Postponed -> {
                    val text =
                        message.until?.let { getString(Res.string.postponed, dateLabel(it, getStringArray(Res.array.months_genitive))) }
                            ?: getString(Res.string.veiled_none)
                    val result = snackbar.showSnackbar(text, getString(Res.string.veil_undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) services.repo.setVeilUntil(message.entryId, message.previous)
                }

                is AppMessage.GentleSwitched ->
                    snackbar.showSnackbar(getString(if (message.on) Res.string.gentle_turned_on else Res.string.gentle_turned_off))
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
    val tabs = top in TAB_ROUTES
    val haptic = LocalHapticFeedback.current
    // A long press on the Feed tab flips Бережно (ADR-031): out of sight, but a moment away.
    val flipGentle: () -> Unit = remember(services, haptic) {
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val on = !services.gentle.on.value
            services.gentle.set(on)
            services.messages.tryEmit(AppMessage.GentleSwitched(on))
        }
    }

    fun Tab.press() = Modifier.hand().then(if (route == Route.Feed) Modifier.longPress(flipGentle) else Modifier)
    val current = backStack.lastOrNull { it in TAB_ROUTES }
    var barHeight by remember { mutableIntStateOf(0) }
    val bar = with(LocalDensity.current) { barHeight.toDp() }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= WIDE
        val twoPane = maxWidth >= TWO_PANE
        Row(Modifier.fillMaxSize()) {
            // Beside an open entry the list stays, and so does the rail; the viewer and drawing take the whole window.
            if (wide && (tabs || (twoPane && top != null && !top.fullWindow))) {
                NavigationRail {
                    Spacer(Modifier.height(8.dp))
                    TABS.forEach { tab ->
                        val label = stringResource(tab.label)
                        NavigationRailItem(
                            selected = current == tab.route,
                            onClick = { nav.selectTab(tab.route) },
                            icon = { Icon(tab.icon, label) },
                            label = { Text(label) },
                            modifier = tab.press(),
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
                    Box(Modifier.fillMaxHeight().widthIn(max = if (wide && !twoPane) READABLE else Dp.Infinity)) {
                        CompositionLocalProvider(LocalWide provides wide) { AppNavigation(services, backStack, nav, twoPane) }
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
                                modifier = tab.press(),
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

/** A wide window (desktop, web, tablets): pickers open as dialogs instead of full-screen sheets. */
internal val LocalWide = compositionLocalOf { false }

/** How wide a screen grows in a big window: lines of text stay readable. */
internal val READABLE = 820.dp

private val Route.fullWindow get() = this is Route.Media || this is Route.Drawing

@Composable
private fun AppNavigation(services: AppServices, backStack: SnapshotStateList<Route>, nav: Navigator, twoPane: Boolean) {
    val pane by rememberUpdatedState(twoPane)
    val listNav = remember(nav) { nav.forList { pane } }
    val listPane = remember(services) { ListPane(services.settings) }
    val strategies = remember(twoPane) {
        if (twoPane) {
            listOf(
                ListDetailStrategy(listPane) {
                    DetailPlaceholder { nav.open(Route.Editor(null, services.today(), opened = services.now())) }
                },
            )
        } else {
            emptyList()
        }
    }
    val openEntry = (backStack.lastOrNull() as? Route.Editor)?.entryId?.takeIf { twoPane }
    CompositionLocalProvider(LocalOpenEntry provides openEntry) { Screens(services, backStack, nav, listNav, strategies) }
}

@Composable
private fun Screens(
    services: AppServices,
    backStack: SnapshotStateList<Route>,
    nav: Navigator,
    listNav: Navigator,
    strategies: List<SceneStrategy<Route>>,
) {
    NavDisplay(
        backStack = backStack,
        onBack = nav::back,
        sceneStrategies = strategies + SinglePaneSceneStrategy(),
        transitionSpec = {
            if (betweenTabs()) still else fadeIn(tween(FADE_MILLIS)) togetherWith ExitTransition.KeepUntilTransitionsFinished
        },
        popTransitionSpec = { if (betweenTabs()) still else EnterTransition.None togetherWith fadeOut(tween(FADE_MILLIS)) },
        predictivePopTransitionSpec = { if (betweenTabs()) still else EnterTransition.None togetherWith fadeOut() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<Route.Home>(metadata = TAB) { HomeScreen(services, listNav) }
            entry<Route.Feed>(metadata = TAB) { FeedScreen(services, listNav) }
            entry<Route.Editor> { EditorScreen(services, nav, it) }
            entry<Route.History> { HistoryScreen(services, nav, it) }
            entry<Route.Revision> { RevisionScreen(services, nav, it) }
            entry<Route.Media>(metadata = FULL_WINDOW) { MediaScreen(services, nav, it) }
            entry<Route.Drawing>(metadata = FULL_WINDOW) { DrawingScreen(services, nav, it) }
            entry<Route.Search>(metadata = TAB) { SearchScreen(services, listNav) }
            entry<Route.Calendar>(metadata = TAB) { CalendarScreen(services, listNav) }
            entry<Route.More>(metadata = TAB) { MoreScreen(services, listNav) }
            entry<Route.Comments> { CommentsScreen(services, nav, it) }
            entry<Route.Tags> { TagsScreen(services, nav) }
            entry<Route.Reminders> { RemindersScreen(services, nav, it) }
            entry<Route.Trash> { TrashScreen(services, nav) }
            entry<Route.CalendarImport> { CalendarImportScreen(services, nav) }
            entry<Route.Server> { ServerScreen(services, nav) }
            entry<Route.Export> { ExportScreen(services, nav) }
            entry<Route.Lock> { LockSettingsScreen(services, nav) }
            entry<Route.Gentle> { GentleScreen(services, nav) }
            entry<Route.GentleEntries> { GentleEntriesScreen(services, nav, it) }
            entry<Route.Speech> { SpeechScreen(services, nav) }
            entry<Route.Appearance> { AppearanceScreen(services, nav) }
            entry<Route.Shortcuts> { ShortcutsScreen(nav, web = services.platform == "web") }
        },
    )
}
