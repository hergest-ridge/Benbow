package app.benbow.app

import androidx.compose.ui.Modifier

/** A mouse has no edge swipes. */
internal actual fun Modifier.keepFromSystemGestures(): Modifier = this
