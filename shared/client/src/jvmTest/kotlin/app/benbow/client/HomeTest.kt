package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTest {
    private var time = 1_000L
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ })

    @Test
    fun onThisDayLooksOnlyAtEarlierYears() = runTest {
        repo.createNote("2024-10-13", "Два года назад")
        repo.createNote("2025-10-13", "Год назад")
        repo.createNote("2025-10-14", "Не тот день")
        repo.createNote("2026-10-13", "Сегодня")
        assertEquals(listOf("Год назад", "Два года назад"), repo.onThisDay("2026-10-13").first().map { it.title })
    }

    @Test
    fun draftsPinnedFavoritesAndDaysWritten() = runTest {
        val a = repo.createNote("2026-08-01", "Старое избранное")
        val b = repo.createNote("2026-10-12", "Свежее избранное")
        val c = repo.createNote("2026-10-13", "Закреп")
        repo.createNote("2026-10-13", "Ещё одна сегодня")
        repo.setFavorite(a, true)
        repo.setFavorite(b, true)
        repo.setPinned(c, true)
        repo.saveDraft(b, JournalRepository.textBody("Свежее избранное, дописываю"))

        assertEquals(listOf("Закреп"), repo.pinned().first().map { it.title })
        assertEquals(listOf("Свежее избранное"), repo.withDrafts().first().map { it.title })
        assertEquals(listOf("Старое избранное"), repo.favoritesUpTo("2026-09-13").first().map { it.title }, "resurfacing skips recent ones")
        assertEquals(2, repo.daysWritten("2026-10-01", "2026-10-31").first(), "distinct days, not entries")
        repo.commitDraft(b)
        assertEquals(emptyList(), repo.withDrafts().first())
    }

    @Test
    fun theFeedShowsNotesInFullAndJournalEntriesAsPreviews() = runTest {
        val note = repo.createNote("2026-10-13", "Первая строка\nвторая\nтретья")
        repo.createEntry("2026-10-12", body = JournalRepository.textBody("Длинная запись\nс продолжением"))
        repo.toggleReaction(note, "🔥", on = true)
        val feed = repo.feed().first()
        assertEquals(listOf("note", "journal"), feed.map { it.kind }, "newest first")
        assertEquals("Первая строка\nвторая\nтретья", feed[0].text)
        assertEquals(setOf("🔥"), feed[0].reactions)
        assertEquals("Длинная запись\nс продолжением", feed[1].text)
    }
}
