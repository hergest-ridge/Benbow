package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** A link opens in a new tab that knows nothing of this one: no opener, and no referrer naming the server (ADR-038). */
@Composable
internal actual fun rememberOpenUrl(): (String) -> Unit = remember { { href -> openTab(href) } }

private fun openTab(url: String): Unit = js("{ window.open(url, '_blank', 'noopener,noreferrer'); }")

/** The page reaches nothing but its server (ADR-030), so the server reads the page and keeps its picture. */
internal actual suspend fun readPage(services: AppServices, href: String): LinkPreview? {
    val info = services.pages.preview(href)
    return LinkPreview(info.title, info.description, info.site, info.image, info.width, info.height)
}

/** A page may read the clipboard only on a paste (Firefox allows nothing else): the address field takes one. */
@Composable
internal actual fun rememberReadClipboard(): (suspend () -> String?)? = null
