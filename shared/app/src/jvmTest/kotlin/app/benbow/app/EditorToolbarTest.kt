package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import app.benbow.client.JournalRepository
import app.benbow.client.createBoardNote
import kotlin.test.Test
import kotlin.test.assertTrue

/** The editor's bar (owner: hard to read): what is used most in one row, «Аа» and «H» for the rest, a name on each. */
@OptIn(ExperimentalTestApi::class)
class EditorToolbarTest {
    private val services = feedTestServices()

    @Test
    fun theRestOfTheToolsWaitBehindTheirPanelsAndEachSaysItsName() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = services.repo.createBoardNote(services.today(), JournalRepository.textBody("Альфа"))
        setContent { BenbowApp(services) }
        onNodeWithText("Записки").click()
        waitUntilAtLeastOneExists(hasContentDescription("Новая записка"), 5_000)
        onNodeWithTag(id).performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Жирный"), 5_000)
        assertTrue(onAllNodes(hasContentDescription("Подчёркнутый")).fetchSemanticsNodes().isEmpty(), "underline waits in «Аа»")
        assertTrue(onAllNodes(hasContentDescription("Увеличить отступ")).fetchSemanticsNodes().isEmpty(), "indents only in a list")

        // A mouse over a tool shows its name and its keys.
        onNodeWithContentDescription("Жирный").performMouseInput { moveTo(center) }
        waitUntilAtLeastOneExists(hasText("Жирный · Ctrl+B"), 5_000)
        screenshot("toolbar-01-hint")
        onNodeWithContentDescription("Жирный").performMouseInput { moveTo(center.copy(y = center.y - 400f)) }
        waitUntilDoesNotExist(hasText("Жирный · Ctrl+B"), 5_000)

        onNodeWithContentDescription("Текст", substring = true).performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Подчёркнутый"), 5_000)
        waitUntilAtLeastOneExists(hasContentDescription("Спойлер"), 5_000)
        screenshot("toolbar-02-text")
        onNodeWithContentDescription("Абзац", substring = true).performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Заголовок 1"), 5_000)
        waitUntilDoesNotExist(hasContentDescription("Подчёркнутый"), 5_000)
        screenshot("toolbar-03-paragraph")

        // In a list the indents come in beside the lists.
        onNodeWithContentDescription("Маркированный список").performClick()
        waitUntilAtLeastOneExists(hasContentDescription("Увеличить отступ"), 5_000)
    }
}
