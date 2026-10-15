@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * The address shows the screen (#/feed, #/entry/<id>) and the browser's Back (the button, Alt+←, a mouse's side
 * button) goes back in the app. While the app has somewhere to go back to, one extra history entry stands in front of
 * the page's own: Back lands on the page's entry, the app steps back, and the extra one is put back if there is still
 * more to go back to. At the app's first screen, Back leaves the page as usual.
 */
@Composable
internal actual fun PlatformAddress(backStack: List<Route>, nav: Navigator, open: (Link) -> Unit) {
    val stack by rememberUpdatedState(backStack)
    val state = remember { AddressState() }
    DisposableEffect(nav) {
        linkOf(currentHash().removePrefix("#"))?.let(open)
        val listener = onPopState {
            val hash = currentHash()
            when {
                state.ignoreNext -> {
                    state.ignoreNext = false
                    replaceAddress(address(stack.last()))
                }

                // Back onto the page's own entry: the app steps back.
                state.armed && hash == state.base -> {
                    state.armed = false
                    if (stack.size > 1) nav.back() else replaceAddress(address(stack.last()))
                }

                // Any other address (typed, a bookmark, Forward): go where it points.
                else -> {
                    state.armed = false
                    val link = linkOf(hash.removePrefix("#"))
                    if (link != null) open(link) else replaceAddress(address(stack.last()))
                }
            }
        }
        onDispose { removePopState(listener) }
    }
    LaunchedEffect(nav) {
        snapshotFlow { backStack.toList() }.collect { routes ->
            val here = address(routes.last())
            when {
                routes.size > 1 && !state.armed -> {
                    state.base = currentHash()
                    pushAddress(here)
                    state.armed = true
                }

                routes.size == 1 && state.armed -> {
                    // Back in the app to its first screen: take the extra entry away, then show the first screen's address.
                    state.armed = false
                    state.ignoreNext = true
                    historyBack()
                }

                else -> replaceAddress(here)
            }
        }
    }
}

private class AddressState {
    /** The page's own entry's address, under the extra one: coming back to it is Back. */
    var base = ""
    var armed = false
    var ignoreNext = false
}

private fun address(route: Route) = "#/" + route.path()

private fun currentHash(): String = js("window.location.hash || ''")

private fun pushAddress(url: String): Unit = js("{ history.pushState({ benbow: true }, '', url); }")

private fun replaceAddress(url: String): Unit = js("{ history.replaceState(history.state, '', url); }")

private fun historyBack(): Unit = js("{ history.back(); }")

private fun onPopState(block: () -> Unit): JsAny =
    js("(() => { const f = () => block(); window.addEventListener('popstate', f); return f; })()")

private fun removePopState(listener: JsAny): Unit = js("{ window.removeEventListener('popstate', listener); }")
