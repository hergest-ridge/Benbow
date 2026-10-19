package app.benbow.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import app.benbow.client.JournalRepository
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.LinkCardBlock
import app.benbow.core.content.TextBlock
import app.benbow.core.content.toDocument
import app.benbow.core.text.Mark
import app.benbow.core.text.buildRichText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Links in entries on screen (ADR-038): cards, the bubble, the sheet, opening, and removing down the ladder. */
@OptIn(ExperimentalTestApi::class)
class LinksTest {
    private val opened = ArrayList<String>()
    private val services = feedTestServices().apply {
        pageReader = { null }
        openUrlOverride = { opened += it }
    }

    private fun body(id: String) = runBlocking { services.repo.entry(id).first()!!.body }

    private fun ComposeUiTest.openForWriting(title: String) {
        setContent { BenbowApp(services) }
        waitUntilAtLeastOneExists(hasText(title, substring = true), 5_000)
        onNodeWithText(title, substring = true).click()
        waitUntilExactlyOneExists(hasContentDescription("Меню"), 5_000)
        onNodeWithContentDescription("Меню").click()
        waitUntilExactlyOneExists(hasText("Редактировать"), 5_000)
        onNodeWithText("Редактировать").click()
        waitUntilExactlyOneExists(hasSetTextAction(), 5_000)
    }

    /** Where offset [offset] of the editor's text is, in the field's own coordinates. */
    private fun ComposeUiTest.positionOf(matcher: SemanticsMatcher, offset: Int): Offset {
        val node = onNode(matcher).fetchSemanticsNode()
        val layouts = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.first().getBoundingBox(offset).center
    }

    @Test
    fun anAddressPastedOnAnEmptyLineBecomesACardThatReadsItsPage() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.pageReader = { LinkPreview("Как устроены маяки", "О линзах Френеля", "Хабр", null) }
        val id = services.repo.createNote(services.today(), "Утро у моря")
        openForWriting("Утро у моря")
        onNode(hasSetTextAction()).performTextInputSelection(TextRange(11))
        onNode(hasSetTextAction()).performTextInput("\n")
        onNode(hasSetTextAction()).performTextInput("https://habr.com/ru/articles/1/?utm_source=tg")
        waitUntil(timeoutMillis = 5_000) {
            val card = DocumentCodec.decode(body(id)).blocks.filterIsInstance<LinkCardBlock>().singleOrNull()
            card?.title == "Как устроены маяки" && card.fetched != null
        }
        val card = DocumentCodec.decode(body(id)).blocks.filterIsInstance<LinkCardBlock>().single()
        assertEquals("https://habr.com/ru/articles/1/", card.href) // the tracker went
        assertEquals("Хабр", card.site)
        waitUntilAtLeastOneExists(hasText("Как устроены маяки"), 5_000)
        screenshot("91-link-card")
    }

    @Test
    fun aCardGoesDownToALinkInTheTextAndComesBack() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = services.repo.createEntry(
            services.today(),
            kind = "note",
            body = JournalRepository.noteBody("Утро\nhttps://x.org/lighthouse", null),
        )
        openForWriting("Утро")
        waitUntilAtLeastOneExists(hasText("x.org/lighthouse"), 5_000)
        onNode(hasText("x.org/lighthouse") and hasClickAction()).click() // a tap while writing selects the card
        waitUntilExactlyOneExists(hasText("В текст"), 5_000)
        screenshot("92-card-selected")
        onNodeWithText("В текст").click()
        waitUntil(timeoutMillis = 5_000) { DocumentCodec.decode(body(id)).blocks.none { it is LinkCardBlock } }
        assertTrue("x.org/lighthouse" in body(id))
        waitUntilExactlyOneExists(hasText("Карточка стала ссылкой в тексте"), 5_000)
        onNodeWithText("Вернуть").click()
        waitUntil(timeoutMillis = 5_000) { DocumentCodec.decode(body(id)).blocks.any { it is LinkCardBlock } }
    }

    @Test
    fun backspaceSelectsACardFirstAndRemovesItOnTheSecondPress() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = services.repo.createEntry(
            services.today(),
            kind = "note",
            body = JournalRepository.noteBody("Утро\nhttps://x.org/a\nпосле", null),
        )
        openForWriting("Утро")
        // "Утро\n￼\nпосле": the cursor at the start of «после», right after the card's line.
        onNode(hasSetTextAction()).performTextInputSelection(TextRange(7))
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Backspace) }
        waitUntilExactlyOneExists(hasText("В текст"), 5_000) // selected: its bubble is up
        assertTrue(DocumentCodec.decode(body(id)).blocks.any { it is LinkCardBlock })
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Backspace) }
        waitUntil(timeoutMillis = 5_000) { DocumentCodec.decode(body(id)).blocks.none { it is LinkCardBlock } }
        assertEquals(
            listOf("Утро", "после"),
            DocumentCodec.decode(body(id)).blocks.map {
                (it as TextBlock).children.joinToString("") { s -> s.text }
            },
        )
        waitUntilExactlyOneExists(hasText("Карточка удалена"), 5_000)
        onNodeWithText("Вернуть").click()
        waitUntil(timeoutMillis = 5_000) { DocumentCodec.decode(body(id)).blocks.any { it is LinkCardBlock } }
    }

    @Test
    fun aLinkInAPostOpensOnATapAndWhileWritingShowsItsBubble() = runDesktopComposeUiTest(width = 412, height = 860) {
        val rich = buildRichText {
            paragraph { text("Маяки") }
            paragraph {
                text("Дочитал ")
                text("ту статью", Mark.Link("https://x.org/a"))
                text(" вечером.")
            }
        }
        val id = services.repo.createEntry(services.today(), kind = "note", body = DocumentCodec.encode(rich.toDocument()))
        setContent { BenbowApp(services) }
        waitUntilAtLeastOneExists(hasText("Маяки", substring = true), 5_000)
        onNodeWithText("Маяки", substring = true).click() // opens to read
        val text = hasText("Дочитал ту статью", substring = true)
        waitUntilAtLeastOneExists(text, 5_000)
        val at = positionOf(text, 6 + 8 + 3) // inside «ту статью»
        onNode(text).performTouchInput { click(at) }
        waitUntil(timeoutMillis = 5_000) { opened == listOf("https://x.org/a") }

        // While writing a tap only places the cursor; the bubble offers the rest.
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Редактировать").click()
        waitUntilExactlyOneExists(hasSetTextAction(), 5_000)
        val inEditor = positionOf(hasSetTextAction(), 6 + 8 + 3)
        onNode(hasSetTextAction()).performTouchInput { click(inEditor) }
        waitUntilExactlyOneExists(hasContentDescription("Убрать ссылку"), 5_000)
        assertEquals(1, opened.size)
        screenshot("93-link-bubble")
        onNodeWithContentDescription("Убрать ссылку").click()
        waitUntil(timeoutMillis = 5_000) { "x.org/a" !in body(id) }
        waitUntilExactlyOneExists(hasText("Ссылка убрана, текст остался"), 5_000)
        onNodeWithText("Вернуть").click()
        waitUntil(timeoutMillis = 5_000) { "x.org/a" in body(id) }
    }

    @Test
    fun theLinkSheetLinksSelectedWords() = runDesktopComposeUiTest(width = 412, height = 860) {
        val id = services.repo.createNote(services.today(), "Читал про маяки")
        openForWriting("Читал про маяки")
        onNode(hasSetTextAction()).performTextInputSelection(TextRange(10, 15)) // «маяки»
        onNodeWithContentDescription("Ссылка").click()
        waitUntilExactlyOneExists(hasText("Адрес") and hasSetTextAction(), 5_000)
        onNode(hasText("Адрес") and hasSetTextAction()).performTextInput("ru.wikipedia.org/wiki/Маяк")
        screenshot("94-link-sheet")
        onNodeWithText("Готово").click()
        waitUntilDoesNotExist(hasText("Текст ссылки"), 5_000)
        waitUntil(timeoutMillis = 5_000) { "https://ru.wikipedia.org/wiki/Маяк" in body(id) }
        assertTrue(
            "Читал про маяки" in
                DocumentCodec.decode(body(id)).blocks.joinToString { (it as TextBlock).children.joinToString("") { s -> s.text } },
        )
    }

    @Test
    fun aCardInTheFeedOpensItsPage() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.repo.createEntry(services.today(), kind = "note", body = JournalRepository.noteBody("Почитать\nhttps://x.org/read", null))
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntilAtLeastOneExists(hasText("x.org/read") and hasClickAction(), 5_000)
        screenshot("95-feed-card")
        onNode(hasText("x.org/read") and hasClickAction()).click()
        waitUntil(timeoutMillis = 5_000) { opened == listOf("https://x.org/read") }
    }
}
