package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import kotlin.test.Test
import kotlin.test.assertTrue

/** Home's posts (owner, 2026-10-30): pinned, today's, drafts, on this day and the favorite read as posts, not a line. */
@OptIn(ExperimentalTestApi::class)
class HomePostsTest {
    @Test
    fun homePostsKeepTheirParagraphsAndALongOneOpensInPlace() = runDesktopComposeUiTest(width = 412, height = 1600) {
        val services = feedTestServices()
        val long = (1..12).joinToString("\n") { "Абзац номер $it: как прошёл день, что было потом и ещё немного слов." }
        services.repo.createNote(services.today(), "Утро у моря\nПервый абзац\nВторой абзац")
        services.repo.createNote(services.today(), "Длинный день\n$long")
        setContent { BenbowApp(services) }
        waitUntilAtLeastOneExists(hasText("Первый абзац"), 5_000)
        // Each paragraph its own, not «Первый абзац Второй абзац» run together.
        onNodeWithText("Второй абзац").assertExists()
        onAllNodesWithText("Показать ещё").assertCountEquals(1)
        assertTrue(onAllNodesWithText("Абзац номер 12", substring = true).fetchSemanticsNodes().isEmpty(), "a long post is cut")
        waitForIdle()
        screenshot("home-posts")
        onNodeWithText("Показать ещё").click()
        waitUntilAtLeastOneExists(hasText("Абзац номер 12", substring = true), 5_000)
        onAllNodesWithText("Показать ещё").assertCountEquals(0)
    }
}
