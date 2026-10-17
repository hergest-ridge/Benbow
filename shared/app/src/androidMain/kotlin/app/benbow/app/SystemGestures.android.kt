package app.benbow.app

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

internal actual fun Modifier.keepFromSystemGestures(): Modifier = systemGestureExclusion()
