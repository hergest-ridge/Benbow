package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import app.benbow.client.MediaFile
import app.benbow.client.library
import app.benbow.client.takenOff
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The trash with media (ADR-041, 7.18 M3): weights, ages, the order, «Вложения», and taking one off with «Вернуть». */
@OptIn(ExperimentalTestApi::class)
class TrashScreenTest {
    private val services = feedTestServices()
    private val repo = services.repo

    /** A picture of one colour, [noise] bytes of grain making it heavier. */
    private fun picture(n: Int, noise: Int = 0): MediaFile {
        val image = BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.getHSBColor(n / 7f, 0.5f, 0.8f)
            fillRect(0, 0, 160, 120)
            dispose()
        }
        val random = Random(n)
        repeat(noise) { image.setRGB(random.nextInt(160), random.nextInt(120), random.nextInt()) }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return runBlocking { services.media.import(png.inputStream(), "image/png", "p$n.png", null) }
    }

    @Test
    fun theTrashWeighsItsEntriesListsTakenOffAttachmentsAndPutsThemBack() = runDesktopComposeUiTest(width = 412, height = 860) {
        val light = repo.createNote(services.today(), "Лёгкая запись")
        repo.attach(light, picture(1), "image", 160, 120)
        val heavy = repo.createNote(services.today(), "Тяжёлая запись")
        repo.attach(heavy, picture(2, noise = 4_000), "image", 160, 120)
        repo.moveToTrash(heavy) // the heavy one first, so «Недавние» puts the light one on top
        repo.moveToTrash(light)
        val kept = repo.createNote(services.today(), "Живая запись")
        val off = repo.attach(kept, picture(3), "image", 160, 120)
        repo.removeAttachment(kept, off)

        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Корзина").click()
        waitUntilAtLeastOneExists(hasText("Файлы в корзине:", substring = true), 5_000)
        waitUntilExactlyOneExists(hasText("Вложения"), 5_000)
        waitUntilExactlyOneExists(hasText("p3.png"), 5_000)
        waitUntilAtLeastOneExists(hasText("сегодня", substring = true), 5_000)
        val top = { name: String -> onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Лёгкая запись") < top("Тяжёлая запись"), "the latest thrown away first")
        screenshot("trash-01-latest")
        onNodeWithText("Тяжёлые").performClick()
        waitForIdle()
        assertTrue(top("Тяжёлая запись") < top("Лёгкая запись"), "the heaviest first")
        screenshot("trash-02-heaviest")

        // The attachment goes back on its entry, which keeps living.
        onAllNodesWithText("Восстановить")[2].performClick()
        waitUntilDoesNotExist(hasText("p3.png"), 5_000)
        assertEquals(emptyList(), runBlocking { repo.takenOff().first() })
        assertEquals(listOf(kept), runBlocking { repo.library().first() }.map { it.postId })
    }

    @Test
    fun takingAPictureOffInItsViewerAsksNothingAndComesBackFromTheSnackbar() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = repo.createNote(services.today(), "Два снимка")
        repo.attach(entry, picture(4), "image", 160, 120)
        repo.attach(entry, picture(5), "image", 160, 120)
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilExactlyOneExists(hasText("1/2"), 5_000)
        onNodeWithContentDescription("Вложения").performTouchInput { click(center) }
        waitUntilExactlyOneExists(hasText("1 / 2"), 5_000)
        onNodeWithContentDescription("В корзину").performClick()
        waitUntilExactlyOneExists(hasText("Вложение в корзине"), 5_000)
        waitUntil(timeoutMillis = 5_000) { runBlocking { repo.takenOff().first() }.size == 1 }
        screenshot("trash-03-viewer-snackbar")
        onNodeWithText("Вернуть").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { repo.takenOff().first() }.isEmpty() }
        waitUntilExactlyOneExists(hasText(" / 2", substring = true), 5_000) // both pages again
    }
}
