package app.benbow.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import app.benbow.client.LibraryItem
import app.benbow.client.LibrarySection
import app.benbow.client.MediaFile
import app.benbow.client.Veil
import app.benbow.client.library
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.Hlc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Ещё → Медиатека» (ADR-041, 7.18 M2): the grid by month, Бережно, the viewer, choosing and the trash, the sections. */
@OptIn(ExperimentalTestApi::class)
class LibraryScreenTest {
    private val services = feedTestServices()
    private val repo = services.repo
    private val today = LocalDate.parse(services.today())

    /** A picture of one colour, stored as a captured one. */
    private fun picture(n: Int): MediaFile {
        val image = BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.getHSBColor(n / 9f, 0.45f, 0.85f)
            fillRect(0, 0, 120, 90)
            dispose()
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return runBlocking { services.media.import(png.inputStream(), "image/png", "p$n.png", null) }
    }

    private fun bytes(name: String, mime: String, size: Int) =
        runBlocking { services.media.import(Random(name.hashCode()).nextBytes(size).inputStream(), mime, name, null) }

    private fun day(back: Int) = today.minus(back, DateTimeUnit.DAY).toString()

    /** Two entries this month (one under the curtain), one quiet, one a month and more back; a voice note and a file. */
    private fun seed() {
        val now = repo.createNote(day(0), "Сегодня у моря")
        (1..4).forEach { repo.attach(now, picture(it), "image", 120, 90) }
        repo.attach(now, bytes("voice-1.m4a", "audio/mp4", 3_000), "voice", durationMs = 12_000)
        val curtained = repo.createNote(day(1), "Тяжёлый день")
        repo.attach(curtained, picture(5), "image", 120, 90)
        repo.setVeil(curtained, Veil.Curtain)
        val quiet = repo.createNote(day(2), "Не сейчас")
        repo.attach(quiet, picture(6), "image", 120, 90)
        repo.setVeil(quiet, Veil.Quiet)
        val old = repo.createNote(day(40), "Осенний лес")
        (7..8).forEach { repo.attach(old, picture(it), "image", 120, 90) }
        repo.attach(old, bytes("Билеты.pdf", "application/pdf", 90_000), "file")
    }

    private fun ComposeUiTest.openLibrary() {
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Медиатека").click()
        waitUntilAtLeastOneExists(hasText("Фото и видео"), 5_000)
    }

    private fun shown(): List<LibraryItem> = runBlocking { repo.library().first() }

    @Test
    fun picturesShowByMonthWithTheirWeightTheQuietOnesNotAtAll() = runDesktopComposeUiTest(width = 412, height = 860) {
        seed()
        openLibrary()
        waitUntilAtLeastOneExists(hasContentDescription("p1.png"), 5_000)
        waitUntilAtLeastOneExists(hasText("Всего: 7", substring = true), 5_000)
        assertEquals(0, onAllNodes(hasContentDescription("p6.png")).fetchSemanticsNodes().size, "a quiet entry's picture")
        assertEquals(1, onAllNodes(hasText("Открыть")).fetchSemanticsNodes().size, "the curtained one")
        screenshot("library-01-grid")
        // A tap on the curtain opens it in place.
        onNodeWithContentDescription("p5.png").performClick()
        waitUntilDoesNotExist(hasText("Открыть"), 5_000)
        screenshot("library-02-curtain-open")
    }

    @Test
    fun theViewerGoesThroughTheLibraryAndOpensAnEntry() = runDesktopComposeUiTest(width = 412, height = 860) {
        seed()
        openLibrary()
        waitUntilAtLeastOneExists(hasContentDescription("p7.png"), 5_000)
        onNodeWithContentDescription("p7.png").performClick()
        waitUntilAtLeastOneExists(hasText("6 / 7"), 5_000)
        screenshot("library-03-viewer")
        onNodeWithText("Открыть запись").click()
        waitUntilAtLeastOneExists(hasText("Осенний лес", substring = true), 5_000)
    }

    @Test
    fun chosenPicturesGoToTheTrashTogetherAndComeBack() = runDesktopComposeUiTest(width = 412, height = 860) {
        seed()
        openLibrary()
        waitUntilAtLeastOneExists(hasContentDescription("p2.png"), 5_000)
        onNodeWithContentDescription("p2.png").performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        waitUntilAtLeastOneExists(hasText("Выбрано: 1"), 5_000)
        onNodeWithContentDescription("p3.png").performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 2"), 5_000)
        screenshot("library-04-chosen")
        onNodeWithContentDescription("В корзину").performClick()
        waitUntilAtLeastOneExists(hasText("Вложений в корзине: 2"), 5_000)
        waitUntil(timeoutMillis = 5_000) { shown().size == 8 }
        screenshot("library-05-trashed")
        onNodeWithText("Вернуть").performClick()
        waitUntil(timeoutMillis = 5_000) { shown().size == 10 }
    }

    @Test
    fun soundsAndFilesAreRowsAndTheHeaviestComeFirstByWeight() = runDesktopComposeUiTest(width = 412, height = 860) {
        seed()
        openLibrary()
        onNodeWithText("Звуки и голос").performClick()
        waitUntilAtLeastOneExists(hasText("0:12", substring = true), 5_000)
        screenshot("library-06-sounds")
        onNodeWithText("Файлы").performClick()
        waitUntilAtLeastOneExists(hasText("Билеты.pdf"), 5_000)
        waitUntilAtLeastOneExists(hasText("88${Char(0x00A0)}КБ", substring = true), 5_000) // never split from its unit
        screenshot("library-07-files")
        onNodeWithText("Фото и видео").performClick()
        onNodeWithText("По весу").performClick()
        waitUntilAtLeastOneExists(hasContentDescription("p1.png"), 5_000)
        screenshot("library-08-by-weight")
    }

    @Test
    fun aViewShowsItsSectionTheBoardOnlyWhenAskedAndTheQuietOnlyWithBerezhnoOff() {
        fun item(kind: String, size: Long, board: Boolean = false, veil: Veil? = null) = LibraryItem(
            "e$kind$size",
            "e$kind$size",
            "2026-10-21",
            board,
            veil,
            AttachmentState(AttachmentMeta("a$size", "s$size", size, "image/png", kind), 1.0, hlc = Hlc.ZERO),
        )
        val items =
            listOf(
                item("image", 10),
                item("video", 30),
                item("voice", 5),
                item("image", 20, board = true),
                item("image", 40, veil = Veil.Quiet),
            )
        assertEquals(listOf(10L, 30L), LibraryView().of(items, gentle = true).map { it.size })
        assertEquals(listOf(40L, 30L, 20L, 10L), LibraryView(bySize = true, notes = true).of(items, gentle = false).map { it.size })
        assertEquals(listOf(5L), LibraryView(LibrarySection.Sounds).of(items, gentle = true).map { it.size })
    }

    @Test
    fun weightsReadAsPeopleSayThem() {
        assertEquals("0" to 0, sizeParts(0))
        assertEquals("900" to 0, sizeParts(900))
        assertEquals("88" to 1, sizeParts(90_000))
        assertEquals("3,4" to 2, sizeParts(3_565_158))
        assertEquals("2" to 3, sizeParts(2L * 1024 * 1024 * 1024))
        assertEquals("12" to 3, sizeParts(12_884_901_888))
    }
}
