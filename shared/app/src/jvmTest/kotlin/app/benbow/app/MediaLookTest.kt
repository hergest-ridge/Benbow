package app.benbow.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import app.benbow.core.content.AttachmentBlock
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.media.MediaLook
import app.benbow.core.text.Atom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Вложение» (owner, 2026-10-30): how an attachment shows in its post, chosen while writing. */
@OptIn(ExperimentalTestApi::class)
class MediaLookTest {
    private val services = feedTestServices()

    private fun picture(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(0xE8, 0xA8, 0x5A)
            fillRect(0, 0, width, height / 2)
            color = Color(0x1F, 0x4E, 0x6E)
            fillRect(0, height / 2, width, height - height / 2)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun attach(entry: String, width: Int, height: Int): String {
        val bytes = picture(width, height)
        runBlocking {
            services.attach({ entry }, { bytes.inputStream() }) { MediaProbe("image/jpeg", "p.jpg", "image", width, height) }.join()
        }
        return runBlocking { services.repo.entry(entry).first()!!.attachments.last().meta.id }
    }

    private fun look(entry: String, id: String) = runBlocking { services.repo.entry(entry).first()!!.mediaLooks[id] }

    @Test
    fun aPictureInTheTextIsSetToFillAroundWhatStaysInSight() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Море на закате")
        val square = attach(entry, 900, 900)
        services.repo.reviseBody(entry) { body ->
            DocumentCodec.encode(Document(DocumentCodec.decode(body).blocks + AttachmentBlock("pic", square)))
        }
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilAtLeastOneExists(hasText("Море на закате"), 5_000)
        onAllNodesWithText("Море на закате")[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Редактировать").click()
        // A tap on the picture selects it; its bubble opens «Вложение».
        waitUntilAtLeastOneExists(androidx.compose.ui.test.hasTestTag("atom:pic"), 5_000)
        onNodeWithTag("atom:pic").performClick()
        waitUntilAtLeastOneExists(hasText("Настроить"), 5_000)
        onNodeWithText("Настроить").performClick()
        waitUntilAtLeastOneExists(hasText("Вложение"), 5_000)
        onNodeWithText("Заполнить").performClick()
        waitUntil(timeoutMillis = 5_000) { look(entry, square)?.fit == MediaLook.Fit.Fill }
        waitForIdle()
        screenshot("media-look-fill")
        // The whole picture under it: a tap says what stays in sight.
        waitUntilAtLeastOneExists(hasText("Нажмите на картинку", substring = true), 5_000)
        onNodeWithTag("media-focus").performTouchInput { click(Offset(width * 0.1f, height * 0.2f)) }
        waitUntil(timeoutMillis = 5_000) { look(entry, square)?.focus?.let { it.x < 0.2f && it.y < 0.3f } == true }
        onNodeWithText("Целиком").performClick()
        waitUntil(timeoutMillis = 5_000) { look(entry, square)?.fit == MediaLook.Fit.Whole }
        onNodeWithText("По размеру").performClick()
        waitUntil(timeoutMillis = 5_000) { look(entry, square)?.fit == null || look(entry, square)?.fit == MediaLook.Fit.Natural }
    }

    /** An attachment outside the text (owner, 2026-10-30): ✕ in its corner, asked once, into the trash. */
    @Test
    fun anAttachmentOutsideTheTextGoesWithItsCross() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Море на закате")
        val loose = attach(entry, 1200, 800)
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilAtLeastOneExists(hasText("Море на закате"), 5_000)
        onAllNodesWithText("Море на закате")[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Редактировать").click()
        waitUntilAtLeastOneExists(hasContentDescription("Убрать вложение"), 5_000)
        onNodeWithContentDescription("Убрать вложение").performClick()
        waitUntilAtLeastOneExists(hasText("Убрать вложение в корзину", substring = true), 5_000)
        waitForIdle()
        screenshot("media-strip-remove")
        onNodeWithText("Убрать").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.entry(entry).first()!!.attachments.none { it.meta.id == loose } } }
    }

    /** Owner, 2026-10-30: a picture in the text held by a scrapbook's frame, laid anew by «Перемешать». */
    @Test
    fun aPictureInTheTextTakesAScrapbooksFrame() = runDesktopComposeUiTest(width = 412, height = 1000) {
        val entry = services.repo.createNote(services.today(), "Море на закате")
        val sea = attach(entry, 1200, 800)
        services.repo.reviseBody(entry) { body ->
            DocumentCodec.encode(Document(DocumentCodec.decode(body).blocks + AttachmentBlock("pic", sea)))
        }
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilAtLeastOneExists(hasText("Море на закате"), 5_000)
        onAllNodesWithText("Море на закате")[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Редактировать").click()
        waitUntilAtLeastOneExists(androidx.compose.ui.test.hasTestTag("atom:pic"), 5_000)
        onNodeWithTag("atom:pic").performClick()
        waitUntilAtLeastOneExists(hasText("Настроить"), 5_000)
        onNodeWithText("Настроить").performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Скотч"), 5_000)
        onNodeWithContentDescription("Скотч").performClick()
        waitUntil(timeoutMillis = 5_000) { look(entry, sea)?.frame == "tape" }
        onNodeWithText("Перемешать").performClick()
        waitUntil(timeoutMillis = 5_000) { look(entry, sea)?.frame?.startsWith("tape.") == true }
        waitForIdle()
        screenshot("media-frame-panel")
        onNodeWithContentDescription("Полароид").performClick()
        waitUntil(timeoutMillis = 5_000) { look(entry, sea)?.frame == "polaroid" }
    }

    /** Owner, 2026-10-30: one outside the text pulled down out of the strip and carried into the text goes there. */
    @Test
    fun anAttachmentCarriedOutOfTheStripGoesIntoTheText() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Море на закате\nВолны шли с запада.\nПотом стемнело.")
        attach(entry, 1200, 800)
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilAtLeastOneExists(hasText("Море на закате"), 5_000)
        onAllNodesWithText("Море на закате")[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Редактировать").click()
        waitUntilAtLeastOneExists(hasContentDescription("p.jpg"), 5_000)
        assertTrue(onAllNodesWithText("в тексте").fetchSemanticsNodes().isEmpty(), "outside the text at first")
        onNodeWithContentDescription("p.jpg").performTouchInput {
            down(center)
            advanceEventTime(900)
            repeat(16) {
                moveBy(Offset(0f, 16f))
                advanceEventTime(16)
            }
        }
        waitForIdle()
        screenshot("media-carry")
        onAllNodes(hasContentDescription("p.jpg"))[0].performTouchInput { up() }
        // In the text now, after its words (the strip, all of its attachments placed, goes).
        waitUntilAtLeastOneExists(inTheText, 5_000)
        waitForIdle()
        screenshot("media-carried")
    }

    /** An atom of the editor's text (BenbowEditor tags each by its paragraph). */
    private val inTheText = SemanticsMatcher("an atom in the text") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atom:") == true
    }

    /** By its own shape a square stands as tall as it is wide (owner: squares were cut); filling, a 4:3 frame. */
    @Test
    fun aSquareStandsWholeByItsOwnShape() {
        val entry = services.repo.createNote(services.today(), "Квадрат")
        val id = attach(entry, 1000, 1000)
        val attachments = runBlocking { services.repo.entry(entry).first()!!.attachments }
        val natural = EntryPictures(services, attachments, onOpen = {}, tallest = 700.dp)
        assertEquals(608.dp, natural.pictureHeight(Atom.Attachment(id), 600.dp), "as tall as wide, and the air around")
        assertEquals(708.dp, natural.pictureHeight(Atom.Attachment(id), 900.dp), "no taller than most of the window")
        val filled = EntryPictures(services, attachments, onOpen = {}, looks = mapOf(id to MediaLook(MediaLook.Fit.Fill)))
        assertEquals(458.dp, filled.pictureHeight(Atom.Attachment(id), 600.dp), "a 4:3 frame")
        assertTrue(filled.pictureHeight(Atom.Attachment(id), 900.dp) <= 528.dp)
    }
}
