package app.benbow.core.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagBookTest {
    private val registry = mapOf(
        // A tag from before ids: its category written by name, its name only on its entries.
        "cat:море" to "Места",
        "color:море" to "fog",
        // Tags with ids.
        "name:t1" to "Мария",
        "cat:t1" to "люди",
        "desc:t1" to "Коллега\nс прошлой работы",
        "name:t2" to "Маша",
        "into:t2" to "t1",
        "name:t3" to "Старый проект",
        "archived:t3" to "true",
        "name:t4" to "Утрата",
        "trash:t4" to "1",
        // Categories.
        "catname:c1" to "Спорт",
        "catemoji:c1" to "🏃",
        "catcolor:c1" to "sand",
        "catsort:c1" to "-1",
        "catname:c2" to "Хобби",
        "cattrash:c2" to "5",
        "name:x" to null,
    )
    private val book = TagBook.read(registry, written = mapOf("море" to "Море", "кафе" to "Кафе"))

    @Test
    fun everyTagAndCategoryIsThereWithWhatTheAppKnows() {
        assertEquals(
            listOf("c1", "люди", "места", "проекты", "эмоции", "c2"),
            book.categories.map {
                it.id
            },
            "in their order, the trash last",
        )
        assertEquals(
            listOf("Кафе", "Мария", "Маша", "Море", "Старый проект", "Утрата"),
            book.tags.map { it.name },
            "with names of their own, else as their entries hold them",
        )
        val sea = book.tags.single { it.id == "море" }
        assertEquals("места", sea.category)
        assertEquals("fog", sea.color)
    }

    @Test
    fun theJsonHasEverythingAsStored() {
        val json = Json.parseToJsonElement(TagBook.json(book)).jsonObject
        assertEquals("benbow-tags/1", json.getValue("format").jsonPrimitive.content)
        val tags = json.getValue("tags").jsonArray.map { it.jsonObject }
        val masha = tags.single { it.getValue("id").jsonPrimitive.content == "t2" }
        assertEquals("t1", masha.getValue("merged_into").jsonPrimitive.content)
        assertTrue(tags.single { it.getValue("id").jsonPrimitive.content == "t4" }.getValue("trash").jsonPrimitive.boolean)
        val hobby = json.getValue("categories").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "c2" }
        assertTrue(hobby.getValue("trash").jsonPrimitive.boolean)
    }

    @Test
    fun theMarkdownReadsAsTheAppShowsIt() {
        val md = TagBook.markdown(book)
        assertTrue("## 🏃 Спорт (sand)\n\n_No tags yet._" in md, md)
        assertTrue("## Люди\n\n- **Мария** — Коллега с прошлой работы\n" in md, md)
        assertTrue("## Места\n\n- **Море** (fog)\n" in md, md)
        assertTrue("## No category\n\n- **Кафе**\n" in md, md)
        assertTrue("## Archived\n\n- **Старый проект**\n" in md, md)
        assertTrue("## Merged\n\n- **Маша** → **Мария**\n" in md, md)
        assertTrue("## In the trash\n\n- Category **Хобби**\n- Tag **Утрата**\n" in md, md)
    }
}
