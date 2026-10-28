package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The look of a note (ADR-047): one field per layer, in every list, a newer app's layers kept. */
class LookFieldsTest {
    private var time = 1_000L
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ })

    @Test
    fun eachLayerIsAFieldOfItsOwn() = runTest {
        val id = repo.createNote("2026-10-26", "Утро у моря")
        val before = db.benbowQueries.pendingCount().executeAsOne()
        repo.setLook(id, mapOf("paper" to "lines", "light" to "315 orange 2;45 blue 1"))
        assertEquals(before + 2, db.benbowQueries.pendingCount().executeAsOne(), "one op per layer, so devices merge layers")
        val look = mapOf("paper" to "lines", "light" to "315 orange 2;45 blue 1")
        assertEquals(look, repo.entry(id).first()!!.look)
        assertEquals(look, repo.journal().first().single().look)
        assertEquals(look, repo.feed().first().single().summary.look, "the feed draws it too")

        repo.setLook(id, mapOf("paper" to null, "scrap" to "tape.3"))
        assertEquals(mapOf("light" to "315 orange 2;45 blue 1", "scrap" to "tape.3"), repo.entry(id).first()!!.look)
    }

    @Test
    fun onlyTokensOfKnownLayersAreWritten() = runTest {
        val id = repo.createNote("2026-10-26", "Мысль")
        repo.setLook(id, mapOf("paper" to "<b>dots</b>", "colour" to "red", "pattern" to "x".repeat(500)))
        assertTrue(repo.entry(id).first()!!.look.isEmpty(), "no markup, no unknown layer, nothing endless")
    }

    @Test
    fun aNotesFontIsALayerOfItsLook() = runTest {
        val id = repo.createNote("2026-10-28", "Утро у моря")
        repo.setLook(id, mapOf("font" to "neucha", "paper" to "lines"))
        assertEquals(mapOf("font" to "neucha", "paper" to "lines"), repo.entry(id).first()!!.look)
        repo.setLook(id, LookFields.LAYERS.associateWith { null })
        assertTrue(repo.entry(id).first()!!.look.isEmpty(), "«Сбросить оформление» takes the font off too")
    }

    @Test
    fun aNewerAppsLayerIsKept() = runTest {
        val id = repo.createNote("2026-10-26", "Мысль")
        repo.setField(id, "look_picture", "fern") // as a newer app's op would arrive
        repo.setLook(id, mapOf("paper" to "dots"))
        assertEquals(mapOf("picture" to "fern", "paper" to "dots"), repo.entry(id).first()!!.look)
    }
}
