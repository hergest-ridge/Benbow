@file:OptIn(ExperimentalComposeUiApi::class)

package app.benbow.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.fromKeyword

internal actual val ResizeColumnIcon: PointerIcon = PointerIcon.fromKeyword("col-resize")
