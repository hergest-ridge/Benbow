package app.benbow.app

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keys and the middle button on desktop and the web (ADR-036 N2). */
class PageScrollingTest {
    @Test
    fun keysOfAList() {
        assertEquals(ScrollKey.Page(down = true), scrollKeyOf(Key.PageDown, shift = false, ctrl = false, alt = false))
        assertEquals(ScrollKey.Page(down = false), scrollKeyOf(Key.PageUp, shift = false, ctrl = false, alt = false))
        assertEquals(ScrollKey.Page(down = true), scrollKeyOf(Key.Spacebar, shift = false, ctrl = false, alt = false))
        assertEquals(
            ScrollKey.Page(down = false),
            scrollKeyOf(Key.Spacebar, shift = true, ctrl = false, alt = false),
            "Shift+Space goes up",
        )
        assertEquals(ScrollKey.Step(down = true), scrollKeyOf(Key.DirectionDown, shift = false, ctrl = false, alt = false))
        assertEquals(ScrollKey.Home, scrollKeyOf(Key.MoveHome, shift = false, ctrl = true, alt = false), "Ctrl+Home too")
        assertEquals(ScrollKey.End, scrollKeyOf(Key.MoveEnd, shift = false, ctrl = false, alt = false))
        assertEquals(ScrollKey.Escape, scrollKeyOf(Key.Escape, shift = false, ctrl = false, alt = false))
        assertNull(scrollKeyOf(Key.DirectionLeft, shift = false, ctrl = false, alt = true), "Alt+← is Back")
        assertNull(scrollKeyOf(Key.PageDown, shift = false, ctrl = true, alt = false), "Ctrl+PgDn is the browser's")
        assertNull(scrollKeyOf(Key.A, shift = false, ctrl = false, alt = false))
    }

    @Test
    fun theAutoscrollIsStillNearTheAnchorThenSpeedsUp() {
        assertEquals(0f, autoscrollSpeed(10f), "within the dead zone")
        assertEquals(0f, autoscrollSpeed(-12f))
        val near = autoscrollSpeed(40f)
        val far = autoscrollSpeed(200f)
        assertTrue(near > 0f && far > near * 4, "farther is much faster: $near, $far")
        assertEquals(-near, autoscrollSpeed(-40f), "above the anchor it goes up")
        assertEquals(6_000f, autoscrollSpeed(5_000f), "capped")
    }

    @Test
    fun aQuickStillClickSticksAHoldDoesNot() {
        assertTrue(autoscrollSticks(heldMillis = 120, moved = 2f))
        assertFalse(autoscrollSticks(heldMillis = 600, moved = 2f), "held")
        assertFalse(autoscrollSticks(heldMillis = 120, moved = 40f), "moved")
    }
}
