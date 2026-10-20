package app.benbow.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The board's K0 spike (ADR-040): cards carried by mouse and finger, a note opened and closed, the tab in labs builds. */
@OptIn(ExperimentalTestApi::class)
class BoardSpikeTest {
    @Test
    fun aMouseCarriesACardOntoAnother() = runDesktopComposeUiTest(width = 412, height = 860) {
        val board = SpikeBoard(60)
        setContent { BoardScreen(board) }
        val from = center("n4")
        val to = center("n5")
        onNodeWithTag("n4").performMouseInput {
            moveTo(center)
            press()
            steps(from, to) { moveTo(it - from + center) }
            release() // while a card is carried the board draws every frame: the test never idles until it lands
        }
        waitForIdle()
        assertEquals(listOf("n5", "n4", "n6"), board.others.take(3).map { it.id })
        assertEquals(listOf("n0", "n1", "n2", "n3"), board.pinned.map { it.id }, "pinned ones stay apart")
    }

    @Test
    fun aFingerLiftsAfterALongPressAndScrollsOtherwise() = runDesktopComposeUiTest(width = 412, height = 860) {
        val board = SpikeBoard(60)
        setContent { BoardScreen(board) }
        val from = center("n5")
        val to = center("n4")
        onNodeWithTag("n5").performTouchInput {
            down(center)
            advanceEventTime(800)
            steps(from, to) { moveTo(it - from + center) }
            up()
        }
        waitForIdle()
        assertEquals(listOf("n5", "n4", "n6"), board.others.take(3).map { it.id })

        // Moving at once is a scroll: nothing changes places.
        onNodeWithTag("n6").performTouchInput { swipeUp() }
        waitForIdle()
        assertEquals(listOf("n5", "n4", "n6"), board.others.take(3).map { it.id })
    }

    @Test
    fun aNoteOpensForWritingAndClosesBackIntoItsCard() = runDesktopComposeUiTest(width = 412, height = 860) {
        val board = SpikeBoard(60)
        setContent { BoardScreen(board) }
        // Halfway the card's sheet has grown part of the way, opaque (no fade): see board-02-halfway.png.
        mainClock.autoAdvance = false
        onNodeWithTag("n4").performClick()
        mainClock.advanceTimeBy(150)
        screenshot("board-02-halfway")
        mainClock.autoAdvance = true
        waitUntilAtLeastOneExists(hasContentDescription("Закрыть"), 5_000)
        screenshot("board-02-open")
        onNodeWithContentDescription("Закрыть").performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasContentDescription("Закрыть")).fetchSemanticsNodes().isEmpty(), "the arrow closes it")
        screenshot("board-03-closed")
    }

    @Test
    fun theTabShowsInLabsBuildsAfterCalendarWithSearchInMore() = runDesktopComposeUiTest(width = 412, height = 860) {
        val services = labsServices()
        setContent { BenbowApp(services) }
        onNodeWithText("Записки").click()
        waitUntilAtLeastOneExists(hasText("Закреплённые"), 5_000)
        screenshot("board-01-phone")
        onNodeWithText("Ещё").click()
        waitUntilAtLeastOneExists(hasText("Записи, теги, вложения"), 5_000)
        onNodeWithText("Записи, теги, вложения").click()
        waitUntilAtLeastOneExists(hasText("Слова, теги…"), 5_000)
    }

    @Test
    fun onceACardIsChosenATapChoosesInsteadOfOpening() = runDesktopComposeUiTest(width = 412, height = 860) {
        val board = SpikeBoard(60)
        setContent { BoardScreen(board) }
        onNodeWithTag("n4").performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        waitForIdle()
        onNodeWithTag("n5").performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasContentDescription("Закрыть")).fetchSemanticsNodes().isEmpty(), "no note opened")
        screenshot("board-06-chosen")
    }

    @Test
    fun aWideWindowOpensANoteAsADialogOverTheBoard() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val services = labsServices()
        setContent { BenbowApp(services) }
        onNodeWithText("Записки").click()
        waitUntilAtLeastOneExists(hasText("Закреплённые"), 5_000)
        screenshot("board-04-wide")
        onNodeWithTag("n5").performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Закрыть"), 5_000)
        screenshot("board-05-wide-open")
    }

    private fun ComposeUiTest.center(tag: String): Offset = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center

    /** Twelve steps along the way, a frame apart, so the grid lays out between them. */
    private fun steps(from: Offset, to: Offset, move: (Offset) -> Unit) {
        (1..12).forEach { move(from + (to - from) * (it / 12f)) }
    }

    private fun labsServices() = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
        "desktop",
        "Test PC",
        labs = true,
    )
}
