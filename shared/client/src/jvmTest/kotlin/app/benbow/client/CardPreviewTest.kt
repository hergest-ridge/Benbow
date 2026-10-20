package app.benbow.client

import app.benbow.core.content.Document
import app.benbow.core.content.LinkCardBlock
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A note's card on the board (ADR-040): what Keep would show of it, made without the board parsing anything. */
class CardPreviewTest {
    private var key = 0

    private fun text(text: String, item: String? = null, checked: Boolean? = null, style: String = "normal", spoiler: String? = null) =
        TextBlock(
            "k${key++}",
            style = style,
            listItem = item,
            checked = checked,
            children = listOfNotNull(Span(text), spoiler?.let { Span(it, listOf("spoiler")) }),
        )

    @Test
    fun aLoneLineIsTheNoteAndALineWithMoreUnderItItsTitle() {
        assertEquals(CardPreview(lines = listOf(CardLine("Купить ниток"))), cardOf(Document(listOf(text("Купить ниток")))))
        val titled = cardOf(Document(listOf(text("Ремонт"), text("Ниша 62 см\nрозетка слева"), text(""), text("Позвонить мастеру"))))
        assertEquals("Ремонт", titled.title)
        assertEquals(listOf("Ниша 62 см", "розетка слева", "", "Позвонить мастеру"), titled.lines.map { it.text })
    }

    @Test
    fun aChecklistShowsItsOpenBoxesInOrderAndCountsTheTickedOnes() {
        val card = cardOf(
            Document(
                listOf(
                    text("Продукты"),
                    text("молоко", "check", false),
                    text("хлеб", "check", true),
                    text("яйца", "check", false),
                    text("сыр", "check", true),
                ),
            ),
        )
        assertEquals("Продукты", card.title)
        assertEquals(listOf(CardLine("молоко", box = true), CardLine("яйца", box = true)), card.lines)
        assertEquals(2, card.ticked)
        // A list with no title: its first box is no title.
        assertEquals("", cardOf(Document(listOf(text("молоко", "check", false), text("хлеб", "check", false)))).title)
    }

    @Test
    fun listsAreNumberedHeadingsMarkedSpoilersHiddenAndLongNotesCut() {
        val card = cardOf(
            Document(
                listOf(
                    text("Планы"),
                    text("Глава", style = "h2"),
                    text("первое", "number"),
                    text("второе", "number"),
                    text("пункт", "bullet"),
                    text("видно ", spoiler = "тайна"),
                    LinkCardBlock("l", "https://example.org/page", title = "Страница"),
                ),
            ),
        )
        assertEquals(
            listOf(
                CardLine("Глава", heading = true),
                CardLine("первое", bullet = "1."),
                CardLine("второе", bullet = "2."),
                CardLine("пункт", bullet = "•"),
                CardLine("видно"),
                CardLine("Страница"),
            ),
            card.lines,
        )
        val long = cardOf(Document(List(30) { text("строка $it") }))
        assertEquals(12, long.lines.size)
        assertTrue(long.more)
    }
}
