package app.benbow.client

import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.TextBlock
import app.benbow.core.content.toPlainText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplatesTest {
    private val context = TemplateContext("13 октября 2026", "вторник", "Октябрь", "Какой момент сегодня хочется запомнить?")

    @Test
    fun placeholdersAreFilledAndStructureKept() {
        val doc = DocumentCodec.decode(Templates.render(Templates.byId("today")!!, context))
        val blocks = doc.blocks.filterIsInstance<TextBlock>()
        assertEquals("вторник, 13 октября 2026", blocks.first().children.single().text)
        assertEquals("h1", blocks.first().style)
        assertEquals("check", blocks.last().listItem)
        assertEquals(false, blocks.last().checked)
        assertTrue(
            Templates.builtIn.all { t ->
                "{{" !in DocumentCodec.decode(Templates.render(t, context)).toPlainText()
            },
            "no placeholder left anywhere",
        )
    }

    @Test
    fun theQuestionOfTheDayIsStableForADay() {
        assertEquals(Prompts.ofTheDay("2026-10-13"), Prompts.ofTheDay("2026-10-13"))
        assertTrue(Prompts.ofTheDay("2026-10-13") in Prompts.byPractice.values.flatten())
        assertTrue(Prompts.kindness("2026-10-13") in Prompts.byPractice.getValue("Доброта к себе"))
        val week = (10..16).map { Prompts.ofTheDay("2026-10-$it") }.toSet()
        assertTrue(week.size > 1, "it changes over a week")
    }
}
