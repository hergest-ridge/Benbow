package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tags and categories as things of their own (7.9 T2, ADR-032): every step is a registry field, so entries never
 * change, every step can be taken back, and nothing done to tags lets an entry out from under a Бережно rule.
 */
class TagEditingTest {
    private var time = 1_000L

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val repo = JournalRepository(db, { time++ })
        val engine = SyncEngine(db, { FakeApi(server) }, { time++ })

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }

        suspend fun names() = repo.journal().first().associate { it.title to it.tags.map(TagRef::name) }

        suspend fun veils() = repo.journal().first().associate { it.title to it.veil }

        suspend fun find(query: SearchQuery) = repo.search(query).first().map { it.title }.sorted()

        suspend fun list() = repo.tags().first().map { it.name to it.uses }

        suspend fun fields(id: String) = repo.entry(id).first()!!.fields
    }

    private val server = FakeServer()
    private val phone = Device(server, "phone")

    @Test
    fun aTagIsMadeNamedDescribedAndArchivedWithoutTouchingItsEntries() = runTest {
        val walk = phone.repo.createNote("2026-10-01", "Прогулка")
        val sea = phone.repo.addTag(walk, "Море")!!
        val ahead = phone.repo.createTag("Отпуск", category = "места")!!
        assertEquals(listOf("Море" to 1, "Отпуск" to 0), phone.list(), "a tag made on its own waits with no entries")
        assertEquals("места", phone.repo.tags().first().single { it.id == ahead }.category)
        assertEquals(ahead, phone.repo.addTag(phone.repo.createNote("2026-10-02", "Билеты"), "отпуск"), "and is found by its name")

        val before = phone.fields(walk)
        assertTrue(phone.repo.renameTag(sea, "Океан"))
        assertFalse(phone.repo.renameTag(sea, "  "), "a name can't be empty")
        phone.repo.describeTag(sea, "  Всё про воду  ")
        phone.repo.archiveTag(sea, true)
        assertEquals(before, phone.fields(walk), "the entry itself never changes")
        assertEquals(listOf("Океан"), phone.names().getValue("Прогулка"), "an archived tag stays on its entries")
        assertEquals(listOf("Прогулка"), phone.find(SearchQuery("океан")), "and in search, under its new name")
        assertTrue(phone.repo.tags().first().single { it.id == sea }.archived)
        val registry = phone.repo.tagRegistry().first()
        assertEquals("Всё про воду", registry.description(sea))
        phone.repo.archiveTag(sea, false)
        phone.repo.describeTag(sea, "")
        assertFalse(phone.repo.tags().first().single { it.id == sea }.archived)
        assertNull(phone.repo.tagRegistry().first().description(sea))
    }

    @Test
    fun aTagInTheTrashLeavesEveryEntryAndComesBackExactlyItsRuleStillHolds() = runTest {
        val loss = phone.repo.createNote("2025-03-10", "Похороны")
        val grief = phone.repo.addTag(loss, "Утрата")!!
        phone.repo.addTag(loss, "Семья")
        phone.repo.setGentleRule(GentleRule("r", "Утрата", GentleRule.TAG, value = grief, level = GentleRule.QUIET))
        val before = phone.fields(loss)

        phone.repo.trashTag(grief)
        assertEquals(listOf("Семья"), phone.names().getValue("Похороны"))
        assertEquals(listOf("Семья" to 1), phone.list())
        assertEquals(listOf("Утрата" to 1), phone.repo.trashedTags().first().map { it.name to it.uses })
        assertEquals(emptyList(), phone.find(SearchQuery("утрата")), "nor found by it")
        assertEquals(Veil.Quiet, phone.veils().getValue("Похороны"), "the rule still sees the tag")
        assertEquals(before, phone.fields(loss))
        val other = phone.repo.createNote("2026-10-02", "Новое")
        assertTrue(phone.repo.addTag(other, "Утрата") != grief, "a name typed now makes a new tag, not one from the trash")

        phone.repo.restoreTag(grief)
        assertEquals(listOf("Семья", "Утрата"), phone.names().getValue("Похороны"))
        assertEquals(listOf("Похороны"), phone.find(SearchQuery("утрата")).filter { it == "Похороны" })
        assertEquals(before, phone.fields(loss))
    }

    @Test
    fun mergedTagsReadAsOneAndSplitAgainNoRuleLetsGo() = runTest {
        val a = phone.repo.createNote("2026-10-01", "Кофе с Машей")
        val b = phone.repo.createNote("2026-10-02", "Маша приехала")
        val masha = phone.repo.addTag(a, "маша")!!
        val mariya = phone.repo.addTag(b, "Мария")!!
        phone.repo.setGentleRule(GentleRule("r", "маша", GentleRule.TAG, value = masha, level = GentleRule.QUIET))
        phone.repo.setGentleRule(GentleRule("s", "Мария", GentleRule.TAG, value = mariya, level = GentleRule.CURTAIN))

        assertFalse(phone.repo.mergeTags(masha, masha), "not into itself")
        assertTrue(phone.repo.mergeTags(masha, mariya))
        assertFalse(phone.repo.mergeTags(mariya, masha), "nor back round in a circle")
        assertEquals(mapOf("Кофе с Машей" to listOf("Мария"), "Маша приехала" to listOf("Мария")), phone.names())
        assertEquals(listOf("Мария" to 2), phone.list())
        assertEquals(mariya, phone.repo.addTag(phone.repo.createNote("2026-10-03", "Звонок"), "Маша"), "the old name leads on")
        assertEquals(listOf("Звонок", "Кофе с Машей", "Маша приехала"), phone.find(SearchQuery(tag = mariya)))
        assertEquals(
            mapOf("Кофе с Машей" to Veil.Quiet, "Маша приехала" to Veil.Curtain, "Звонок" to Veil.Curtain),
            phone.veils(),
            "each rule still holds, and the one on the target covers the merged entries too",
        )

        phone.repo.removeTag(a, mariya)
        assertEquals(emptyList(), phone.names().getValue("Кофе с Машей"), "taking the target off takes the merged tag off")
        phone.repo.splitTag(masha)
        phone.repo.addTag(a, "маша", masha)
        assertEquals(
            mapOf("Кофе с Машей" to listOf("маша"), "Маша приехала" to listOf("Мария")),
            phone.names().filterKeys {
                it != "Звонок"
            },
        )
        assertEquals(Veil.Quiet, phone.veils().getValue("Кофе с Машей"))
        assertEquals(
            listOf("маша"),
            phone.repo.tagRegistry().first().names.filterKeys {
                it == masha
            }.values.toList(),
            "it got a name of its own",
        )
    }

    @Test
    fun mergesOnTwoDevicesAtOnceStillAgree() = runTest {
        val desktop = Device(server, "desktop")
        val a = phone.repo.createNote("2026-10-01", "Один")
        val x = phone.repo.addTag(a, "Икс")!!
        val y = phone.repo.addTag(a, "Игрек")!!
        phone.engine.sync()
        desktop.engine.sync()
        assertTrue(phone.repo.mergeTags(x, y))
        assertTrue(desktop.repo.mergeTags(y, x), "each device alone sees no circle")
        phone.engine.sync()
        desktop.engine.sync()
        phone.engine.sync()
        val one = phone.names().getValue("Один")
        assertEquals(1, one.size, "a circle reads as one tag")
        assertEquals(one, desktop.names().getValue("Один"), "the same one on both")
        assertEquals(phone.list(), desktop.list())
    }

    @Test
    fun renamesMergesAndTheTrashReachOtherDevicesAndTheirSearch() = runTest {
        val desktop = Device(server, "desktop")
        val a = phone.repo.createNote("2026-10-01", "Утро")
        val run = phone.repo.addTag(a, "Бег")!!
        val sport = phone.repo.addTag(phone.repo.createNote("2026-10-02", "Зал"), "Спорт")!!
        phone.engine.sync()
        desktop.engine.sync()
        assertEquals(listOf("Утро"), desktop.find(SearchQuery("бег")))

        phone.repo.renameTag(sport, "Тренировки")
        phone.repo.mergeTags(run, sport)
        phone.engine.sync()
        desktop.engine.sync()
        assertEquals(mapOf("Утро" to listOf("Тренировки"), "Зал" to listOf("Тренировки")), desktop.names())
        assertEquals(listOf("Зал", "Утро"), desktop.find(SearchQuery("тренировк")), "the index follows the merge and the rename")
        assertEquals(emptyList(), desktop.find(SearchQuery("бег")))

        phone.repo.trashTag(sport)
        phone.engine.sync()
        desktop.engine.sync()
        assertEquals(mapOf("Утро" to emptyList<String>(), "Зал" to emptyList()), desktop.names(), "the merged tag goes with it")
        assertEquals(emptyList(), desktop.find(SearchQuery("тренировк")))
    }

    @Test
    fun categoriesAreMadeRenamedOrderedAndTrashedTheirRulesHold() = runTest {
        val a = phone.repo.createNote("2026-10-01", "Пробежка")
        val run = phone.repo.addTag(a, "Бег")!!
        val sport = phone.repo.createCategory("Спорт")!!
        phone.repo.setTagCategory(run, sport)
        phone.repo.setCategoryEmoji(sport, "🏃")
        phone.repo.describeCategory(sport, "Движение")
        phone.repo.renameCategory(sport, "Спорт и тело")
        phone.repo.setGentleRule(GentleRule("r", "Спорт", GentleRule.CATEGORY, value = sport))
        var registry = phone.repo.tagRegistry().first()
        assertEquals(listOf("Люди", "Места", "Проекты", "Эмоции", "Спорт и тело"), registry.categories.map { it.name })
        assertEquals("🏃", registry.categoryEmoji[sport])
        assertEquals("Движение", registry.categoryDescriptions[sport])
        assertEquals(sport, phone.repo.tags().first().single().category)

        phone.repo.setCategoryOrder(listOf(sport, "эмоции", "люди", "места", "проекты"))
        registry = phone.repo.tagRegistry().first()
        assertEquals(listOf(sport, "эмоции", "люди", "места", "проекты"), registry.categories.map { it.id })

        assertEquals(Veil.Curtain, phone.veils().getValue("Пробежка"))
        assertTrue(phone.repo.trashCategory(sport))
        assertFalse(phone.repo.trashCategory("Эмоции"), "check-ins need it")
        registry = phone.repo.tagRegistry().first()
        assertNull(phone.repo.tags().first().single().category, "its tags show without a category")
        assertEquals(listOf("Спорт и тело"), registry.trashedCategories.map { it.name })
        assertTrue(sport !in registry.categories.map { it.id })
        assertEquals(Veil.Curtain, phone.veils().getValue("Пробежка"), "the rule on it still holds")

        phone.repo.restoreCategory(sport)
        assertEquals(sport, phone.repo.tags().first().single().category)
    }

    @Test
    fun aLevelFromTheEditorMakesOrChangesTheOneRuleOnIt() = runTest {
        val a = phone.repo.createNote("2026-10-01", "Похороны")
        val grief = phone.repo.addTag(a, "Утрата")!!
        phone.repo.setGentleLevel(GentleRule.TAG, grief, "Утрата", GentleRule.OFF)
        assertNull(phone.repo.gentleRules().first().ruleFor(GentleRule.TAG, grief), "«Видно» makes no rule")
        phone.repo.setGentleLevel(GentleRule.TAG, grief, "Утрата", GentleRule.QUIET)
        assertEquals(Veil.Quiet, phone.veils().getValue("Похороны"))
        phone.repo.setGentleLevel(GentleRule.TAG, grief, "Утрата", GentleRule.OFF)
        assertNull(phone.veils().getValue("Похороны"))
        assertEquals(listOf(GentleRule.OFF), phone.repo.gentleRules().first().rules.map { it.level }, "one rule, kept and off")

        // A rule made by name before ids is the same rule.
        phone.repo.setField(a, "tag:море", "Море")
        phone.repo.setGentleRule(GentleRule("old", "Море", GentleRule.TAG, value = "Море"))
        phone.repo.setGentleLevel(GentleRule.TAG, "море", "Море", GentleRule.QUIET)
        assertEquals(GentleRule.QUIET, phone.repo.gentleRules().first().rules.single { it.id == "old" }.level)
    }

    @Test
    fun aFreshDeviceStillHasTheBuiltInCategories() = runTest {
        val expected = listOf("Люди", "Места", "Проекты", "Эмоции")
        assertEquals(expected, phone.repo.tagRegistry().first().categories.map { it.name }, "before any registry exists")
        assertEquals(expected, TagRegistry().categories.map { it.name }, "and before it is read")
        assertEquals(TagRegistry.of(emptyMap()), TagRegistry())
    }
}
