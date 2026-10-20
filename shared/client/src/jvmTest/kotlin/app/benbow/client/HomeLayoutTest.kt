package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    @Test
    fun theEditorShowsAtOnceAndWritesInOrder() = runTest {
        val editor = HomeLayoutEditor(repo, CoroutineScope(Dispatchers.Unconfined), repo.homeLayout().first())
        editor.move("stats", -1)
        editor.move("stats", -1)
        editor.move("checkin", -1) // already first
        assertEquals(listOf("checkin", "prompt", "pinned", "today", "drafts", "stats", "on-this-day", "favorite"), ids(editor.layout.value))
        assertEquals(editor.layout.value, repo.homeLayout().first())

        val (drafts, at) = editor.remove("drafts")!!
        assertEquals(null, repo.homeLayout().first().blocks.find { it.id == "drafts" })
        assertEquals(
            listOf(HomeBlockType.Drafts, HomeBlockType.Notes, HomeBlockType.Tag),
            editor.layout.value.addable(),
            "a tag block can always come",
        )
        editor.put(drafts, at)
        assertEquals(editor.layout.value, repo.homeLayout().first())
        assertEquals("drafts", editor.layout.value.blocks[4].id, "back in its place")

        editor.remove("prompt")
        val prompt = editor.add(HomeBlockType.Prompt, after = "checkin")
        assertEquals("prompt", prompt.id, "the kind's own id, so two devices adding it make one block")
        assertEquals("prompt", repo.homeLayout().first().blocks[1].id)

        val before = editor.reset()
        assertEquals(HomeLayout.DEFAULT, repo.homeLayout().first())
        editor.restore(before)
        assertEquals(before, repo.homeLayout().first().blocks)
        editor.close()
    }

    @Test
    fun movesStepOverBlocksThisAppCantDraw() {
        val a = HomeBlock.of(HomeBlockType.Checkin)
        val b = HomeBlock.of(HomeBlockType.Prompt)
        val unknown = HomeBlock("x", "letter", "tall")
        val blocks = listOf(a, unknown, b)
        assertEquals(listOf(b, a, unknown), blocks.moved(b.id, -1), "one step for the eye")
        assertEquals(listOf(unknown, b, a), blocks.moved(a.id, 1))
        assertEquals(blocks, blocks.moved(b.id, 1), "already last")
        assertEquals(blocks, blocks.plusBlock(a, 0), "no block twice")
    }

    @Test
    fun anEditMadeOnAnotherDeviceMeanwhileStays() = runTest {
        val editor = HomeLayoutEditor(repo, CoroutineScope(Dispatchers.Unconfined), repo.homeLayout().first())
        editor.move("today", -1)
        repo.editHomeLayout { it.minusBlock("favorite") } // synced from elsewhere while the editor is open
        editor.move("stats", -1)
        assertEquals(null, repo.homeLayout().first().blocks.find { it.id == "favorite" }, "each write goes to the stored layout")
        assertEquals(listOf("checkin", "prompt", "today", "pinned", "drafts", "stats", "on-this-day"), ids(repo.homeLayout().first()))
        editor.close()
    }

    @Test
    fun tagBlocksComeManyTimesWithTheirSizesAndSettings() = runTest {
        val editor = HomeLayoutEditor(repo, CoroutineScope(Dispatchers.Unconfined), repo.homeLayout().first())
        val sea = editor.add(HomeBlockType.Tag, after = null, config = mapOf(HomeBlock.TAG to "море"))
        val music = editor.add(HomeBlockType.Tag, after = sea.id, config = mapOf(HomeBlock.TAG to "музыка"))
        assertTrue(sea.id != music.id && sea.id != "tag", "each its own id")
        assertEquals(BlockSize.M, sea.blockSize, "a strip to start with")
        assertTrue(HomeBlockType.Tag in editor.layout.value.addable(), "a tag block per tag")

        editor.resize(sea.id, BlockSize.S)
        editor.configure(sea.id, HomeBlock.ORDER, HomeBlock.RANDOM)
        editor.resize(music.id, BlockSize.List) // not a size it comes in: kept, not drawn
        val stored = repo.homeLayout().first().blocks
        assertEquals(
            HomeBlock(sea.id, "tag", "s", mapOf(HomeBlock.TAG to "море", HomeBlock.ORDER to HomeBlock.RANDOM)),
            stored.single {
                it.id ==
                    sea.id
            },
        )
        assertEquals(null, stored.single { it.id == music.id }.blockSize)

        editor.configure(sea.id, HomeBlock.ORDER, null)
        assertEquals(mapOf(HomeBlock.TAG to "море"), repo.homeLayout().first().blocks.single { it.id == sea.id }.config)
        editor.close()
    }
}
