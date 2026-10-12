package app.benbow.core.sync

import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeTest {
    @Test
    fun diff3CombinesIndependentChanges() {
        val base = listOf("a", "b", "c", "d")
        assertEquals(listOf("A", "b", "c", "D"), diff3(base, listOf("A", "b", "c", "d"), listOf("a", "b", "c", "D")))
        assertEquals(listOf("a", "x", "b", "c", "d", "y"), diff3(base, listOf("a", "x", "b", "c", "d"), listOf("a", "b", "c", "d", "y")))
        assertEquals(listOf("a", "c"), diff3(base, listOf("a", "c", "d"), listOf("a", "b", "c")))
    }

    @Test
    fun diff3ReportsOverlappingChanges() {
        assertNull(diff3(listOf("a", "b"), listOf("a", "X"), listOf("a", "Y")))
        assertEquals(listOf("a", "Z"), diff3(listOf("a", "b"), listOf("a", "Z"), listOf("a", "Z")))
    }

    @Test
    fun documentsMergeByBlock() {
        val base = doc("k1" to "Утро", "k2" to "День")
        val ours = doc("k1" to "Утро, туман", "k2" to "День")
        val theirs = doc("k1" to "Утро", "k2" to "День", "k3" to "Вечер")
        val merged = mergeDocumentBodies(base, ours, theirs)
        assertEquals(doc("k1" to "Утро, туман", "k2" to "День", "k3" to "Вечер"), merged)
    }

    @Test
    fun sameBlockEditedTwiceIsAConflict() {
        val base = doc("k1" to "Утро")
        assertNull(mergeDocumentBodies(base, doc("k1" to "Утро!"), doc("k1" to "Утро?")))
    }

    @Test
    fun duplicateKeysAreAConflict() {
        val base = doc("k1" to "a")
        assertNull(mergeDocumentBodies(base, doc("k1" to "a", "k2" to "x"), doc("k1" to "a", "k2" to "y", "k3" to "z")).also { assertTrue(it == null) })
    }

    @Test
    fun hlcOrdersAcrossDriftingClocks() {
        var phoneTime = 1_000L
        val phone = HlcClock("phone") { phoneTime }
        val desktop = HlcClock("desktop") { 500L }
        val a = phone.next()
        desktop.observe(a)
        val b = desktop.next()
        assertTrue(b > a, "a write made after seeing another must sort later even with a slow clock")
        phoneTime = 900L
        assertTrue(phone.next() > a)
    }

    private fun doc(vararg blocks: Pair<String, String>): String =
        DocumentCodec.encode(Document(blocks.map { (key, text) -> TextBlock(key, children = listOf(Span(text))) }))
}
