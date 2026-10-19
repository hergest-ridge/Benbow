package app.benbow.core.text

/** One GET of [url] that doesn't follow redirects; at most [maxBytes] of the body are read. */
fun interface PageGet {
    suspend fun get(url: String, maxBytes: Int, accept: String): PageResponse
}

class PageResponse(val status: Int, val location: String?, val contentType: String?, val body: ByteArray)

/** A page read for its card (ADR-038): its final address, what it says, and its picture's bytes when it has one. */
class PageRead(val url: String, val meta: PageMeta, val image: ByteArray?)

/**
 * Reads a page the way its card needs it, the same on the phone, the desktop and the server (which passes [allowed] to
 * keep its own network out of reach): at most [REDIRECTS] redirects, [HTML_MAX] of HTML, a picture up to [IMAGE_MAX]
 * (the page's own, else its touch icon). [decode] turns bytes into text in the named charset (UTF-8 when null).
 */
object PageReader {
    const val HTML_MAX = 512 * 1024
    const val IMAGE_MAX = 5 * 1024 * 1024
    const val REDIRECTS = 5
    private const val HTML = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5"

    // JPEG and PNG first: the desktop decodes nothing else (a server that can choose gives one of them).
    private const val IMAGE = "image/jpeg,image/png;q=0.9,image/webp;q=0.8,image/*;q=0.5"

    suspend fun read(
        href: String,
        get: PageGet,
        decode: (ByteArray, String?) -> String,
        allowed: (String) -> Boolean = {
            true
        },
    ): PageRead? {
        val (url, page) = follow(href, get, HTML_MAX, HTML, allowed) ?: return null
        if (page.status !in 200..299) return null
        val type = page.contentType?.lowercase().orEmpty()
        if (type.startsWith("image/")) return PageRead(url, PageMeta(), picture(url, get, allowed))
        if (type.isNotEmpty() && "html" !in type && "xml" !in type) return PageRead(url, PageMeta(), null)
        val head = page.body.copyOfRange(0, minOf(page.body.size, 4096)).decodeToString()
        val charset = Regex("""charset=([^\s;]+)""").find(type)?.groupValues?.get(1)?.trim('"', '\'') ?: PageMeta.charsetIn(head)
        val meta = PageMeta.read(decode(page.body, charset), url)
        val image = meta.image?.let { picture(it, get, allowed) } ?: meta.icon?.let { picture(it, get, allowed) }
        return PageRead(url, meta, image)
    }

    private suspend fun picture(url: String, get: PageGet, allowed: (String) -> Boolean): ByteArray? {
        val (_, response) = follow(url, get, IMAGE_MAX, IMAGE, allowed) ?: return null
        val type = response.contentType?.lowercase().orEmpty()
        return response.body.takeIf { response.status in 200..299 && type.startsWith("image/") && "svg" !in type && it.isNotEmpty() }
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
