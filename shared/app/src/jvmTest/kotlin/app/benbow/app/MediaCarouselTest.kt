package app.benbow.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.Hlc
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test

/** A post's carousel while its pictures change under it. */
@OptIn(ExperimentalTestApi::class)
class MediaCarouselTest {
    private val services = feedTestServices()

    /** A generated picture of its own colour, stored like a captured one. */
    private fun picture(n: Int): AttachmentState {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, n * 0x111111) }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val file = runBlocking { services.media.import(png.inputStream(), "image/png", "p$n.png", null) }
        val meta = AttachmentMeta("a$n", file.sha256, file.size, file.mime, "image", width = 40, height = 30)
        return AttachmentState(meta, position = n.toDouble(), hlc = Hlc(n.toLong(), 0, "test"))
    }

    /** 2026-10-18 and 19 on the owner's phone: a second item on a post crashed the app (IndexOutOfBounds in the pager's keys). */
    @Test
    fun itemsAddedAndRemovedUnderTheCarousel() = runDesktopComposeUiTest(width = 412, height = 860) {
        val (one, two, three) = listOf(picture(1), picture(2), picture(3))
        var items by mutableStateOf(listOf(one))
        setContent { MediaCarousel(services.media, items, onOpen = {}) }
        waitForIdle()

        items = listOf(one, two)
        waitForIdle()
        onNodeWithText("1/2").assertExists()

        items = listOf(one, two, three)
        waitForIdle()
        onNodeWithText("1/3").assertExists()

        items = listOf(three)
        waitForIdle()
        onNodeWithText("1/3").assertDoesNotExist()
    }
}
