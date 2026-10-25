package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.benbow.client.readBytes

/** A link opens in a new tab that knows nothing of this one: no opener, and no referrer naming the server (ADR-038). */
@Composable
internal actual fun rememberOpenUrl(): (String) -> Unit = remember { { href -> openTab(href) } }

private fun openTab(url: String): Unit = js("{ window.open(url, '_blank', 'noopener,noreferrer'); }")

/**
 * The page reaches nothing but its server (ADR-030), so the server reads the page and keeps its picture. An address that
 * is a picture comes back as that picture, kept by the server as it came: fetched here to go under its link (ADR-045).
 */
internal actual suspend fun readPage(services: AppServices, href: String): LinkPreview? {
    val info = services.pages.preview(href)
    val image = info.image
    val mime = info.mime
    val width = info.width
    val height = info.height
    if (image != null && mime != null && width != null && height != null) {
        services.pictureComing(href, width, height, mime == "image/gif")
        val bytes = services.media.file(image).readBytes()
        return LinkPreview(info.title, info.description, info.site, null, services.storeLinkPicture(info.url, bytes, mime, width, height))
    }
    return LinkPreview(info.title, info.description, info.site, image)
}

/** A page may read the clipboard only on a paste (Firefox allows nothing else): the address field takes one. */
@Composable
internal actual fun rememberReadClipboard(): (suspend () -> String?)? = null
