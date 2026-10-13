package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteBackgroundTest {
    private var time = 1_000L
    private val repo = JournalRepository(BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema)), { time++ })

    @Test
    fun aNoteKeepsItsKeepColor() = runTest {
        val id = repo.createNote("2026-10-13", "Список покупок")
        repo.setBackground(id, "mint")
        assertEquals("mint", repo.journal().first().single().background)
        assertEquals("mint", repo.entry(id).first()!!.background)
        assertEquals("mint", repo.feed().first().single().summary.background, "the feed shows it too")

        repo.setBackground(id, "#ff0000") // only palette names: they follow the theme, hex wouldn't
        assertEquals(null, repo.journal().first().single().background)
    }
}
