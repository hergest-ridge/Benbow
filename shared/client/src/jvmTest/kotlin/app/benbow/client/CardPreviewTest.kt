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
    fun theTitleIsTheNotesOwnHeadingAndPlainLinesAreItsWords() {
        assertEquals(CardPreview(lines = listOf(CardLine("Купить ниток"))), cardOf(Document(listOf(text("Купить ниток")))))
        val plain = cardOf(Document(listOf(text("Ремонт"), text("Ниша 62 см"))))
        assertEquals("", plain.title, "a first line is no title unless it is the note's title (a heading)")
        assertEquals(listOf("Ремонт", "Ниша 62 см"), plain.lines.map { it.text })
        val titled = cardOf(
            Document(listOf(text("Ремонт", style = "h3"), text("Ниша 62 см\nрозетка слева"), text(""), text("Позвонить мастеру"))),
        )
        assertEquals("Ремонт", titled.title)
        assertEquals(listOf("Ниша 62 см", "розетка слева", "", "Позвонить мастеру"), titled.lines.map { it.text })
        assertEquals("Только название", cardOf(Document(listOf(text("Только название", style = "h3")))).title)
    }

    @Test
    fun aChecklistShowsItsOpenBoxesInOrderAndCountsTheTickedOnes() {
        val card = cardOf(
            Document(
                listOf(
                    text("Продукты", style = "h3"),
                    text("молоко", "check", false),
                    text("хлеб", "check", true),
                    text("яйца", "check", false),
                    text("сыр", "check", true),
                ),
            ),
        )
        assertEquals("Продукты", card.title)
        assertEquals(listOf(CardLine("молоко", box = true, key = "k1"), CardLine("яйца", box = true, key = "k3")), card.lines)
        assertEquals(2, card.ticked)
        // «Отмеченные — вниз» off: every box where it stands, the ticked ones ticked.
        assertEquals(
            listOf(
                CardLine("молоко", box = true, key = "k1"),
                CardLine("хлеб", box = true, key = "k2", checked = true),
                CardLine("яйца", box = true, key = "k3"),
                CardLine("сыр", box = true, key = "k4", checked = true),
            ),
            card.inPlace,
        )
        assertEquals(emptyList(), cardOf(Document(listOf(text("молоко", "check", false)))).inPlace, "nothing ticked: the lines as they are")
        // A list with no title: its first box is no title.
        assertEquals("", cardOf(Document(listOf(text("молоко", "check", false), text("хлеб", "check", false)))).title)
    }

    @Test
    fun listsAreNumberedHeadingsMarkedSpoilersHiddenAndLongNotesCut() {
        val card = cardOf(
            Document(
                listOf(
                    text("Планы", style = "h3"),
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
        val long = cardOf(Document(List(40) { text("строка $it") }))
        assertEquals(30, long.lines.size)
        assertTrue(long.more)
        // A long paragraph: enough for a card of some 26 rows, the rest left to the note.
        val essay = cardOf(Document(listOf(text("слово ".repeat(500)))))
        assertTrue(essay.more)
        assertTrue(essay.lines.single().text.length in 500..600, "${essay.lines.single().text.length}")
    }
}
