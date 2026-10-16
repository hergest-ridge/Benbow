package app.benbow.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagSearchTest {
    private val tags = listOf(
        TagChoice("1", "Море", uses = 5, category = "Места"),
        TagChoice("2", "Мария Иванова", uses = 2, category = "Люди", description = "Коллега с прошлой работы"),
        TagChoice("3", "Морковь", uses = 1),
        TagChoice("4", "Ёлка", uses = 1),
        TagChoice("5", "Benbow", uses = 9, category = "Проекты"),
        TagChoice("6", "Аморе", uses = 1),
        TagChoice("7", "Мораторий", uses = 1, archived = true),
    )

    private fun find(query: String) = TagSearch.find(query, tags).map { it.tag.name }

    @Test
    fun theBestMatchesComeFirst() {
        assertEquals(listOf("Море", "Морковь", "Мораторий", "Аморе"), find("мор"), "beginnings (the archived one after), then inside")
        assertEquals("Море", find("море").first(), "the whole name first")
        assertEquals(listOf("Мария Иванова"), find("ива"), "the beginning of any word")
        assertEquals(listOf("Мария Иванова"), find("мар ив"), "every word starts a word")
        assertEquals(listOf("Ёлка"), find("елка"), "ё as е")
        assertEquals(emptyList(), find("   "))
    }

    @Test
    fun wordFormsTyposAndTheWrongLayoutStillFind() {
        assertEquals("Море", find("моря").first(), "a word form")
        assertTrue("Benbow" in find("benbwo"), "two letters swapped")
        assertTrue("Benbow" in find("bembow"), "one letter wrong")
        assertEquals(listOf("Морковь"), find("марков"), "a slip in a longer word still being typed")
        assertFalse("Морковь" in find("море"), "a word form, not another word that starts alike")
        assertEquals("Море", find("vjht").first(), "typed in the latin layout")
        assertEquals("Benbow", find("иутищц").first(), "and the other way round")
        assertFalse("Benbow" in find("ben bo x"), "not every word matches")
    }

    @Test
    fun categoriesAndDescriptionsFindTooAfterNames() {
        val hits = TagSearch.find("люди", tags)
        assertEquals(listOf("Мария Иванова"), hits.map { it.tag.name })
        assertEquals(TagMatch.CATEGORY, hits.single().where)
        assertEquals(TagMatch.DESCRIPTION, TagSearch.find("коллега", tags).single().where)
        assertEquals(0 until 3, TagSearch.find("мор", tags).first().range, "the part of the name to show in bold")
    }

    @Test
    fun oneSlipAtMost() {
        assertTrue(TagSearch.withinOne("море", "море"))
        assertTrue(TagSearch.withinOne("мор", "море"))
        assertTrue(TagSearch.withinOne("омре", "море"))
        assertTrue(TagSearch.withinOne("мире", "море"))
        assertFalse(TagSearch.withinOne("мирэ", "море"))
        assertFalse(TagSearch.withinOne("мо", "море"))
    }

    @Test
    fun usageGivesTheNewestTagsAndWhatGoesTogether() {
        fun entry(day: String, at: Long, vararg tags: String) = EntrySummary(
            id = "$day-$at",
            day = day,
            createdAt = at,
            title = "",
            preview = "",
            conflict = false,
            tags = tags.map { TagRef(it, it) },
        )
        val usage = TagUsage(
            listOf(
                entry("2026-10-01", 1, "работа", "кофе"),
                entry("2026-10-03", 1, "маша", "кофе"),
                entry("2026-10-03", 2, "маша", "кино"),
                entry("2026-10-02", 1, "маша", "кофе"),
            ),
        )
        assertEquals(listOf("маша", "кино", "кофе", "работа"), usage.recent)
        assertEquals("2026-10-03", usage.lastDay["кофе"])
        assertEquals(listOf("кофе", "кино"), usage.together(setOf("маша")))
        assertEquals(emptyList(), usage.together(emptySet()))
    }
}
