package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckinTest {
    private var time = 1_000L
    private val repo = JournalRepository(BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema)), { time++ })

    @Test
    fun aCheckinKeepsMoodAndNamesFeelingsAsEmotionTags() = runTest {
        repo.createCheckin("2026-10-13", -1, listOf("усталость", "тревога"), "")
        val entry = repo.journal().first().single()
        assertEquals(-1, entry.mood)
        assertEquals("усталость, тревога", entry.title, "the words stand in when nothing was written")
        assertEquals(listOf("тревога", "усталость"), entry.tags.map { it.name })
        assertEquals(setOf(EMOTIONS), repo.tags().first().map { it.category }.toSet())
        assertEquals(-1.0, repo.monthDays("2026-10-01", "2026-10-31").first().getValue("2026-10-13").mood, "it tints the calendar")
    }

    @Test
    fun ownWordsComeFirst() = runTest {
        repo.createCheckin("2026-10-13", 2, listOf("радость"), "Сдал экзамен!")
        assertEquals("Сдал экзамен!", repo.journal().first().single().title)
    }
}
