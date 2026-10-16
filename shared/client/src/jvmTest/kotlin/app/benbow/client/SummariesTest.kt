package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class SummariesTest {
    private var time = 1_000L
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ })

    private val body = DocumentCodec.encode(
        Document(
            listOf(
                TextBlock("a", children = listOf(Span("The doctor said "), Span("it is serious", listOf("spoiler")), Span(" today"))),
                TextBlock("b", children = listOf(Span("We walked "), Span("and cried", listOf("spoiler")), Span(" by the sea"))),
            ),
        ),
    )

    @Test
    fun aSpoilerStaysOutOfTheTitleAsWellAsThePreview() = runTest {
        repo.createEntry("2026-10-16", kind = "note", body = body)
        val entry = repo.journal().first().single()
        assertEquals("The doctor said today", entry.title)
        assertEquals("We walked by the sea", entry.preview)
    }

    @Test
    fun titlesMadeTheOldWayAreMadeAgainOnce() = runTest {
        val id = repo.createEntry("2026-10-16", kind = "note", body = body)
        repo.prepareSummaries() // a fresh journal: made the new way already, now marked as such
        db.benbowQueries.updateSummary("The doctor said it is serious today", "The doctor said today We walked by the sea", id)
        repo.prepareSummaries()
        assertEquals("The doctor said it is serious today", repo.journal().first().single().title, "only once per version")

        Settings(db).remove("summaries.version")
        repo.prepareSummaries()
        assertEquals("The doctor said today", repo.journal().first().single().title)
        assertEquals("We walked by the sea", repo.journal().first().single().preview)
    }
}
