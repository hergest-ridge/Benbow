package app.benbow.app

import androidx.compose.ui.Modifier

/** The browser's own swipes stay the browser's. */
internal actual fun Modifier.keepFromSystemGestures(): Modifier = this
