package app.benbow.server.api

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
