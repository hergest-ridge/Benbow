package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The feed over the whole journal (ADR-036): every post's place in the index, the posts in full by window. */
class FeedIndexTest {
    private var time = 1_000L
    private val repo = JournalRepository(
        BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema)),
        { time++ },
        today = { "2026-10-17" },
    )

    @Test
    fun theIndexHoldsEveryPostNewestFirstAndNothingElse() = runTest {
        // Far more than the 300 the feed used to stop at.
        val ids = (0 until 420).map { i -> repo.createNote(day(i), "Запись $i") }
        repo.createComment(ids[0], day(0), "Ответ")
        repo.createCheckin(day(0), 4, listOf("спокойствие"), "")
        val sameDay = repo.createNote(day(0), "Ещё одна сегодня")
        repo.moveToTrash(ids[1])

        val keys = repo.feedIndex().first()
        assertEquals(sameDay, keys.first().id, "the latest of today on top")
        assertEquals(ids[417], keys.last().id, "down to the very first post (the first of the last day's three)")
        assertFalse(keys.any { it.id == ids[1] }, "nothing from the trash")
        assertEquals(420 + 1, keys.size, "no comments; a check-in is a post like any other")
        assertEquals(keys.sortedWith(compareByDescending<FeedKey> { it.day }.thenByDescending { it.createdAt }), keys)
    }

    @Test
    fun theIndexVeilsLikeTheListsDo() = runTest {
        val plain = repo.createNote("2026-10-10", "Прогулка")
        val tagged = repo.createNote("2026-10-11", "Похороны")
        val grief = repo.addTag(tagged, "Утрата")!!
        repo.setGentleRule(GentleRule("r", "Утрата", GentleRule.TAG, value = grief, level = GentleRule.QUIET))
        val own = repo.createNote("2026-10-12", "Ссора")
        repo.setVeil(own, Veil.Curtain)
        val later = repo.createNote("2026-10-13", "Письмо себе")
        repo.setVeilUntil(later, "2026-12-01")
        val spring = repo.createNote("2024-04-02", "Больница")
        repo.setGentleRule(GentleRule("p", "Весна 2024", GentleRule.PERIOD, from = "2024-03-01", to = "2024-05-31"))

        val veils = repo.feedIndex().first().associate { it.id to it.veil }
        assertNull(veils[plain])
        assertEquals(Veil.Quiet, veils[tagged], "a tag's rule")
        assertEquals(Veil.Curtain, veils[own], "its own mark")
        assertEquals(Veil.Quiet, veils[later], "put off until December")
        assertEquals(Veil.Curtain, veils[spring], "a period's rule")
        val full = repo.feedItems(veils.keys.toList()).first().associate { it.summary.id to it.summary.veil }
        assertEquals(veils, full, "the posts in full agree")
    }

    @Test
    fun aWindowComesInTheOrderAskedAndKnowsItsPictures() = runTest {
        val a = repo.createNote("2026-10-01", "Первая")
        val b = repo.createNote("2026-10-02", "Вторая")
        val c = repo.createNote("2026-10-03", "Третья")
        repo.attach(b, MediaFile("ab".repeat(32), 10, "image/jpeg", "/media/b.jpg", "b.jpg", null), "image")

        assertEquals(listOf(a, c), repo.feedItems(listOf(a, c)).first().map { it.summary.id })
        assertEquals(listOf(c, a), repo.feedItems(listOf(c, a, "gone")).first().map { it.summary.id }, "a missing one is left out")
        assertEquals(emptyList(), repo.feedItems(emptyList()).first())
        val media = repo.feedIndex().first().associate { it.id to it.media }
        assertTrue(media.getValue(b))
        assertFalse(media.getValue(a))
        assertEquals(listOf(c, b), repo.feed(limit = 2).first().map { it.summary.id }, "the first window")
    }

    /** Three posts a day, back from today. */
    private fun day(i: Int) = LocalDate(2026, 10, 17).minus(i / 3, DateTimeUnit.DAY).toString()
}
