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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
import app.benbow.app.resources.audio_failed
import app.benbow.app.resources.board_archived
import app.benbow.app.resources.board_archived_many
import app.benbow.app.resources.board_trashed
import app.benbow.app.resources.board_trashed_many
import app.benbow.app.resources.card_became_text
import app.benbow.app.resources.category_trashed
import app.benbow.app.resources.copied
import app.benbow.app.resources.dictation_attach
import app.benbow.app.resources.dictation_done
import app.benbow.app.resources.dictation_kept
import app.benbow.app.resources.dictation_needs_model
import app.benbow.app.resources.dictation_open_model
import app.benbow.app.resources.feed_hidden
import app.benbow.app.resources.gentle_turned_off
import app.benbow.app.resources.gentle_turned_on
import app.benbow.app.resources.home_removed
import app.benbow.app.resources.media_mic_denied
import app.benbow.app.resources.media_trashed
import app.benbow.app.resources.media_trashed_many
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.moved_open
import app.benbow.app.resources.moved_to_board
import app.benbow.app.resources.moved_to_board_many
import app.benbow.app.resources.moved_to_journal
import app.benbow.app.resources.moved_to_journal_many
import app.benbow.app.resources.postponed
import app.benbow.app.resources.purge_done
import app.benbow.app.resources.reminder_now
import app.benbow.app.resources.removed_atom
import app.benbow.app.resources.removed_card
import app.benbow.app.resources.removed_link
import app.benbow.app.resources.save_failed
import app.benbow.app.resources.saved
import app.benbow.app.resources.size_units
import app.benbow.app.resources.speech_failed
import app.benbow.app.resources.storage_freed
import app.benbow.app.resources.storage_freed_none
import app.benbow.app.resources.tab_board
import app.benbow.app.resources.tab_calendar
import app.benbow.app.resources.tab_feed
import app.benbow.app.resources.tab_friends
import app.benbow.app.resources.tab_home
import app.benbow.app.resources.tab_more
import app.benbow.app.resources.tab_search
import app.benbow.app.resources.tab_trackers
import app.benbow.app.resources.tag_trashed
import app.benbow.app.resources.tags_merged
import app.benbow.app.resources.tags_trashed
import app.benbow.app.resources.template_saved
import app.benbow.app.resources.template_trashed
import app.benbow.app.resources.tracker_cleared
import app.benbow.app.resources.tracker_trashed
import app.benbow.app.resources.transcript_removed
import app.benbow.app.resources.veil_undo
import app.benbow.app.resources.veiled_curtain
import app.benbow.app.resources.veiled_none
import app.benbow.app.resources.veiled_quiet
import app.benbow.app.resources.voice_replaced
import app.benbow.client.IoDispatcher
import app.benbow.client.TagRegistry
import app.benbow.client.Templates
import app.benbow.client.Veil
import app.benbow.client.archiveOnBoard
import app.benbow.client.editHomeLayout
import app.benbow.client.plusBlock
import app.benbow.client.restoreAttachments
import app.benbow.client.restoreCategory
import app.benbow.client.restoreTag
import app.benbow.client.splitTag
import app.benbow.client.tagRegistry
import app.benbow.ui.editor.Removal
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
     * the menu switches to editing. [board]: a note opened on the board (ADR-040), a new one made there; [list]: a new
     * one that starts as a list (the + button's «Список»).
     */
    data class Editor(
        val entryId: String?,
        val day: String,
        val opened: Long = 0,
        val template: String? = null,
        val readOnly: Boolean = false,
        val heading: String? = null,
        val board: Boolean = false,
        val list: Boolean = false,
    ) : Route

    data class History(val entryId: String) : Route

    data class Revision(val entryId: String, val revisionId: String, val createdAt: Long) : Route

    data class Media(val entryId: String, val attachmentId: String) : Route

    /** A drawing to make ([attachmentId] null) or to change. */
    data class Drawing(val entryId: String, val attachmentId: String?) : Route

    data object Search : Route

    data object Calendar : Route

    /** Notes beside the journal, Keep-like (ADR-040). */
    data object Board : Route

    /** Trackers (ADR-050): a tab on the web and the desktop, a page from «Ещё» on a phone. */
    data object Trackers : Route

    /** «Лента друзей (проба)» (ADR-056): a tab in the browser only. */
    data object Friends : Route

    /** A tracker's sheet as a page of its own, where the window is too narrow for it beside the list. */
    data class TrackerSheet(val trackerId: String) : Route

    /** «Настроить трекер» (ADR-050, B5): its name, emoji and columns. */
    data class TrackerEditor(val trackerId: String) : Route

    data class Comments(val entryId: String) : Route

    data object Tags : Route

    /** A tag's page (7.9 T4): what it is and its entries. */
    data class Tag(val tagId: String) : Route

    /** [day] preselects the date (from the calendar). */
    data class Reminders(val day: String? = null) : Route

    data object Trash : Route

    /** One's own templates and the standard ones (ADR-061). */
    data object Templates : Route

    /** «Облик записки (проба)» (ADR-047, 7.20 L0), in [AppServices.labs] builds only. */
    data object LooksSpike : Route

    /** «Ещё → Медиатека» (ADR-041): every attachment shown in an entry. */
    data object Library : Route

    /** The library's viewer at [key] ("entry/attachment"), paging through what [view] shows. */
    data class LibraryMedia(val view: LibraryView, val key: String) : Route

    /** «Место» (ADR-041): what the files weigh on the server and here. */
    data object Storage : Route

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

private val TAB_ROUTES = setOf(Route.Home, Route.Feed, Route.Calendar, Route.Board, Route.Search, Route.More)

/**
 * The tabs in order. «Записки» comes after Календарь (ADR-040); Поиск moved to «Ещё» for the time being (owner: six tabs
 * are too many).
 */
private val TABS = listOf(
    Tab(Route.Home, Res.string.tab_home, Icons.Home),
    Tab(Route.Feed, Res.string.tab_feed, Icons.Feed),
    Tab(Route.Calendar, Res.string.tab_calendar, Icons.Calendar),
    Tab(Route.Board, Res.string.tab_board, Icons.Board),
    Tab(Route.More, Res.string.tab_more, Icons.MoreHoriz),
)

private val TRACKERS_TAB = Tab(Route.Trackers, Res.string.tab_trackers, Icons.Trackers)

private val FRIENDS_TAB = Tab(Route.Friends, Res.string.tab_friends, Icons.Friends)

/**
 * The tabs here: «Трекеры» after «Записки» on the web and the desktop (ADR-050); a phone keeps them in «Ещё». The
 * browser also has «Друзья (проба)» before «Ещё» (ADR-056).
 */
private fun tabsOf(services: AppServices): List<Tab> {
    val tabs = if (services.trackersTab) TABS.flatMap { if (it.route == Route.Board) listOf(it, TRACKERS_TAB) else listOf(it) } else TABS
    return if (services.friendsTab) tabs.flatMap { if (it.route == Route.More) listOf(FRIENDS_TAB, it) else listOf(it) } else tabs
}

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
            LocalPagerArrows provides (services.platform != "android"), // a mouse: side buttons on pictures
            LocalServerFonts provides services.fonts, // the fonts notes take from the server (ADR-052)
            LocalMediaStore provides services.media, // a note's light from its pictures
        ) { AppRoot(services) }
    }
}

@Composable
private fun AppRoot(services: AppServices) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        services.sync.foreground = true
        services.lock.onReturn()
        if (services.gentle.onReturn()) services.cameBack.tryEmit(Unit)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        services.sync.foreground = false
        services.lock.onLeave()
        services.gentle.onLeave()
        services.updates.inBackground() // a quiet update may go in now (ADR-042, U3)
    }
    // Android 17 keeps the app off the home network, and so from its server, until this is granted (ADR-062).
    val network = rememberLocalNetworkAccess()
    LaunchedEffect(Unit) {
        if (network.missing && withContext(IoDispatcher) { services.settings.connection } != null) network.ask(services.sync::requestSync)
    }
    val backStack = remember { mutableStateListOf<Route>(Route.Home) }
    val nav = remember(backStack) { Navigator(backStack) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    PlatformAddress(backStack, nav) { link ->
        when (link) {
            is Link.Tab -> nav.selectTab(
                link.route.takeIf { (it != Route.Trackers || services.trackersTab) && (it != Route.Friends || services.friendsTab) }
                    ?: Route.Home,
            )

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

                // The update's notification (ADR-042): «Ещё», where «Обновить» is the first line.
                AppLaunch.Update -> nav.selectTab(Route.More)

                // A tracker's widget (ADR-050): the trackers, a tab or a page from «Ещё».
                AppLaunch.Trackers -> if (services.trackersTab) {
                    nav.selectTab(Route.Trackers)
                } else {
                    nav.selectTab(Route.More)
                    nav.open(Route.Trackers)
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
                is Shortcut.Tab -> tabsOf(services).getOrNull(shortcut.index)?.let { nav.selectTab(it.route) }
                Shortcut.Save -> Unit // the open editor takes it
            }
        }
    }
    LaunchedEffect(Unit) {
        suspend fun show(message: AppMessage) {
            when (message) {
                is AppMessage.AttachFailed -> snackbar.showSnackbar(getString(Res.string.attach_failed, message.reason))

                AppMessage.PlaybackFailed -> snackbar.showSnackbar(getString(Res.string.audio_failed))

                is AppMessage.TranscriptionFailed -> snackbar.showSnackbar(getString(Res.string.speech_failed, message.reason))

                AppMessage.Copied -> snackbar.showSnackbar(getString(Res.string.copied))

                AppMessage.Saved -> snackbar.showSnackbar(getString(Res.string.saved))

                is AppMessage.SaveFailed -> snackbar.showSnackbar(getString(Res.string.save_failed, message.reason))

                is AppMessage.Reminder -> {
                    snackbar.showSnackbar(getString(Res.string.reminder_now, message.text), withDismissAction = true)
                }

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

                AppMessage.HiddenFromFeed -> snackbar.showSnackbar(getString(Res.string.feed_hidden))

                is AppMessage.TagsTrashed -> {
                    val text = message.names.singleOrNull()?.let { getString(Res.string.tag_trashed, it) }
                        ?: getString(Res.string.tags_trashed, message.names.size)
                    val result = snackbar.showSnackbar(text, getString(Res.string.veil_undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) message.ids.forEach { services.repo.restoreTag(it) }
                }

                is AppMessage.TagsMerged -> {
                    val result = snackbar.showSnackbar(
                        getString(Res.string.tags_merged, message.into),
                        getString(Res.string.veil_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) message.ids.forEach { services.repo.splitTag(it) }
                }

                is AppMessage.CategoryTrashed -> {
                    val result =
                        snackbar.showSnackbar(
                            getString(Res.string.category_trashed, message.name),
                            getString(Res.string.veil_undo),
                            duration = SnackbarDuration.Long,
                        )
                    if (result == SnackbarResult.ActionPerformed) services.repo.restoreCategory(message.id)
                }

                is AppMessage.HomeBlockRemoved -> {
                    val name = message.block.kind?.let { getString(it.title) }.orEmpty()
                    val result = snackbar.showSnackbar(
                        getString(Res.string.home_removed, name),
                        getString(Res.string.veil_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) services.repo.editHomeLayout { it.plusBlock(message.block, message.at) }
                }

                is AppMessage.Dictated -> {
                    val result = snackbar.showSnackbar(
                        getString(Res.string.dictation_done),
                        getString(Res.string.dictation_attach),
                        duration = SnackbarDuration.Long,
                    )
                    services.dictations?.settle(message, attach = result == SnackbarResult.ActionPerformed)
                }

                AppMessage.DictationKept -> snackbar.showSnackbar(getString(Res.string.dictation_kept))

                AppMessage.DictationNeedsModel -> {
                    val result = snackbar.showSnackbar(
                        getString(Res.string.dictation_needs_model),
                        getString(Res.string.dictation_open_model),
                        duration = SnackbarDuration.Long, // with an action the default never ends
                    )
                    if (result == SnackbarResult.ActionPerformed) nav.open(Route.Speech)
                }

                AppMessage.MicDenied -> snackbar.showSnackbar(getString(Res.string.media_mic_denied))

                is AppMessage.TranscriptRemoved -> {
                    val result = snackbar.showSnackbar(
                        getString(Res.string.transcript_removed),
                        getString(Res.string.veil_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result ==
                        SnackbarResult.ActionPerformed
                    ) {
                        services.repo.setTranscript(message.entryId, message.attachmentId, message.text)
                    }
                }

                is AppMessage.TextRemoved -> {
                    val text = when (message.removal.kind) {
                        Removal.Kind.Atom -> Res.string.removed_atom
                        Removal.Kind.Card -> Res.string.removed_card
                        Removal.Kind.Link -> Res.string.removed_link
                        Removal.Kind.CardToText -> Res.string.card_became_text
                    }
                    val result = snackbar.showSnackbar(getString(text), getString(Res.string.veil_undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) services.removalBack.emit(message.removal)
                }

                is AppMessage.TrackerTrashed -> {
                    val result = snackbar.showSnackbar(
                        getString(Res.string.tracker_trashed, message.name),
                        getString(Res.string.veil_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) services.trackers.restore(message.id)
                }

                is AppMessage.TrackerDayCleared -> {
                    val result = snackbar.showSnackbar(getString(Res.string.tracker_cleared), getString(Res.string.veil_undo))
                    if (result == SnackbarResult.ActionPerformed) services.trackerWrites(message.undo)
                }

                is AppMessage.MovedToBoard -> {
                    val text = message.ids.singleOrNull()?.let { getString(Res.string.moved_to_board) }
                        ?: getString(Res.string.moved_to_board_many, message.ids.size)
                    val result = snackbar.showSnackbar(text, getString(Res.string.moved_open), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) {
                        services.showOnBoard.value = message.ids.first()
                        nav.selectTab(Route.Board)
                    }
                }

                is AppMessage.MovedToJournal -> {
                    val text = message.ids.singleOrNull()?.let {
                        getString(Res.string.moved_to_journal, dateLabel(message.day, getStringArray(Res.array.months_genitive)))
                    } ?: getString(Res.string.moved_to_journal_many, message.ids.size)
                    val result = snackbar.showSnackbar(text, getString(Res.string.moved_open), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) {
                        services.showInFeed.value = message.ids.first()
                        nav.selectTab(Route.Feed)
                    }
                }

                is AppMessage.BoardArchived -> {
                    val text = message.ids.singleOrNull()?.let { getString(Res.string.board_archived) }
                        ?: getString(Res.string.board_archived_many, message.ids.size)
                    val result = snackbar.showSnackbar(text, getString(Res.string.veil_undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) message.ids.forEach { services.repo.archiveOnBoard(it, false) }
                }

                is AppMessage.TemplateSaved -> {
                    val result = snackbar.showSnackbar(
                        getString(Res.string.template_saved, message.name),
                        getString(Res.string.moved_open),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) nav.open(Route.Editor(message.id, services.today()))
                }

                is AppMessage.TemplateTrashed -> {
                    val result = snackbar.showSnackbar(
                        getString(Res.string.template_trashed, message.name),
                        getString(Res.string.veil_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) services.repo.restore(message.id)
                }

                is AppMessage.BoardTrashed -> {
                    val text = message.ids.singleOrNull()?.let { getString(Res.string.board_trashed) }
                        ?: getString(Res.string.board_trashed_many, message.ids.size)
                    val result = snackbar.showSnackbar(text, getString(Res.string.veil_undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) message.ids.forEach { services.repo.restore(it) }
                }

                is AppMessage.Purged -> {
                    val (number, unit) = sizeParts(message.bytes)
                    val size = "$number${Char(0x00A0)}${getStringArray(Res.array.size_units).getOrElse(unit) { "" }}"
                    val day = dateLabel(dayOf(message.freesOn), getStringArray(Res.array.months_genitive))
                    snackbar.showSnackbar(getString(Res.string.purge_done, size, day), withDismissAction = true)
                }

                is AppMessage.DeviceFreed -> {
                    val (number, unit) = sizeParts(message.bytes)
                    val size = "$number${Char(0x00A0)}${getStringArray(Res.array.size_units).getOrElse(unit) { "" }}"
                    snackbar.showSnackbar(
                        getString(
                            if (message.bytes >
                                0
                            ) {
                                Res.string.storage_freed
                            } else {
                                Res.string.storage_freed_none
                            },
                            size,
                        ),
                    )
                }

                is AppMessage.MediaTrashed -> {
                    val text = message.keys.singleOrNull()?.let { getString(Res.string.media_trashed) }
                        ?: getString(Res.string.media_trashed_many, message.keys.size)
                    val result = snackbar.showSnackbar(text, getString(Res.string.veil_undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) services.repo.restoreAttachments(message.keys)
                }

                is AppMessage.VoiceReplaced -> {
                    val result = snackbar.showSnackbar(
                        getString(Res.string.voice_replaced),
                        getString(Res.string.veil_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) services.restoreVoice(message.entryId, message.attachmentId)
                }
            }
        }
        // The newest message takes the place of the one on screen (none waits behind another), each in its own
        // coroutine so a snackbar's wait never holds up the next message.
        services.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            launch { show(message) }
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
    val tabList = remember(services) { tabsOf(services) }
    val tabRoutes = remember(tabList) { TAB_ROUTES + tabList.map { it.route } }
    val top = backStack.lastOrNull()
    val tabs = top in tabRoutes
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
    val current = backStack.lastOrNull { it in tabRoutes }
    var barHeight by remember { mutableIntStateOf(0) }
    val bar = with(LocalDensity.current) { barHeight.toDp() }
    // The mini-player (ADR-035) sits on the tab bar, or at the foot of a wide window; it opens the player screen.
    var miniHeight by remember { mutableIntStateOf(0) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val openPlayer: () -> Unit = remember { { playerOpen = true } }
    val scope = rememberCoroutineScope()
    var railWidth by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= WIDE
        val twoPane = maxWidth >= TWO_PANE
        // Beside an open entry the list stays, and so does the rail. The viewer and a drawing take the whole window (owner:
        // no rail in full screen), but the rail stays put beneath them as it was for the screen they opened from, so
        // nothing under them jumps aside as they come and go (WholeWindow lays them out over it).
        val under = backStack.lastOrNull { !it.fullWindow }
        val rail = wide && (under in tabRoutes || (twoPane && under != null))
        val covered = rail && top?.fullWindow == true
        val density = LocalDensity.current
        val railDp = if (rail) with(density) { railWidth.toDp() } else 0.dp
        val column = maxWidth - railDp
        val box = if (wide && !twoPane) minOf(column, READABLE) else column
        val span = WindowSpan(left = railDp + (column - box) / 2, width = maxWidth)
        Row(Modifier.fillMaxSize()) {
            if (rail) {
                NavigationRail(
                    Modifier.onSizeChanged { railWidth = it.width }.then(if (covered) Modifier.clearAndSetSemantics {} else Modifier),
                ) {
                    Spacer(Modifier.height(8.dp))
                    tabList.forEach { tab ->
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
                val miniShown = bottomBar || (wide && top?.fullWindow != true)
                val mini = with(LocalDensity.current) { if (miniShown) miniHeight.toDp() else 0.dp }
                Box(
                    Modifier.weight(1f).fillMaxWidth().then(
                        if (bottomBar) {
                            Modifier.consumeWindowInsets(
                                WindowInsets.navigationBars,
                            ).consumeWindowInsets(PaddingValues(bottom = bar + mini))
                        } else {
                            Modifier
                        },
                    ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.fillMaxHeight().widthIn(max = if (wide && !twoPane) READABLE else Dp.Infinity)) {
                        CompositionLocalProvider(
                            LocalWide provides wide,
                            LocalOpenPlayer provides openPlayer,
                            LocalWindowSpan provides span,
                        ) {
                            AppNavigation(services, backStack, nav, twoPane)
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
                }
                if (miniShown) {
                    MiniPlayer(
                        services,
                        onOpen = { playerOpen = true },
                        bottomInset = !bottomBar,
                        Modifier.onSizeChanged {
                            miniHeight =
                                it.height
                        },
                    )
                }
                if (bottomBar) {
                    ShortNavigationBar(Modifier.onSizeChanged { barHeight = it.height }) {
                        tabList.forEach { tab ->
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
        if (playerOpen) {
            CompositionLocalProvider(LocalWide provides wide) {
                PlayerScreen(services, onOpenEntry = { id ->
                    scope.launch {
                        val entry = services.repo.entry(id).first() ?: return@launch
                        nav.open(Route.Editor(entry.id, entry.day, readOnly = true))
                    }
                }, onDismiss = { playerOpen = false })
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

private val Route.fullWindow get() = this is Route.Media || this is Route.LibraryMedia || this is Route.Drawing

/** Where the screens' frame stands in the window: [left] from the window's edge (the rail, a centred column), and its [width]. */
internal class WindowSpan(val left: Dp, val width: Dp)

private val LocalWindowSpan = compositionLocalOf { WindowSpan(0.dp, 0.dp) }

/**
 * A screen over the whole window from inside the screens' frame (the viewer, a drawing): out over the rail and the
 * margins, which stay put beneath it. What falls through it never reaches the rail.
 */
@Composable
private fun WholeWindow(content: @Composable () -> Unit) {
    val span = LocalWindowSpan.current
    Box(
        Modifier.layout { measurable, constraints ->
            val left = span.left.roundToPx()
            val width = maxOf(span.width.roundToPx(), constraints.maxWidth)
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-left, 0) }
        }.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) { content() }
}

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
    val openTag = remember(nav) { { id: String -> nav.open(Route.Tag(id)) } }
    CompositionLocalProvider(LocalOpenEntry provides openEntry, LocalOpenTag provides openTag) {
        ProvideLinks(services, nav) { Screens(services, backStack, nav, listNav, strategies) }
    }
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
        sceneStrategies = listOf(BoardSceneStrategy(nav::back)) + strategies + SinglePaneSceneStrategy(),
        transitionSpec = {
            if (betweenTabs()) still else fadeIn(tween(FADE_MILLIS)) togetherWith ExitTransition.KeepUntilTransitionsFinished
        },
        popTransitionSpec = { if (betweenTabs()) still else EnterTransition.None togetherWith fadeOut(tween(FADE_MILLIS)) },
        predictivePopTransitionSpec = { if (betweenTabs()) still else EnterTransition.None togetherWith fadeOut() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<Route.Home>(metadata = TAB) { HomeScreen(services, listNav) }
            entry<Route.Feed>(metadata = TAB) { FeedScreen(services, listNav) }
            entry<Route.Editor>(metadata = { if (it.board) mapOf(BOARD_NOTE to (it.entryId ?: "")) else emptyMap() }) {
                EditorScreen(services, nav, it)
            }
            entry<Route.History> { HistoryScreen(services, nav, it) }
            entry<Route.Revision> { RevisionScreen(services, nav, it) }
            entry<Route.Media>(metadata = FULL_WINDOW) { WholeWindow { MediaScreen(services, nav, it) } }
            entry<Route.LibraryMedia>(metadata = FULL_WINDOW) { WholeWindow { LibraryViewer(services, nav, it) } }
            entry<Route.Drawing>(metadata = FULL_WINDOW) { WholeWindow { DrawingScreen(services, nav, it) } }
            entry<Route.Search>(metadata = TAB) { SearchScreen(services, listNav) }
            entry<Route.Calendar>(metadata = TAB) { CalendarScreen(services, listNav) }
            // A grid wants the window, not a list's pane; a note opens on it in the board's own scene (ADR-040).
            entry<Route.Board>(metadata = TAB + FULL_WINDOW + mapOf(BOARD_ENTRY to true)) { BoardScreen(services, listNav) }
            // A sheet wants the window, as the board does; a tab only where trackers have one (ADR-050).
            entry<Route.Trackers>(metadata = if (services.trackersTab) TAB + FULL_WINDOW else emptyMap()) {
                TrackersScreen(services, nav, tab = services.trackersTab)
            }
            entry<Route.Friends>(metadata = TAB + FULL_WINDOW) { FriendsFeedScreen(services) }
            entry<Route.TrackerSheet>(metadata = if (services.trackersTab) FULL_WINDOW else emptyMap()) {
                TrackerSheetScreen(services, nav, it.trackerId, back = true)
            }
            entry<Route.TrackerEditor>(metadata = if (services.trackersTab) FULL_WINDOW else emptyMap()) {
                TrackerEditorScreen(services, nav, it.trackerId)
            }
            entry<Route.More>(metadata = TAB) { MoreScreen(services, listNav) }
            entry<Route.Comments> { CommentsScreen(services, nav, it) }
            entry<Route.Tags> { TagsScreen(services, nav) }
            entry<Route.Tag> { TagPage(services, nav, it) }
            entry<Route.Reminders> { RemindersScreen(services, nav, it) }
            entry<Route.Trash> { TrashScreen(services, nav) }
            entry<Route.Templates> { TemplatesScreen(services, nav) }
            entry<Route.LooksSpike> { LooksSpikeScreen(nav) }
            entry<Route.Library> { LibraryScreen(services, nav) }
            entry<Route.Storage> { StorageScreen(services, nav) }
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
