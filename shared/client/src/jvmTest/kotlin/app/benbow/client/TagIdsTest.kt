package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutRevision
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncOp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Tags by id (ADR-032): tags from before ids keep their key as their id, so old journals read exactly as they did. */
class TagIdsTest {
    private var time = 1_000L

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val repo = JournalRepository(db, { time++ })
        val engine = SyncEngine(db, { FakeApi(server) }, { time++ })

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }

        suspend fun veils() = repo.journal().first().associate { it.title to it.veil }

        suspend fun find(query: SearchQuery) = repo.search(query).first().map { it.title }.sorted()

        suspend fun names() = repo.journal().first().associate { it.title to it.tags.map(TagRef::name) }
    }

    private var hlc = 0L

    /** Ops as an app version from before ids sent them: tags and categories keyed and named by their names. */
    private fun older(vararg ops: SyncOp) = ops.toList()

    private fun field(entry: String, field: String, value: String?) = SetField("op-${++hlc}", entry, field, value, Hlc(hlc, 0, "old-phone"))

    private fun note(id: String, day: String, text: String, vararg tags: String) = older(
        CreateEntry("c-$id", id, "journal", day, 1),
        PutRevision("r-$id", id, "rev-$id", emptyList(), JournalRepository.textBody(text)),
    ) + tags.map { field(id, "tag:" + it.lowercase(), it) }

    /** A journal written before ids: «Море» (written in two cases) in «Места», «Утрата» quiet by a rule, «Места» curtained. */
    private fun olderJournal(server: FakeServer) {
        server.rules.push(
            note("e1", "2026-10-01", "Вечер у моря", "Море") +
                note("e2", "2026-10-02", "Опять волны", "море", "Утрата") +
                older(
                    CreateEntry("c-tags", TAG_REGISTRY_ID, "system", "1970-01-01", 1),
                    field(TAG_REGISTRY_ID, "cat:море", "Места"),
                    field(TAG_REGISTRY_ID, "color:утрата", "fog"),
                    field(TAG_REGISTRY_ID, "catcolor:места", "sand"),
                    CreateEntry("c-gentle", GENTLE_REGISTRY_ID, "system", "1970-01-01", 1),
                    field(
                        GENTLE_REGISTRY_ID,
                        "rule:g1",
                        GentleRule("g1", "Утрата", GentleRule.TAG, value = "Утрата", level = GentleRule.QUIET).encode(),
                    ),
                    field(GENTLE_REGISTRY_ID, "rule:g2", GentleRule("g2", "Места", GentleRule.CATEGORY, value = "Места").encode()),
                ),
        )
    }

    @Test
    fun anOlderJournalReadsAsItDidWithoutWritingAnything() = runTest {
        val server = FakeServer()
        olderJournal(server)
        val phone = Device(server, "phone")
        phone.engine.sync()
        val ops = server.log.size

        assertEquals(
            listOf(TagCount("море", "Море", 2, "места"), TagCount("утрата", "Утрата", 1)),
            phone.repo.tags().first(),
            "the old key is the id; of two spellings the one that sorts first",
        )
        assertEquals(mapOf("Вечер у моря" to listOf("Море"), "Опять волны" to listOf("море", "Утрата")), phone.names(), "each as written")
        val registry = phone.repo.tagRegistry().first()
        assertEquals("fog", registry.color("утрата"))
        assertEquals("sand", registry.color("море"), "its category's color")
        assertEquals("Места", registry.categoryName("места"))
        assertEquals(mapOf("Вечер у моря" to Veil.Curtain, "Опять волны" to Veil.Quiet), phone.veils(), "rules by name still hold")
        assertEquals(listOf("Вечер у моря", "Опять волны"), phone.find(SearchQuery("море")))
        assertEquals(listOf("Вечер у моря", "Опять волны"), phone.find(SearchQuery(tag = "море")))
        assertEquals(0, phone.repo.pendingCount().first(), "reading an older journal changes nothing")
        assertEquals(ops, server.log.size)

        val later = phone.repo.createNote("2026-10-03", "Снова на берегу")
        assertEquals("море", phone.repo.addTag(later, "МОРЕ"), "a name finds the older tag")
        assertEquals(emptyMap(), phone.repo.tagRegistry().first().names, "which needs no name of its own")
        val coffee = phone.repo.addTag(later, "Кофе")!!
        assertNotEquals("кофе", coffee, "a new tag gets an id of its own")
        assertEquals("Кофе", phone.repo.tagRegistry().first().names[coffee])
        assertEquals(coffee, phone.repo.addTag(phone.repo.createNote("2026-10-04", "Утро"), "кофе"), "and is found by its name")
    }

    @Test
    fun aRenameShowsEverywhereAndNoRuleLetsGo() = runTest {
        val server = FakeServer()
        olderJournal(server)
        val phone = Device(server, "phone")
        val desktop = Device(server, "desktop")
        phone.engine.sync()
        desktop.engine.sync()

        phone.repo.setField(TAG_REGISTRY_ID, "name:море", "Океан")
        phone.repo.setField(TAG_REGISTRY_ID, "name:утрата", "Потеря")
        phone.engine.sync()
        desktop.engine.sync()
        server.rules.push(note("e3", "2026-10-05", "Шторм", "Море")) // an app from before ids, by the old name
        desktop.engine.sync()

        for (device in listOf(phone, desktop)) {
            device.engine.sync()
            assertEquals(
                mapOf("Вечер у моря" to listOf("Океан"), "Опять волны" to listOf("Океан", "Потеря"), "Шторм" to listOf("Океан")),
                device.names(),
            )
            assertEquals(listOf("Вечер у моря", "Опять волны", "Шторм"), device.find(SearchQuery("океан")), "the index follows")
            assertEquals(listOf("Опять волны"), device.find(SearchQuery("потеря")))
            assertEquals(emptyList(), device.find(SearchQuery("утрата")), "the old name is gone from search")
            assertEquals(
                mapOf("Вечер у моря" to Veil.Curtain, "Опять волны" to Veil.Quiet, "Шторм" to Veil.Curtain),
                device.veils(),
                "a rule by the old name keeps its tag",
            )
            assertEquals(listOf("Океан" to 3, "Потеря" to 1), device.repo.tags().first().map { it.name to it.uses })
        }
        assertEquals(listOf("Океан"), phone.repo.entry("e1").first()!!.tags.map { it.name }, "the editor too")
    }

    @Test
    fun aRuleByNameStillCoversATagWithANewId() {
        val registry =
            TagRegistry(names = mapOf("x1" to "Кофе"), tagCategories = mapOf("x1" to "c1"), categoryNames = mapOf("c1" to "Утро"))
        val tags = listOf(TagRef("x1", "Кофе"))
        val byName = GentleRules(listOf(GentleRule("r", "Кофе", GentleRule.TAG, value = "кофе")), registry)
        assertEquals(Veil.Curtain, byName.ruled("2026-10-01", tags), "rules made by an older app name the tag")
        val byCategory = GentleRules(listOf(GentleRule("r", "Утро", GentleRule.CATEGORY, value = "Утро")), registry)
        assertEquals(Veil.Curtain, byCategory.ruled("2026-10-01", tags))
        val byId = GentleRules(listOf(GentleRule("r", "Кофе", GentleRule.TAG, value = "x1")), registry)
        assertEquals(Veil.Curtain, byId.ruled("2026-10-01", tags))
        assertTrue(GentleRules(listOf(GentleRule("r", "Чай", GentleRule.TAG, value = "чай")), registry).ruled("2026-10-01", tags) == null)
    }
}
