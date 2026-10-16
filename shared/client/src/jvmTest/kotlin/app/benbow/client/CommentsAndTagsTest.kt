package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommentsAndTagsTest {
    private var time = 1_000L

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val repo = JournalRepository(db, { time++ })
        val engine = SyncEngine(db, { FakeApi(server) }, { time++ })

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }
    }

    @Test
    fun aThreadLivesUnderItsEntryNotBesideIt() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val entry = phone.repo.createNote("2025-10-13", "Год назад: начал дневник")
        phone.repo.createComment(entry, "2026-10-13", "Спустя год: не бросил!")

        assertEquals(listOf("Спустя год: не бросил!"), phone.repo.comments(entry).first().map { it.text })
        assertEquals(1, phone.repo.commentCount(entry).first())
        assertEquals(listOf("Год назад: начал дневник"), phone.repo.journal().first().map { it.title }, "comments aren't journal items")
        val feed = phone.repo.feed().first()
        assertEquals(listOf(1), feed.map { it.comments })
        assertEquals(
            listOf("Спустя год: не бросил!"),
            phone.repo.search(SearchQuery("бросил")).first().map {
                it.title
            },
            "but they are searchable",
        )

        phone.engine.sync()
        val desktop = Device(server, "desktop")
        desktop.engine.sync()
        assertEquals(listOf("Спустя год: не бросил!"), desktop.repo.comments(entry).first().map { it.text })
    }

    @Test
    fun tagCategoriesFromTwoDevicesMergeWithoutConflict() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val desktop = Device(server, "desktop")
        val a = phone.repo.createNote("2026-10-13", "Обед с Машей")
        phone.repo.addTag(a, "Маша")
        phone.repo.addTag(a, "Кафе")
        phone.engine.sync()
        desktop.engine.sync()

        val ids = desktop.repo.tags().first().associate { it.name to it.id }
        phone.repo.setTagCategory(ids.getValue("Маша"), "люди") // both devices change the registry offline
        desktop.repo.setTagCategory(ids.getValue("Кафе"), "места")
        phone.engine.sync()
        desktop.engine.sync()
        phone.engine.sync()

        val expected = mapOf("Кафе" to "места", "Маша" to "люди")
        assertEquals(expected, phone.repo.tags().first().associate { it.name to it.category })
        assertEquals(expected, desktop.repo.tags().first().associate { it.name to it.category })
        val registry = server.store.entry(TAG_REGISTRY_ID)!!
        assertEquals(null, registry.head, "no revisions, so nothing to conflict over")
        assertTrue(registry.conflictHeads.isEmpty())
        assertEquals(listOf("Обед с Машей"), phone.repo.journal().first().map { it.title }, "the registry is not a note")
        assertEquals(1, phone.repo.feed().first().size)
    }

    @Test
    fun tagColorsComeFromTheTagThenItsCategoryAndSync() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val desktop = Device(server, "desktop")
        phone.repo.setTagCategory("маша", "люди")
        phone.repo.setTagCategory("кафе", "места")
        phone.repo.setTagCategory("benbow", "хобби")
        phone.repo.setCategoryColor("места", "sand")
        phone.repo.setTagColor("маша", "coral")
        phone.repo.setTagColor("кафе", "not-a-color") // ignored, stays as its category
        phone.engine.sync()
        desktop.engine.sync()

        val looks = desktop.repo.tagRegistry().first()
        assertEquals("coral", looks.color("маша"), "a tag's own color wins")
        assertEquals("sand", looks.color("кафе"), "then its category's")
        assertEquals("storm", looks.categoryColor("люди"), "built-in categories have defaults")
        assertTrue(looks.color("benbow") in SWATCHES, "other categories get one from their id")
        assertEquals(null, looks.color("без категории"), "a free tag stays neutral")

        desktop.repo.setTagColor("маша", null)
        desktop.engine.sync()
        phone.engine.sync()
        assertEquals("storm", phone.repo.tagRegistry().first().color("маша"), "back to the category color")
    }
}
