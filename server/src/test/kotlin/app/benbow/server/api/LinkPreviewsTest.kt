package app.benbow.server.api

import app.benbow.core.text.PageMeta
import app.benbow.core.text.PagePicture
import app.benbow.core.text.PageRead
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** The server reads pages for the web client's link cards (ADR-038), never inside the home network. */
class LinkPreviewsTest {
    @Test
    fun theHomeNetworkIsOutOfReach() {
        listOf(
            "http://127.0.0.1/",
            "https://localhost:8765/api/v1/info",
            "http://192.168.1.1/",
            "http://10.0.0.5/admin",
            "http://172.16.3.4/",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/",
            "http://[::1]/",
            "http://[fd12:3456::1]/",
            "http://0.0.0.0/",
        ).forEach { assertFalse(LinkPreviews.isPublic(it), it) }
    }

    @Test
    fun theSocketItselfRefusesHomeAddresses() {
        // As if a name had passed the check and then turned to a home address (DNS rebinding): this page is never reached.
        val accepted = AtomicInteger()
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        server.accept().use { socket ->
                            accepted.incrementAndGet()
                            val request = socket.getInputStream().bufferedReader()
                            while (!request.readLine().isNullOrEmpty()) Unit
                            socket.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi".toByteArray(),
                            )
                        }
                    }
                }
            }
            listOf("http://127.0.0.1:${server.localPort}/", "http://localhost:${server.localPort}/").forEach { url ->
                assertFailsWith<IOException>(url) { LinkPreviews.fetch(url, 1024, "*/*") }
            }
        }
        assertEquals(0, accepted.get())
    }

    @Test
    fun aBigPictureBecomesASmallJpeg() {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2400, 1260, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val jpeg = assertNotNull(LinkPreviews.jpeg(png))
        val image = ImageIO.read(jpeg.inputStream())
        assertEquals(1024, image.width)
        assertEquals(537, image.height)
        assertEquals(null, LinkPreviews.jpeg(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun anAddressThatIsAPictureTellsTheBrowserItsTypeAndSize() {
        // Owner, 0.15.0: a picture link came to the browser as a plain card, its type left out of the answer.
        val gif = "GIF89a".encodeToByteArray() + byteArrayOf(0xE0.toByte(), 0x01, 0x0E, 0x01, 0, 0, 0)
        val kept = ArrayList<String>()
        val read = PageRead("https://x.org/cat.gif", PageMeta(), gif, PagePicture(gif, "image/gif", 480, 270))
        val info = LinkPreviews.infoOf(read) { _, mime -> "a".repeat(64).also { kept += mime } }
        assertEquals(Triple("image/gif", 480, 270), Triple(info.mime, info.width, info.height))
        assertEquals(listOf("image/gif"), kept, "kept as it came, no JPEG made of it")
        // Not kept: a plain card, and nothing claims to be the picture.
        val plain = LinkPreviews.infoOf(read) { _, _ -> null }
        assertEquals(null, plain.mime)
        assertEquals(null, plain.image)
    }
}
