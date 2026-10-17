package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeLayoutTest {
    private var time = 1_000L
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ })

    private fun ids(layout: HomeLayout) = layout.blocks.map { it.id }

    @Test
    fun untouchedHomeIsTheDefaultAndStoresNothing() = runTest {
        val layout = repo.homeLayout().first()
        assertEquals(HomeLayout.DEFAULT, layout)
        assertFalse(layout.custom)
        assertEquals(null, db.benbowQueries.selectEntry(HOME_REGISTRY_ID).executeAsOneOrNull(), "nothing written until changed")
        assertEquals(listOf("checkin", "prompt", "pinned", "today", "drafts", "on-this-day", "favorite", "stats"), ids(layout))
    }

    @Test
    fun theFirstChangeWritesTheWholeLayoutAndLaterOnesOnlyWhatChanged() = runTest {
        val default = repo.homeLayout().first()
        val stats = default.blocks.single { it.id == "stats" }
        repo.saveHomeLayout(default, listOf(stats) + default.blocks.filter { it.id != "stats" && it.id != "drafts" })
        val saved = repo.homeLayout().first()
        assertTrue(saved.custom)
        assertEquals(listOf("stats", "checkin", "prompt", "pinned", "today", "on-this-day", "favorite"), ids(saved))

        val outbox = db.benbowQueries.pendingCount().executeAsOne()
        val today = saved.blocks.single { it.id == "today" }
        repo.saveHomeLayout(saved, saved.blocks.map { if (it == today) it.copy(config = mapOf("max" to "3")) else it })
        assertEquals(outbox + 1, db.benbowQueries.pendingCount().executeAsOne(), "one block's field, the order unchanged")
        assertEquals(mapOf("max" to "3"), repo.homeLayout().first().blocks.single { it.id == "today" }.config)
    }

    @Test
    fun blocksFromAnotherDeviceOrANewerAppStay() = runTest {
        val default = repo.homeLayout().first()
        repo.saveHomeLayout(default, default.blocks)
        val seen = repo.homeLayout().first()
        // Meanwhile another device adds a block of a kind this app doesn't know yet.
        repo.setRegistryField(BLOCK_PREFIX + "x1", """{"type":"letter","size":"tall","config":{"to":"2030"},"more":1}""", HOME_REGISTRY_ID)
        val withLetter = repo.homeLayout().first()
        assertEquals("x1", withLetter.blocks.last().id, "a block the order doesn't name yet comes last")
        assertEquals(null, withLetter.blocks.last().blockSize, "not drawn here")
        assertEquals(8, packHome(withLetter.blocks).size, "takes no room")

        // An edit made from what was seen before it arrived leaves it alone.
        repo.saveHomeLayout(seen, seen.blocks.reversed().filter { it.id != "prompt" })
        val after = repo.homeLayout().first()
        assertEquals(listOf("stats", "favorite", "on-this-day", "drafts", "today", "pinned", "checkin", "x1"), ids(after))
        assertEquals(mapOf("to" to "2030"), after.blocks.last().config)

        // And editing around it, now that it's known, never rewrites it.
        val letterField = { db.benbowQueries.selectEntry(HOME_REGISTRY_ID).executeAsOne().fields.let(::decodeFields)[BLOCK_PREFIX + "x1"] }
        val stored = letterField()
        repo.saveHomeLayout(after, after.blocks.reversed())
        assertEquals(stored, letterField())
        assertEquals("x1", repo.homeLayout().first().blocks.first().id)
    }

    @Test
    fun resetBringsTheDefaultBack() = runTest {
        val default = repo.homeLayout().first()
        repo.saveHomeLayout(default, default.blocks.take(2))
        assertEquals(2, repo.homeLayout().first().blocks.size)
        repo.resetHomeLayout()
        assertEquals(HomeLayout.DEFAULT, repo.homeLayout().first())
    }

    @Test
    fun anEmptiedHomeStaysEmpty() = runTest {
        val default = repo.homeLayout().first()
        repo.saveHomeLayout(default, emptyList())
        val layout = repo.homeLayout().first()
        assertTrue(layout.custom)
        assertEquals(emptyList(), layout.blocks)
    }

    @Test
    fun rowsFillInOrderAndAListTakesARowOfItsOwn() {
        val (s, m, l, list) = listOf(BlockSize.S, BlockSize.M, BlockSize.L, BlockSize.List)
        val blocks = listOf(m, s, s, s, list, s, l)
        assertEquals(
            listOf(listOf(m), listOf(s, s), listOf(s), listOf(list), listOf(s), listOf(l)),
            packRows(blocks, 2) { it.span(2) },
            "a lone S keeps its row; nothing is moved to fill a gap",
        )
        assertEquals(
            listOf(listOf(m, s, s), listOf(s), listOf(list), listOf(s, l)),
            packRows(blocks, 4) { it.span(4) },
            "a wide window: the same order, more in a row",
        )
    }
}
