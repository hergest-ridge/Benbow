package app.benbow.core.text

import app.benbow.core.media.PictureSize

/** One GET of [url] that doesn't follow redirects; at most [maxBytes] of the body are read. */
fun interface PageGet {
    suspend fun get(url: String, maxBytes: Int, accept: String): PageResponse
}

class PageResponse(val status: Int, val location: String?, val contentType: String?, val body: ByteArray)

/**
 * A page read for its card (ADR-038): its final address, what it says, and its picture's bytes when it has one. When the
 * address is a picture itself (or a GIF site's page), [own] is that file as it came: it goes under the link as an
 * attachment (ADR-045).
 */
class PageRead(val url: String, val meta: PageMeta, val image: ByteArray?, val own: PagePicture? = null)

/** A picture as the site gave it (a GIF stays a GIF), with its [mime] type and its size in pixels. */
class PagePicture(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

/** What is known of a picture on its way, before it is all here: its type and, once its first bytes came, its size. */
class PictureHint(val mime: String, val width: Int? = null, val height: Int? = null)

/**
 * Reads a page the way its card needs it, the same on the phone, the desktop and the server (which passes [allowed] to
 * keep its own network out of reach): at most [REDIRECTS] redirects, [HTML_MAX] of HTML, a picture up to [IMAGE_MAX]
 * (the page's own, else its touch icon). An address that is a picture, or a page of a GIF site (owner: GIFs by their
 * links didn't show), gives its picture whole, up to [PICTURE_MAX]; [onPicture] hears of it as soon as that is sure,
 * before the whole file comes. [decode] turns bytes into text in the named charset (UTF-8 when null).
 */
object PageReader {
    const val HTML_MAX = 512 * 1024
    const val IMAGE_MAX = 5 * 1024 * 1024
    const val PICTURE_MAX = 25 * 1024 * 1024
    const val REDIRECTS = 5
    private const val HTML = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5"

    // JPEG and PNG first: the desktop decodes nothing else (a server that can choose gives one of them).
    private const val IMAGE = "image/jpeg,image/png;q=0.9,image/webp;q=0.8,image/*;q=0.5"

    // A picture kept whole is shown by the app's own decoders: a GIF stays one.
    private const val PICTURE = "image/gif,image/jpeg,image/png,image/webp;q=0.9,image/*;q=0.5"

    suspend fun read(
        href: String,
        get: PageGet,
        decode: (ByteArray, String?) -> String,
        allowed: (String) -> Boolean = {
            true
        },
        onPicture: (PictureHint) -> Unit = {},
    ): PageRead? {
        val (url, page) = follow(href, get, HTML_MAX, HTML, allowed) ?: return null
        if (page.status !in 200..299) return null
        val type = page.contentType?.lowercase().orEmpty()
        if (type.startsWith("image/")) {
            if ("svg" in type) return PageRead(url, PageMeta(), null)
            // The first bytes are here already: their size says how big a place the picture will take.
            val size = PictureSize.of(page.body)
            onPicture(PictureHint(type.substringBefore(';').trim(), size?.first, size?.second))
            val own = whole(url, get, allowed)
            return PageRead(url, PageMeta(), own?.bytes?.takeIf { it.size <= IMAGE_MAX } ?: picture(url, get, allowed), own)
        }
        if (type.isNotEmpty() && "html" !in type && "xml" !in type) return PageRead(url, PageMeta(), null)
        val head = page.body.copyOfRange(0, minOf(page.body.size, 4096)).decodeToString()
        val charset = Regex("""charset=([^\s;]+)""").find(type)?.groupValues?.get(1)?.trim('"', '\'') ?: PageMeta.charsetIn(head)
        val meta = PageMeta.read(decode(page.body, charset), url)
        val gif = if (Links.isGifSite(url)) {
            meta.image?.let {
                onPicture(PictureHint("image/gif"))
                whole(it, get, allowed)
            }?.takeIf { it.mime == "image/gif" }
        } else {
            null
        }
        val image = gif?.bytes?.takeIf { it.size <= IMAGE_MAX }
            ?: meta.image?.let { picture(it, get, allowed) } ?: meta.icon?.let { picture(it, get, allowed) }
        return PageRead(url, meta, image, gif)
    }

    private suspend fun picture(url: String, get: PageGet, allowed: (String) -> Boolean): ByteArray? {
        val (_, response) = follow(url, get, IMAGE_MAX, IMAGE, allowed) ?: return null
        val type = response.contentType?.lowercase().orEmpty()
        return response.body.takeIf { response.status in 200..299 && type.startsWith("image/") && "svg" !in type && it.isNotEmpty() }
    }

    /** [url]'s picture as it is, when it is one whole (not cut at [PICTURE_MAX]) whose size can be read. */
    private suspend fun whole(url: String, get: PageGet, allowed: (String) -> Boolean): PagePicture? {
        val (_, response) = follow(url, get, PICTURE_MAX + 1, PICTURE, allowed) ?: return null
        val mime = response.contentType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val bytes = response.body
        if (response.status !in 200..299 || !mime.startsWith("image/") || "svg" in mime || bytes.size !in 1..PICTURE_MAX) return null
        val (width, height) = PictureSize.of(bytes) ?: return null
        return PagePicture(bytes, mime, width, height)
    }

    private suspend fun follow(
        start: String,
        get: PageGet,
        maxBytes: Int,
        accept: String,
        allowed: (String) -> Boolean,
    ): Pair<String, PageResponse>? {
        var url = start
        repeat(REDIRECTS + 1) {
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
            if (!allowed(url)) return null
            val response = get.get(url, maxBytes, accept)
            val next = response.location?.takeIf { response.status in 300..399 } ?: return url to response
            url = PageMeta.resolve(url, next) ?: return null
        }
        return null
    }
}
