package app.benbow.server.api

import app.benbow.core.api.LinkPreviewInfo
import app.benbow.core.text.PageGet
import app.benbow.core.text.PageReader
import app.benbow.core.text.PageResponse
import app.benbow.server.storage.BlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.net.SocketFactory

/**
 * Reads pages for the web client's link cards (ADR-038): the browser's page reaches nothing but this server (ADR-030).
 * Only public addresses: a paired device can't use the server to look into the home network. Each address is checked
 * twice: by name before every request, and as the socket connects, so a name that turns to a home address in between
 * (DNS rebinding) gets nowhere. The page's picture is kept as a JPEG blob, which the client then shows like any other.
 */
internal class LinkPreviews(private val blobs: BlobStore?) {
    suspend fun read(href: String, deviceId: String?): LinkPreviewInfo? = withContext(Dispatchers.IO) {
        val read = PageReader.read(href, Direct, ::decode, ::isPublic) ?: return@withContext null
        // An address that is a picture keeps it as it came (a GIF stays a GIF), with its size; a page's picture is a JPEG.
        read.own?.let { own ->
            val image = keep(own.bytes, deviceId, own.mime) ?: return@let null
            return@withContext LinkPreviewInfo(
                read.url,
                read.meta.title,
                read.meta.description,
                read.meta.site,
                image,
                own.width,
                own.height,
            )
        }
        val image = read.image?.let(::jpeg)?.let { keep(it, deviceId) }
        LinkPreviewInfo(read.url, read.meta.title, read.meta.description, read.meta.site, image)
    }

    private fun keep(bytes: ByteArray, deviceId: String?, mime: String = "image/jpeg"): String? {
        val store = blobs ?: return null
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val name = "link." + when (mime) {
            "image/gif" -> "gif"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return runCatching {
            when (val start = store.start(sha256, bytes.size.toLong(), mime, name, System.currentTimeMillis(), deviceId)) {
                is BlobStore.Start.Exists -> sha256

                // A picture kept whole may be bigger than one chunk.
                is BlobStore.Start.Upload -> {
                    var offset = start.offset
                    var done = false
                    while (offset < bytes.size) {
                        val end = minOf(bytes.size.toLong(), offset + BlobStore.MAX_CHUNK).toInt()
                        val chunk =
                            store.append(start.id, offset, bytes.copyOfRange(offset.toInt(), end)) as? BlobStore.Chunk.Accepted ?: break
                        offset = chunk.offset
                        done = chunk.blob != null
                    }
                    sha256.takeIf { done }
                }
            }
        }.getOrNull()
    }

    private object Direct : PageGet {
        override suspend fun get(url: String, maxBytes: Int, accept: String): PageResponse =
            withContext(Dispatchers.IO) { fetch(url, maxBytes, accept) }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val SIDE = 1024
        private const val AGENT = "Mozilla/5.0 (compatible; Benbow link preview; like TelegramBot and Twitterbot)"

        // No proxy: the socket goes straight to the address it checks. A whole GET may take three timeouts, no more.
        private val client =
            OkHttpClient.Builder()
                .socketFactory(PublicSockets)
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout(3L * TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

        /** Whether every address [url]'s host has is out on the internet (not this machine, the LAN or a link-local one). */
        fun isPublic(url: String): Boolean {
            val host = runCatching { URI(ascii(url)).host }.getOrNull()?.trim('[', ']') ?: return false
            val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
            return addresses.isNotEmpty() && addresses.all(::isPublicAddress)
        }

        private fun isPublicAddress(a: InetAddress): Boolean = !(
            a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress ||
                (a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC) || // unique local fc00::/7
                (a.address.size == 4 && a.address[0].toInt() == 100 && (a.address[1].toInt() and 0xC0) == 64) // CGNAT 100.64/10
            )

        internal fun fetch(url: String, maxBytes: Int, accept: String): PageResponse {
            val request =
                Request.Builder()
                    .url(ascii(url))
                    .header("User-Agent", AGENT)
                    .header("Accept", accept)
                    .header("Accept-Language", "ru,en;q=0.8")
                    .build()
            client.newCall(request).execute().use { response ->
                val body = response.body.byteStream().use { readAtMost(it, maxBytes) }
                return PageResponse(response.code, response.header("Location"), response.header("Content-Type"), body)
            }
        }

        /** Sockets that connect only to public addresses, whatever the host's name said when it was checked. */
        private object PublicSockets : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    val address = (endpoint as? InetSocketAddress)?.address
                    if (address == null || !isPublicAddress(address)) throw ConnectException("Not a public address: $endpoint")
                    super.connect(endpoint, timeout)
                }
            }

            override fun createSocket(host: String, port: Int): Socket = createSocket(InetAddress.getByName(host), port)

            override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                createSocket(InetAddress.getByName(host), port, localHost, localPort)

            override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply {
                connect(InetSocketAddress(host, port))
            }

            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                createSocket().apply {
                    bind(InetSocketAddress(localAddress, localPort))
                    connect(InetSocketAddress(address, port))
                }
        }

        private fun readAtMost(input: InputStream, max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < max) {
                val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /** Cyrillic paths percent-encoded, a Cyrillic domain in punycode. */
        private fun ascii(href: String): String {
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

        private fun decode(bytes: ByteArray, charset: String?): String =
            String(bytes, charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8)

        /** The picture as a JPEG at most [SIDE] px across (transparency on white), or null when it can't be read. */
        fun jpeg(image: ByteArray): ByteArray? = runCatching {
            val source = ImageIO.read(ByteArrayInputStream(image)) ?: return null
            val scale = minOf(1.0, SIDE.toDouble() / maxOf(source.width, source.height))
            val w = maxOf(1, (source.width * scale).toInt())
            val h = maxOf(1, (source.height * scale).toInt())
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(source, 0, 0, w, h, null)
            g.dispose()
            val writer = ImageIO.getImageWritersByFormatName("jpg").next()
            val bytes = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(bytes).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.85f
                }
                writer.write(null, IIOImage(out, null, null), params)
                writer.dispose()
            }
            bytes.toByteArray()
        }.getOrNull()
    }
}
