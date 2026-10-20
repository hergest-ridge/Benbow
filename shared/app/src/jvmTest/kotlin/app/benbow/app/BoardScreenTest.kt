package app.benbow.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
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
import app.benbow.client.HomeBlock
import app.benbow.client.JournalRepository
import app.benbow.client.Settings
import app.benbow.client.archiveOnBoard
import app.benbow.client.board
import app.benbow.client.boardTrash
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.createBoardNote
import app.benbow.client.db.BenbowDb
import app.benbow.client.editHomeLayout
import app.benbow.client.moveToBoard
import app.benbow.client.pinOnBoard
import app.benbow.client.plusBlock
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
        waitUntilAtLeastOneExists(hasContentDescription("Новая записка"), 5_000)
    }

    @Test
    fun aNewNoteGrowsOutOfTheQuickBarAndLandsOnTopOfTheBoardNeverInTheJournal() = runDesktopComposeUiTest(width = 412, height = 860) {
        openBoard()
        waitUntilAtLeastOneExists(hasText("Здесь будут записки"), 5_000)
        screenshot("board-01-empty")
        onNodeWithContentDescription("Новая записка").performClick()
        // Keep's way: «Название» on top, «Заметка» under it.
        waitUntilAtLeastOneExists(hasText("Название"), 5_000)
        waitUntilAtLeastOneExists(hasText("Заметка"), 5_000)
        onAllNodes(hasSetTextAction())[0].performTextInput("Купить ниток")
        onAllNodes(hasSetTextAction())[1].performTextInput("и пуговиц")
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
        waitUntilAtLeastOneExists(hasContentDescription("Выбрано: 1"), 5_000)
        onNodeWithTag(b).performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Выбрано: 2"), 5_000)
        screenshot("board-09-chosen")
        onNodeWithContentDescription("Меню").performClick()
        onNodeWithText("Закрепить").performClick()
        waitUntil(timeoutMillis = 5_000) { notes().pinned.map { it.id }.toSet() == setOf(b, c) }
        waitUntilAtLeastOneExists(hasText("Записки"), 5_000) // the bar gave way to the title

        onNodeWithTag(a).performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        waitUntilAtLeastOneExists(hasContentDescription("Выбрано: 1"), 5_000)
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
        waitUntilAtLeastOneExists(hasContentDescription("Выбрано: 1"), 5_000)
        onNodeWithContentDescription("В корзину").performClick()
        waitUntil(timeoutMillis = 5_000) { notes().others.isEmpty() }
        waitUntilAtLeastOneExists(hasText("Записка в корзине"), 5_000)
        assertEquals(listOf(a), runBlocking { repo.boardTrash().first() }.map { it.id })
        assertEquals(emptyList(), runBlocking { repo.trash().first() }, "the journal's trash keeps to the journal")
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
        waitUntilAtLeastOneExists(hasContentDescription("Выбрано: 1"), 5_000)
        onNodeWithTag(c).performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.DirectionRight)
            keyUp(Key.CtrlLeft)
        }
        waitUntil(timeoutMillis = 5_000) { notes().others.map { it.id } == listOf(b, c, a) }
        onNodeWithTag(c).performKeyInput { pressKey(Key.Escape) }
        waitUntilAtLeastOneExists(hasText("Записки"), 5_000)
    }

    @Test
    fun aNewListIsWrittenBoxByBoxAndTickedOnItsCard() = runDesktopComposeUiTest(width = 412, height = 860) {
        openBoard()
        onNodeWithContentDescription("Новая записка").performClick()
        waitUntilAtLeastOneExists(hasText("Название"), 5_000)
        onAllNodes(hasSetTextAction())[0].performTextInput("Продукты")
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").performClick()
        onNodeWithText("Показать флажки").performClick()
        waitUntilAtLeastOneExists(hasText("Пункт списка"), 5_000)
        // The title's field, then the box's: a line break starts the next box.
        onAllNodes(hasSetTextAction())[1].performTextInput("молоко\nхлеб")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 3 }
        waitForIdle()
        screenshot("board-10-list")
        onNodeWithContentDescription("Назад").performClick()
        waitUntilDoesNotExist(hasText("Пункт списка"), 5_000)
        waitUntil(timeoutMillis = 5_000) {
            notes().others.singleOrNull()?.card?.let {
                it.title == "Продукты" && it.lines.map { l -> l.text } == listOf("молоко", "хлеб")
            } ==
                true
        }
        val id = notes().others.single().id

        // A box ticked right on the card goes under «Отмечено».
        waitUntilAtLeastOneExists(hasContentDescription("Отметить"), 5_000)
        onAllNodes(hasContentDescription("Отметить"))[0].performClick()
        waitUntil(timeoutMillis = 5_000) { notes().others.single().card.ticked == 1 }
        waitUntilAtLeastOneExists(hasText("Отмечено: 1"), 5_000)
        assertEquals(listOf("хлеб"), notes().others.single().card.lines.map { it.text })

        onNodeWithTag(id).performClick()
        waitUntilAtLeastOneExists(hasText("Пункт списка"), 5_000)
        waitUntilAtLeastOneExists(hasText("Отмечено: 1"), 5_000)
        screenshot("board-11-list-open")
    }

    @Test
    fun aNotesLinesBecomeBoxesAndBack() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = repo.createBoardNote(services.today(), JournalRepository.textBody("Дела\nпозвонить\nкупить батарейки"))
        openBoard()
        onNodeWithTag(id).performClick()
        waitUntilAtLeastOneExists(hasSetTextAction(), 5_000)
        onNodeWithContentDescription("Меню").performClick()
        onNodeWithText("Показать флажки").performClick()
        waitUntilAtLeastOneExists(hasText("Пункт списка"), 5_000)
        onNodeWithContentDescription("Назад").performClick()
        waitUntil(timeoutMillis = 5_000) {
            notes().others.single().card.let { it.title == "" && it.lines.size == 3 && it.lines.all { l -> l.box } }
        }

        onNodeWithTag(id).performClick()
        waitUntilAtLeastOneExists(hasText("Пункт списка"), 5_000)
        onNodeWithContentDescription("Меню").performClick()
        onNodeWithText("Скрыть флажки").performClick()
        waitUntilDoesNotExist(hasText("Пункт списка"), 5_000)
        onNodeWithContentDescription("Назад").performClick()
        waitUntil(timeoutMillis = 5_000) { notes().others.single().card.lines.none { it.box } }
    }

    @Test
    fun theArchiveShowsItsNotesAndLetsThemBack() = runDesktopComposeUiTest(width = 412, height = 860) {
        val (a, b) = listOf("Альфа", "Бета").map { repo.createBoardNote(services.today(), JournalRepository.textBody(it)) }
        repo.archiveOnBoard(a, true)
        openBoard()
        waitUntilAtLeastOneExists(hasText("Бета"), 5_000)
        assertTrue(onAllNodes(hasText("Альфа")).fetchSemanticsNodes().isEmpty())
        onNodeWithContentDescription("Архив").performClick()
        waitUntilAtLeastOneExists(hasText("Альфа"), 5_000)
        screenshot("board-12-archive")
        onNodeWithTag(a).performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        waitUntilAtLeastOneExists(hasContentDescription("Выбрано: 1"), 5_000)
        onNodeWithContentDescription("Из архива").performClick()
        waitUntil(timeoutMillis = 5_000) { notes().others.map { it.id }.toSet() == setOf(a, b) }
        waitUntilAtLeastOneExists(hasText("В архиве пусто"), 5_000)
        onNodeWithContentDescription("Назад").performClick()
        waitUntilAtLeastOneExists(hasText("Альфа"), 5_000)
    }

    @Test
    fun searchingTheBoardFiltersItAndPointsToTheJournalAndBack() = runDesktopComposeUiTest(width = 412, height = 860) {
        repo.createNote(services.today(), "Смородина у мамы на даче")
        repo.createBoardNote(services.today(), JournalRepository.textBody("Варенье\nсмородина, сахар"))
        repo.createBoardNote(services.today(), JournalRepository.checklistBody())
        repo.createBoardNote(services.today(), JournalRepository.textBody("Купить смородину"))
        openBoard()
        onNodeWithContentDescription("Искать в записках").performClick()
        waitUntilAtLeastOneExists(hasSetTextAction(), 5_000)
        onNode(hasSetTextAction()).performTextInput("смородина")
        waitUntilAtLeastOneExists(hasText("Варенье"), 5_000)
        waitUntilAtLeastOneExists(hasText("Купить смородину"), 5_000)
        waitUntilAtLeastOneExists(hasText("Ещё 1 в дневнике"), 5_000)
        screenshot("board-13-search")

        // The journal's search has it, and points back to the board.
        onNodeWithText("Ещё 1 в дневнике").performClick()
        waitUntilAtLeastOneExists(hasText("Смородина у мамы на даче"), 5_000)
        waitUntilAtLeastOneExists(hasText("Ещё 2 в записках"), 5_000)
        onNodeWithText("Ещё 2 в записках").performClick()
        waitUntilAtLeastOneExists(hasText("Варенье"), 5_000)
    }

    @Test
    fun theTrashKeepsTheBoardsNotesApart() = runDesktopComposeUiTest(width = 412, height = 860) {
        val note = repo.createBoardNote(services.today(), JournalRepository.textBody("Старый список"))
        val entry = repo.createNote(services.today(), "Старая запись")
        repo.moveToTrash(note)
        repo.moveToTrash(entry)
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Корзина").click()
        waitUntilAtLeastOneExists(hasText("Старый список"), 5_000)
        waitUntilAtLeastOneExists(hasText("Дневник"), 5_000)
        assertTrue(onAllNodes(hasText("Записки")).fetchSemanticsNodes().isNotEmpty())
        screenshot("board-14-trash")
    }

    @Test
    fun anEntryGoesOntoTheBoardAndBackAndEachMoveShowsWhereItWent() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = repo.createNote(services.today(), "Идея для подарка")
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilAtLeastOneExists(hasText("Идея для подарка"), 5_000)
        onNodeWithText("Идея для подарка").performTouchInput { longClick() }
        waitUntilAtLeastOneExists(hasText("В записки"), 5_000)
        onNodeWithText("В записки").performClick()
        waitUntilAtLeastOneExists(hasText("Перенесено в записки"), 5_000)
        assertEquals(emptyList(), runBlocking { repo.journal().first() })
        onNodeWithText("Открыть").performClick()
        waitUntilAtLeastOneExists(hasText("из дневника", substring = true), 5_000)
        screenshot("board-15-from-journal")

        // And back: on the day it was made, with a way to it in the feed.
        onNodeWithTag(id).performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").performClick()
        onNodeWithText("В дневник").performClick()
        waitUntilAtLeastOneExists(hasText("Перенесено в дневник", substring = true), 5_000)
        assertEquals(listOf(id), runBlocking { repo.journal().first() }.map { it.id })
        onNodeWithText("Открыть").performClick()
        waitUntilAtLeastOneExists(hasText("из записок", substring = true), 5_000)
    }

    @Test
    fun deletingANoteThatWasAJournalEntryOffersTheJournalBack() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = repo.createNote(services.today(), "Старая мысль")
        repo.moveToBoard(id)
        openBoard()
        waitUntilAtLeastOneExists(hasText("Старая мысль"), 5_000)
        onNodeWithTag(id).performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        waitUntilAtLeastOneExists(hasContentDescription("Выбрано: 1"), 5_000)
        onNodeWithContentDescription("В корзину").performClick()
        waitUntilAtLeastOneExists(hasText("Это была запись дневника"), 5_000)
        screenshot("board-16-former")
        onNodeWithText("Вернуть в дневник").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { repo.journal().first() }.map { it.id } == listOf(id) }
        assertEquals(emptyList(), runBlocking { repo.boardTrash().first() }, "nothing went to the trash")
    }

    @Test
    fun homeShowsTheBoardsPinnedNotesAndQuickCaptureCanPutANoteThere() = runDesktopComposeUiTest(width = 412, height = 860) {
        repo.pinOnBoard(repo.createBoardNote(services.today(), JournalRepository.textBody("Код от домофона 4512")), true)
        repo.editHomeLayout { it.plusBlock(HomeBlock("notes", "notes", "m"), 0) }
        setContent { BenbowApp(services) }
        waitUntilAtLeastOneExists(hasText("Код от домофона 4512"), 5_000)
        screenshot("board-17-home")
        onNodeWithText("Код от домофона 4512").performClick()
        waitUntilAtLeastOneExists(hasSetTextAction(), 5_000)
    }

    @Test
    fun quickCaptureRemembersWhereItPutsThings() = runDesktopComposeUiTest(width = 412, height = 860) {
        var closed = false
        setContent { QuickCapture(services, SharedContent("Купить батарейки", emptyList()), onClose = { closed = true }) }
        onNodeWithText("В записки").performClick()
        onNodeWithText("Сохранить").performClick()
        waitUntil(timeoutMillis = 5_000) { closed }
        assertEquals(listOf("Купить батарейки"), notes().others.map { it.card.lines.single().text })
        assertEquals(emptyList(), runBlocking { repo.journal().first() })
        assertEquals("board", Settings(BenbowDb(driver)).get("capture.target"))
    }

    @Test
    fun aNewNoteOnTopKeepsTheColumnsLevel() = runDesktopComposeUiTest(width = 412, height = 860) {
        val (a, b) = listOf("Первая\nс хвостом\nи ещё", "Вторая").map {
            repo.createBoardNote(services.today(), JournalRepository.textBody(it))
        }
        openBoard()
        waitUntilAtLeastOneExists(hasText("Вторая"), 5_000)
        // A note made elsewhere (another device, the capture sheet) lands on top while the board is at its top.
        val c = repo.createBoardNote(services.today(), JournalRepository.textBody("Третья"))
        waitUntilAtLeastOneExists(hasText("Третья"), 5_000)
        waitForIdle()
        val tops = listOf(c, b).map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops[0], tops[1], "the first row starts level: $tops")
        assertTrue(onNodeWithTag(a).fetchSemanticsNode().boundsInRoot.top > tops[0])
    }

    private fun ComposeUiTest.center(tag: String): Offset = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
}
