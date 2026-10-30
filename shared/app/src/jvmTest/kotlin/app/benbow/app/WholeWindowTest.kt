package app.benbow.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** The viewer over the whole window (owner, 2026-10-30): the rail stays put beneath it, so nothing jumps aside. */
@OptIn(ExperimentalTestApi::class)
class WholeWindowTest {
    @Test
    fun theViewerCoversTheRailThatStaysBeneathIt() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val services = feedTestServices()
        val entry = services.repo.createNote(services.today(), "Закат на море")
        val bytes = picture()
        runBlocking {
            services.attach({ entry }, { bytes.inputStream() }) { MediaProbe("image/jpeg", "sea.jpg", "image", 1200, 900) }.join()
        }
        setContent { BenbowApp(services) }
        onAllNodesWithText("Лента")[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Вложения"), 5_000)
        onAllNodes(hasContentDescription("Вложения"))[0].performTouchInput { click(center) }
        waitUntilAtLeastOneExists(hasContentDescription("В корзину"), 5_000)
        runBlocking { delay(500) }
        waitForIdle()
        screenshot("viewer-over-rail")
        assertTrue(onAllNodesWithText("Главная").fetchSemanticsNodes().isEmpty(), "the rail beneath is out of reach")
        // Where the rail stands, the viewer's black: it starts at the window's edge, over the rail.
        val shot = onRoot().captureToImage().toPixelMap()
        assertTrue(shot[RAIL / 2, 450].luminance() < 0.05f, "the viewer over the rail: ${shot[RAIL / 2, 450]}")
        // Its back button stands over the rail too: a click there reaches it, and the feed is back, not another tab.
        onRoot().performMouseInput { click(Offset(28f, 30f)) }
        waitUntilAtLeastOneExists(hasText("Главная"), 5_000)
        waitUntilAtLeastOneExists(hasContentDescription("Фильтр"), 5_000)
    }

    private fun picture(): ByteArray {
        val image = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(0xE8, 0xA8, 0x5A)
            fillRect(0, 0, 1200, 500)
            color = Color(0x1F, 0x4E, 0x6E)
            fillRect(0, 500, 1200, 400)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private companion object {
        /** Material's rail, 80 dp wide (the test's density is 1). */
        const val RAIL = 80
    }
}
