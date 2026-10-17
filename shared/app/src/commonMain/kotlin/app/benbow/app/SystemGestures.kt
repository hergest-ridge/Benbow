package app.benbow.app

import androidx.compose.ui.Modifier

/** Where the system's own edge swipe (Android's back) gives way to the app: the feed scrubber's thumb (ADR-036 N3). */
internal expect fun Modifier.keepFromSystemGestures(): Modifier
