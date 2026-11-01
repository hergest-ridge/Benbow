package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.addTag
import app.benbow.client.ownTemplates
import app.benbow.client.saveAsTemplate
import app.benbow.client.templateTrash
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One's own templates (ADR-061): a note saved as one from its menu, then new entries started from it. */
@OptIn(ExperimentalTestApi::class)
class OwnTemplatesUiTest {
    private val services = feedTestServices()

    private fun journal() = runBlocking { services.repo.journal().first() }

    @Test
    fun aNoteSavedAsATemplateLeadsTheChoiceOfANewEntry() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = services.repo.createNote(services.today(), "Утренние страницы\nТри страницы от руки.")
        services.repo.addTag(entry, "утро")
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilAtLeastOneExists(hasText("Утренние страницы", substring = true), 5_000)
        onAllNodesWithText("Утренние страницы", substring = true)[0].click()
        waitUntilAtLeastOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Сохранить как шаблон").click()
        waitUntilAtLeastOneExists(hasText("Название шаблона"), 5_000)
        onNode(hasText("Сохранить") and hasClickAction()).click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.ownTemplates().first() }.isNotEmpty() }
        assertEquals(
            listOf("Утренние страницы"),
            runBlocking {
                services.repo.ownTemplates().first()
            }.map { it.name },
            "named by its first line",
        )
        assertEquals(1, journal().size, "a template is no journal entry")

        // A new entry: the template's chip comes first; its words and tag come with it.
        onNodeWithContentDescription("Назад").click()
        onNodeWithText("Главная").click()
        waitUntilAtLeastOneExists(hasContentDescription("Новая запись"), 5_000)
        onNodeWithContentDescription("Новая запись").click()
        waitUntilAtLeastOneExists(hasText("Итоги дня"), 5_000)
        screenshot("templates-chips")
        onNode(hasText("Утренние страницы") and hasClickAction()).click()
        waitUntil(timeoutMillis = 5_000) { journal().size == 2 }
        waitUntil(timeoutMillis = 5_000) { journal().all { it.tags.map { tag -> tag.name } == listOf("утро") } }
        val made = journal().first { it.id != entry }
        assertTrue("Три страницы от руки." in made.preview, made.preview)
    }

    @Test
    fun aTemplateOpensAsItselfAndGoesToTheTrash() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = services.saveAsTemplate("Сны", app.benbow.client.JournalRepository.textBody("Что снилось"), open = true)
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        waitUntilAtLeastOneExists(hasText("Шаблоны"), 5_000)
        onNode(hasText("Шаблоны") and hasClickAction()).click()
        waitUntilAtLeastOneExists(hasText("Стандартные"), 5_000)
        screenshot("templates-screen")
        onNode(hasText("Сны") and hasClickAction()).click()
        waitUntilAtLeastOneExists(hasText("подставятся при записи", substring = true), 5_000)
        assertTrue(onAllNodes(hasContentDescription("В избранное")).fetchSemanticsNodes().isEmpty(), "a template has no star")
        screenshot("template-open")
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("В корзину").click()
        waitUntil(timeoutMillis = 5_000) { runBlocking { services.repo.ownTemplates().first() }.isEmpty() }
        assertEquals(listOf(id), runBlocking { services.repo.templateTrash().first() }.map { it.id })
    }

    @Test
    fun aLongPressOnTheQuickBarsPenStartsFromATemplate() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.saveAsTemplate("Сны", app.benbow.client.JournalRepository.textBody("Что снилось: {{weekday}}"), open = true)
        setContent { BenbowApp(services) }
        waitUntilAtLeastOneExists(hasContentDescription("Новая запись"), 5_000)
        onNodeWithContentDescription("Новая запись").performTouchInput { longClick() }
        waitUntilAtLeastOneExists(hasText("Шаблоны…"), 5_000)
        screenshot("templates-menu")
        onNode(hasText("Сны") and hasClickAction()).click()
        waitUntil(timeoutMillis = 5_000) { journal().isNotEmpty() }
        runBlocking { delay(200) }
        val made = journal().single()
        assertTrue(made.title.startsWith("Что снилось: ") && "{{" !in made.title, made.title)
    }
}
