package app.benbow.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * «Свет записки на всю область» (Ещё → Оформление, on by default; owner): in a wide window the open note's light falls
 * across the whole right side, not only the note. The note tells its lights here; the pane draws them over its margins
 * and the note draws the same ones under its words, laid out in the pane ([frameAt]), so they run on unbroken.
 */
@Stable
internal class PaneGlow {
    private var owner: Any? = null
    var lights by mutableStateOf<List<LookLight>>(emptyList())
        private set
    var dark by mutableStateOf(false)
        private set

    /** The pane in the window, as laid out. */
    var bounds by mutableStateOf(Rect.Zero)

    fun show(by: Any, lights: List<LookLight>, dark: Boolean) {
        owner = by
        this.lights = lights
        this.dark = dark
    }

    /** Takes [by]'s lights away, unless another note has told its own since. */
    fun hide(by: Any) {
        if (owner != by) return
        owner = null
        lights = emptyList()
    }

    /** Where a note whose top left is at [topLeft] in the window lays its lights out. */
    fun frameAt(topLeft: Offset) = LightFrame(bounds.size, topLeft - bounds.topLeft)
}

/** The right side's glow while «Свет записки на всю область» is on and the note is open beside a list; null elsewhere. */
internal val LocalPaneGlow = compositionLocalOf<PaneGlow?> { null }
