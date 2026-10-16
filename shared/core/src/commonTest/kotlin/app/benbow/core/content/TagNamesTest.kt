package app.benbow.core.content

import kotlin.test.Test
import kotlin.test.assertEquals

class TagNamesTest {
    private val fields = mapOf("tag:a" to "А", "tag:b" to "Б", "tag:c" to "В", "tag:gone" to null)

    @Test
    fun mergedTagsReadAsTheirTargetOnceAndTrashedOnesNotAtAll() {
        val tags = TagNames.of(mapOf("name:b" to "Бэ", "into:a" to "b", "trash:c" to "1", "into:x" to null))
        assertEquals("b", tags.root("a"))
        assertEquals(listOf("b" to "Бэ"), tags.on(fields), "a reads as b, once; c is in the trash; a taken-off tag is no tag")
        assertEquals(listOf("a" to "А", "b" to "Б", "c" to "В"), TagNames().on(fields), "without a registry each reads as written")
    }

    @Test
    fun aCircleOfMergesEndsAtItsSmallestIdEverywhere() {
        val tags = TagNames(into = mapOf("b" to "c", "c" to "a", "a" to "b", "d" to "b"))
        assertEquals(listOf("a", "a", "a", "a"), listOf("a", "b", "c", "d").map(tags::root))
    }

    @Test
    fun changedNamesTheTagsThatReadDifferently() {
        val before = TagNames(names = mapOf("a" to "А", "b" to "Б"))
        assertEquals(setOf("a"), TagNames.changed(before, before.copy(names = mapOf("a" to "Альфа", "b" to "Б"))))
        assertEquals(setOf("a"), TagNames.changed(before, before.copy(into = mapOf("a" to "b"))))
        val merged = before.copy(into = mapOf("a" to "b"))
        assertEquals(setOf("a", "b"), TagNames.changed(merged, merged.copy(trashed = setOf("b"))), "b's trash takes a along")
        assertEquals(emptySet(), TagNames.changed(before, before))
    }
}
