package app.benbow.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import app.benbow.client.JournalRepository
import app.benbow.client.Settings
import app.benbow.client.board
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.createBoardNote
import app.benbow.client.db.BenbowDb
import app.benbow.client.pinOnBoard
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Записки» (ADR-040, K2): the board on its notes, a new note from the quick bar, a note opening as its card's sheet. */
@OptIn(ExperimentalTestApi::class)
class BoardScreenTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema)
    private val services = AppServices(
        driver,
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
        "desktop",
        "Test PC",
        labs = true,
    )
    private val repo = services.repo

    private fun notes() = runBlocking { repo.board().first() }

    private fun ComposeUiTest.openBoard() {
        setContent { BenbowApp(services) }
        onNodeWithText("Записки").click()
        waitUntilAtLeastOneExists(hasText("Заметка…"), 5_000)
    }

    @Test
    fun aNewNoteGrowsOutOfTheQuickBarAndLandsOnTopOfTheBoardNeverInTheJournal() = runDesktopComposeUiTest(width = 412, height = 860) {
        openBoard()
        waitUntilAtLeastOneExists(hasText("Здесь будут записки"), 5_000)
        screenshot("board-01-empty")
        onNodeWithText("Заметка…").performClick()
        waitUntilAtLeastOneExists(hasSetTextAction(), 5_000)
        onNode(hasSetTextAction()).performTextInput("Купить ниток\nи пуговиц")
        onNodeWithContentDescription("Назад").performClick()
        waitUntilDoesNotExist(hasSetTextAction(), 5_000)
        waitUntil(timeoutMillis = 5_000) { notes().others.singleOrNull()?.card?.title == "Купить ниток" }
        waitUntilAtLeastOneExists(hasText("и пуговиц"), 5_000)
        assertEquals(emptyList(), runBlocking { repo.journal().first() }, "a note on the board is not the journal's")
        screenshot("board-02-new-note")
    }

    @Test
    fun cardsShowTheirNotesAndANoteOpensAsItsCardsSheet() = runDesktopComposeUiTest(width = 412, height = 860) {
        val list = repo.createBoardNote(
            services.today(),
            DocumentCodec.encode(
                Document(
                    listOf(TextBlock("t", children = listOf(Span("Продукты")))) +
                        listOf("молоко" to false, "хлеб" to true, "яйца" to false, "сыр" to true)
                            .mapIndexed { i, (item, done) ->
                                TextBlock("c$i", listItem = "check", checked = done, children = listOf(Span(item)))
                            },
                ),
            ),
        )
        val repair = repo.createBoardNote(services.today(), JournalRepository.textBody("Ремонт\nНиша 62 см, розетка слева"))
        repo.setBackground(repair, "mint")
        repo.pinOnBoard(repo.createBoardNote(services.today(), JournalRepository.textBody("Код от домофона 4512")), true)
        openBoard()
        waitUntilAtLeastOneExists(hasText("Отмечено: 2"), 5_000)
        assertTrue(onAllNodes(hasText("Закреплённые")).fetchSemanticsNodes().isNotEmpty())
        screenshot("board-03-cards")

        // Halfway the card's sheet has grown part of the way, opaque: board-04-halfway.png.
        mainClock.autoAdvance = false
        onNodeWithTag(list).performClick()
        mainClock.advanceTimeBy(150)
        screenshot("board-04-halfway")
        mainClock.autoAdvance = true
        waitUntilAtLeastOneExists(hasSetTextAction(), 5_000)
        waitUntilAtLeastOneExists(hasText("молоко", substring = true), 5_000)
        screenshot("board-05-open")
        onNodeWithContentDescription("Назад").performClick()
        waitUntilDoesNotExist(hasSetTextAction(), 5_000)
        waitUntilAtLeastOneExists(hasText("Отмечено: 2"), 5_000)
    }

    @Test
    fun aCardCarriedByTheMouseKeepsItsPlace() = runDesktopComposeUiTest(width = 412, height = 860) {
        val (a, b, c) = listOf("Альфа", "Бета", "Гамма").map { repo.createBoardNote(services.today(), JournalRepository.textBody(it)) }
        openBoard()
        waitUntilAtLeastOneExists(hasText("Гамма"), 5_000)
        assertEquals(listOf(c, b, a), notes().others.map { it.id }, "the newest on top")
        val from = center(c)
        val to = center(b)
        onNodeWithTag(c).performMouseInput {
            moveTo(center)
            press()
            (1..12).forEach { moveTo(center + (to - from) * (it / 12f)) }
            release() // while a card is carried the board draws every frame: the test never idles until it lands
        }
        waitUntil(timeoutMillis = 5_000) { notes().others.map { it.id } == listOf(b, c, a) }
    }

    @Test
    fun theBoardIsAGridOrAListAsThisDeviceChose() = runDesktopComposeUiTest(width = 1280, height = 800) {
        listOf("Первая", "Вторая", "Третья").forEach { repo.createBoardNote(services.today(), JournalRepository.textBody(it)) }
        openBoard()
        waitUntilAtLeastOneExists(hasText("Третья"), 5_000)
        screenshot("board-06-wide")
        onNodeWithText("Третья").performClick()
        waitUntilAtLeastOneExists(hasSetTextAction(), 5_000)
        screenshot("board-07-wide-open")
        onNodeWithContentDescription("Назад").performClick()
        waitUntilDoesNotExist(hasSetTextAction(), 5_000)
        onNode(hasContentDescription("Списком")).performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Сеткой"), 5_000)
        assertEquals("list", Settings(BenbowDb(driver)).get("board.view"))
        screenshot("board-08-wide-list")
    }

    @Test
    fun chosenNotesArePinnedArchivedAndTrashedFromTheBarWithAWayBack() = runDesktopComposeUiTest(width = 412, height = 860) {
        val (a, b, c) = listOf("Альфа", "Бета", "Гамма").map { repo.createBoardNote(services.today(), JournalRepository.textBody(it)) }
        openBoard()
        waitUntilAtLeastOneExists(hasText("Гамма"), 5_000)

        // A long press without a move chooses; then a tap chooses too.
        onNodeWithTag(c).performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        waitUntilAtLeastOneExists(hasText("Выбрано: 1"), 5_000)
        onNodeWithTag(b).performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 2"), 5_000)
        screenshot("board-09-chosen")
        onNodeWithContentDescription("Закрепить").performClick()
        waitUntil(timeoutMillis = 5_000) { notes().pinned.map { it.id }.toSet() == setOf(b, c) }
        waitUntilAtLeastOneExists(hasText("Записки"), 5_000) // the bar gave way to the title

        onNodeWithTag(a).performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        waitUntilAtLeastOneExists(hasText("Выбрано: 1"), 5_000)
        onNodeWithContentDescription("В архив").performClick()
        waitUntil(timeoutMillis = 5_000) { notes().others.isEmpty() }
        waitUntilAtLeastOneExists(hasText("Записка в архиве"), 5_000)
        onNodeWithText("Вернуть").performClick()
        waitUntil(timeoutMillis = 5_000) { notes().others.map { it.id } == listOf(a) }

        onNodeWithTag(a).performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        waitUntilAtLeastOneExists(hasText("Выбрано: 1"), 5_000)
        onNodeWithContentDescription("В корзину").performClick()
        waitUntil(timeoutMillis = 5_000) { notes().others.isEmpty() }
        waitUntilAtLeastOneExists(hasText("Записка в корзине"), 5_000)
        assertEquals(listOf(a), runBlocking { repo.trash().first() }.map { it.id })
    }

    @Test
    fun aCardSwipedAsideGoesToTheArchive() = runDesktopComposeUiTest(width = 412, height = 860) {
        val (a, b) = listOf("Альфа", "Бета").map { repo.createBoardNote(services.today(), JournalRepository.textBody(it)) }
        openBoard()
        waitUntilAtLeastOneExists(hasText("Бета"), 5_000)
        // A short sideways move springs back.
        onNodeWithTag(b).performTouchInput {
            down(center)
            (1..4).forEach { moveTo(center + Offset(-10f * it, 0f)) }
            up()
        }
        waitForIdle()
        assertEquals(listOf(b, a), notes().others.map { it.id })
        onNodeWithTag(b).performTouchInput {
            down(center)
            (1..10).forEach { moveTo(center + Offset(-24f * it, 0f)) }
            up()
        }
        waitUntil(timeoutMillis = 5_000) { notes().others.map { it.id } == listOf(a) }
        waitUntilAtLeastOneExists(hasText("Записка в архиве"), 5_000)
    }

    @Test
    fun keysMoveAChosenCardAndEscLetsItGo() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val (a, b, c) = listOf("Альфа", "Бета", "Гамма").map { repo.createBoardNote(services.today(), JournalRepository.textBody(it)) }
        openBoard()
        waitUntilAtLeastOneExists(hasText("Гамма"), 5_000)
        // With a mouse a card's mark shows on hover; a click on it chooses the card.
        onNodeWithTag(c).performMouseInput { moveTo(center) }
        waitUntilAtLeastOneExists(hasContentDescription("Выбрать"), 5_000)
        onNodeWithContentDescription("Выбрать").performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 1"), 5_000)
        onNodeWithTag(c).performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.DirectionRight)
            keyUp(Key.CtrlLeft)
        }
        waitUntil(timeoutMillis = 5_000) { notes().others.map { it.id } == listOf(b, c, a) }
        onNodeWithTag(c).performKeyInput { pressKey(Key.Escape) }
        waitUntilAtLeastOneExists(hasText("Записки"), 5_000)
    }

    private fun ComposeUiTest.center(tag: String): Offset = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
}
