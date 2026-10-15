package app.benbow.app

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class ShortcutsTest {
    @Test
    fun theAppTakesOnlyItsOwnCombinations() {
        assertEquals(Shortcut.NewEntry, shortcutOf(Key.N, ctrl = true, alt = false))
        assertEquals(Shortcut.Search, shortcutOf(Key.F, ctrl = true, alt = false))
        assertEquals(Shortcut.Save, shortcutOf(Key.S, ctrl = true, alt = false))
        assertEquals(Shortcut.Tab(2), shortcutOf(Key.Three, ctrl = true, alt = false))
        assertEquals(Shortcut.Back, shortcutOf(Key.DirectionLeft, ctrl = false, alt = true))
        for (key in listOf(Key.B, Key.I, Key.U, Key.E, Key.Z, Key.Y, Key.C, Key.V, Key.X)) {
            assertEquals(null, shortcutOf(key, ctrl = true, alt = false), "$key is the editor's")
        }
        assertEquals(null, shortcutOf(Key.N, ctrl = false, alt = false), "plain typing")
    }

    @Test
    fun theBrowserKeepsItsOwnKeys() {
        assertEquals(Shortcut.NewEntry, webShortcutOf("KeyN", ctrl = false, alt = true))
        assertEquals(null, webShortcutOf("KeyN", ctrl = true, alt = false), "Ctrl+N opens a browser window")
        assertEquals(Shortcut.Tab(1), webShortcutOf("Digit2", ctrl = false, alt = true))
        assertEquals(null, webShortcutOf("Digit2", ctrl = true, alt = false), "Ctrl+2 is the browser's second tab")
        assertEquals(Shortcut.Search, webShortcutOf("KeyF", ctrl = true, alt = false))
        assertEquals(Shortcut.Save, webShortcutOf("KeyS", ctrl = true, alt = false))
        assertEquals(null, webShortcutOf("KeyB", ctrl = true, alt = false), "the editor's bold")
    }
}
