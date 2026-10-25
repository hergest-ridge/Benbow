package app.benbow.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * [content] with its name in a small hint above it (owner: say what each tool does): on hover with a mouse, on a long
 * press by touch (which then doesn't press it). [keys] is its shortcut, when it has one ("Ctrl+B"). A press puts the
 * hint away, so it never covers what the press opened.
 */
@Composable
fun Hinted(hint: String, keys: String? = null, content: @Composable () -> Unit) {
    val state = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(if (keys == null) hint else "$hint · $keys") } },
        state = state,
    ) {
        Box(
            Modifier.pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    state.dismiss()
                }
            },
        ) { content() }
    }
}
