package app.benbow.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry

/*
 * Compose copies the *displayed* text, which uses PARAGRAPH_BREAK_GLYPH instead of '\n'. The default
 * text menu is kept (its order and system items stay native), and each platform repairs the clipboard
 * right after a copy. Ctrl+C / Ctrl+X are handled by the editor itself with model text.
 * Rich copy (HTML, Benbow JSON) will hook in here too.
 */

internal fun String.hasDisplayGlyphs(): Boolean = PARAGRAPH_BREAK_GLYPH in this || TAIL_GLYPH in this

internal fun restoreParagraphBreaks(text: String): String = text.replace(PARAGRAPH_BREAK_GLYPH, '\n').replace(TAIL_GLYPH.toString(), "")

/**
 * Repairs text the editor's default Copy/Cut put on the clipboard. [recentlySelected] is true while
 * the editor has (or just had) a range selection, so foreign clipboard changes are never read.
 */
@Composable
internal expect fun EditorClipboardFix(recentlySelected: () -> Boolean, content: @Composable () -> Unit)

/** Null where the platform clipboard is not wired yet (the default copy runs then). */
internal expect fun plainTextClipEntry(text: String): ClipEntry?

/**
 * While [enabled] (a text field is focused), text pasted that no field took goes to [onText]. Only the browser needs it
 * (owner, 2026-10-30: Ctrl+V sometimes pasted nothing): Compose web hears a field's pastes on the one element that was
 * its input when it got focus, while a click that leaves the cursor where it was keeps the page's focus on the canvas,
 * and Ctrl+V then pastes onto another element nobody listens to. Elsewhere it does nothing.
 */
@Composable
expect fun PasteFallback(enabled: Boolean, onText: (String) -> Unit)
