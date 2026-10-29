package app.benbow.app

import androidx.compose.runtime.Composable

// The browser's address for what is on screen (ADR-029, W4): #/feed, #/entry/<id>, #/server. Screens worth a link
// have their own; the rest (a revision, a drawing) show their entry's. A bookmark or a reload opens the same place.

internal fun Route.path(): String = when (this) {
    Route.Home -> ""

    Route.Feed -> "feed"

    Route.Calendar, Route.CalendarImport -> "calendar"

    Route.Board -> "notes"

    Route.Trackers, is Route.TrackerSheet, is Route.TrackerEditor -> "trackers"

    Route.Friends -> "friends"

    Route.Search -> "search"

    Route.More -> "more"

    is Route.Editor -> entryId?.let { "entry/$it" } ?: "new"

    is Route.Comments -> "entry/$entryId/comments"

    is Route.History -> "entry/$entryId/history"

    is Route.Revision -> "entry/$entryId/history"

    is Route.Media -> "entry/$entryId"

    is Route.Drawing -> "entry/$entryId"

    Route.Tags, is Route.Tag -> "tags"

    // a tag's id may be any old name: no address of its own
    is Route.Reminders -> "reminders"

    Route.Trash -> "trash"

    Route.LooksSpike -> "looks"

    Route.Library, is Route.LibraryMedia -> "media"

    Route.Storage -> "space"

    Route.Server -> "server"

    Route.Export -> "export"

    Route.Lock -> "lock"

    Route.Gentle, is Route.GentleEntries -> "gentle"

    Route.Speech -> "speech"

    Route.Appearance -> "appearance"

    Route.Shortcuts -> "shortcuts"
}

/** Where an address leads. */
internal sealed interface Link {
    data class Tab(val route: Route) : Link

    /** A page reached from "Ещё". */
    data class Page(val route: Route) : Link

    data class Entry(val id: String, val comments: Boolean = false) : Link

    data object NewEntry : Link
}

internal fun linkOf(path: String): Link? {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    return when (parts.firstOrNull()) {
        null -> Link.Tab(Route.Home)
        "feed" -> Link.Tab(Route.Feed)
        "calendar" -> Link.Tab(Route.Calendar)
        "notes" -> Link.Tab(Route.Board)
        "trackers" -> Link.Tab(Route.Trackers)
        "friends" -> Link.Tab(Route.Friends)
        "search" -> Link.Tab(Route.Search)
        "more" -> Link.Tab(Route.More)
        "new" -> Link.NewEntry
        "entry" -> parts.getOrNull(1)?.takeIf { ID.matches(it) }?.let { Link.Entry(it, comments = parts.getOrNull(2) == "comments") }
        "tags" -> Link.Page(Route.Tags)
        "reminders" -> Link.Page(Route.Reminders())
        "trash" -> Link.Page(Route.Trash)
        "media" -> Link.Page(Route.Library)
        "space" -> Link.Page(Route.Storage)
        "server" -> Link.Page(Route.Server)
        "export" -> Link.Page(Route.Export)
        "lock" -> Link.Page(Route.Lock)
        "gentle" -> Link.Page(Route.Gentle)
        "appearance" -> Link.Page(Route.Appearance)
        "shortcuts" -> Link.Page(Route.Shortcuts)
        else -> null
    }
}

private val ID = Regex("[0-9a-fA-F-]{8,64}")

/**
 * Keeps the browser's address and Back button in step with [backStack] (web only; the apps have their own back).
 * [open] follows the address the page was opened at.
 */
@Composable
internal expect fun PlatformAddress(backStack: List<Route>, nav: Navigator, open: (Link) -> Unit)
