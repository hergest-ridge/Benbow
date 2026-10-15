package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.shortcut_list
import app.benbow.app.resources.shortcut_list_web
import app.benbow.app.resources.shortcuts
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** What a key combination asks for. The window passes keys to [shortcutOf] and the result to [AppServices.shortcuts]. */
sealed interface Shortcut {
    data object NewEntry : Shortcut

    data object Search : Shortcut

    /** Commit the open entry now (it's saved as a draft all along; this makes a version). */
    data object Save : Shortcut

    data object Back : Shortcut

    /** Ctrl+1 … Ctrl+5: the tabs in order. */
    data class Tab(val index: Int) : Shortcut
}

private val DIGITS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five)

/** Ctrl (or ⌘) + N, F, S, 1–5, and Alt+←. The editor's own Ctrl+B/I/U/E/Z/Y/C/X are left alone. */
fun shortcutOf(key: Key, ctrl: Boolean, alt: Boolean): Shortcut? = when {
    ctrl && key == Key.N -> Shortcut.NewEntry
    ctrl && key == Key.F -> Shortcut.Search
    ctrl && key == Key.S -> Shortcut.Save
    ctrl && key in DIGITS -> Shortcut.Tab(DIGITS.indexOf(key))
    alt && key == Key.DirectionLeft -> Shortcut.Back
    else -> null
}

/**
 * In the browser (ADR-029) Ctrl+N (a new window) and Ctrl+1…5 (its tabs) belong to the browser, so the app takes Alt+N
 * and Alt+1…5; Ctrl+F and Ctrl+S are the app's (the page is a canvas: the browser's find and save see nothing).
 * Back is the browser's own (Alt+←, a mouse's side button), which the app follows. [code] is KeyboardEvent.code.
 */
fun webShortcutOf(code: String, ctrl: Boolean, alt: Boolean): Shortcut? = when {
    alt && !ctrl && code == "KeyN" -> Shortcut.NewEntry
    ctrl && !alt && code == "KeyF" -> Shortcut.Search
    ctrl && !alt && code == "KeyS" -> Shortcut.Save
    alt && !ctrl && code in WEB_DIGITS -> Shortcut.Tab(WEB_DIGITS.indexOf(code))
    else -> null
}

private val WEB_DIGITS = listOf("Digit1", "Digit2", "Digit3", "Digit4", "Digit5")

fun shortcutOf(event: KeyEvent): Shortcut? =
    if (event.type != KeyEventType.KeyDown) null else shortcutOf(event.key, event.isCtrlPressed || event.isMetaPressed, event.isAltPressed)

/** "Ещё → Сочетания клавиш" (desktop and the browser, each with its own keys): what each combination does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShortcutsScreen(nav: Navigator, web: Boolean) {
    val lines = stringArrayResource(if (web) Res.array.shortcut_list_web else Res.array.shortcut_list)
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.shortcuts)) })
    }) { padding ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(padding).padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.forEach { line ->
                    val (keys, what) = line.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            keys,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(150.dp),
                        )
                        Text(what, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
