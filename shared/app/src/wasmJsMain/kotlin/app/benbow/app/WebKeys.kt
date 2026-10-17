@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.app

/**
 * The app's shortcuts in the browser ([webShortcutOf]): read on the page before any element sees the key, so they work
 * wherever the focus is; a key that is a shortcut doesn't reach the browser (Ctrl+S saves a version, not the page).
 */
fun installWebShortcuts(services: AppServices) = listenKeys { code, ctrl, alt ->
    val shortcut = webShortcutOf(code, ctrl, alt) ?: return@listenKeys false
    services.shortcuts.tryEmit(shortcut)
    true
}

private fun listenKeys(onKey: (String, Boolean, Boolean) -> Boolean): Unit = js(
    """{
        document.addEventListener('keydown', e => {
            if (!e.repeat && onKey(e.code, e.ctrlKey || e.metaKey, e.altKey)) { e.preventDefault(); e.stopPropagation(); }
        }, true);
    }""",
)

/**
 * The middle button is the app's autoscroll (ADR-036 N2, [pageScrolling]): its press does nothing of the browser's own
 * (no paste on Linux, no autoscroll of its own). The app still gets the press: it listens to pointer events.
 */
fun keepMiddleButton(): Unit = js(
    """{ document.addEventListener('mousedown', e => { if (e.button === 1) e.preventDefault(); }, true); }""",
)
