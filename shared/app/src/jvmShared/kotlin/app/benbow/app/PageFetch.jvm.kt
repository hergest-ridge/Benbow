package app.benbow.app

import app.benbow.core.text.PageGet
import app.benbow.core.text.PageReader
import app.benbow.core.text.PageResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

/** The phone and the desktop read a card's page themselves (ADR-038): the server may be out of reach away from home. */
internal actual suspend fun readPage(services: AppServices, href: String): LinkPreview? = withContext(Dispatchers.IO) {
    val read = PageReader.read(href, DirectGet, ::decodePage) ?: return@withContext null
    val jpeg = read.image?.let { coverJpeg(it) }
    LinkPreview(read.meta.title, read.meta.description, read.meta.site, jpeg?.let { services.keepPicture(it) })
}

private object DirectGet : PageGet {
    override suspend fun get(url: String, maxBytes: Int, accept: String): PageResponse =
        withContext(Dispatchers.IO) { fetchPage(url, maxBytes, accept) }
}

/** One GET without cookies or redirects; at most [maxBytes] of the body. */
internal fun fetchPage(url: String, maxBytes: Int, accept: String): PageResponse {
    val connection = URL(asciiUrl(url)).openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", PREVIEW_AGENT)
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Language", "ru,en;q=0.8")
        val status = connection.responseCode
        val body = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { readAtMost(it, maxBytes) } ?: ByteArray(0)
        return PageResponse(status, connection.getHeaderField("Location"), connection.contentType, body)
    } finally {
        connection.disconnect()
    }
}

/** Sites tell link previews apart by this; many give their cards' words only to agents like it. */
internal const val PREVIEW_AGENT = "Mozilla/5.0 (compatible; Benbow link preview; like TelegramBot and Twitterbot)"
private const val TIMEOUT_MS = 5_000

private fun readAtMost(input: InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** An address as HTTP needs it: Cyrillic paths percent-encoded, a Cyrillic domain in punycode. */
internal fun asciiUrl(href: String): String {
    val uri =
        runCatching { URI(href) }.getOrNull()
            ?: URL(href).let { URI(it.protocol, it.userInfo, it.host, it.port, it.path, it.query, it.ref) }
    val host = uri.host ?: return uri.toASCIIString()
    val ascii = IDN.toASCII(host)
    return if (ascii ==
        host
    ) {
        uri.toASCIIString()
    } else {
        URI(uri.scheme, uri.userInfo, ascii, uri.port, uri.path, uri.query, uri.fragment).toASCIIString()
    }
}

private fun decodePage(bytes: ByteArray, charset: String?): String {
    val cs = charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    return String(bytes, cs)
}
