package app.benbow.app

import app.benbow.client.FeedKey
import app.benbow.client.Veil
import app.benbow.client.addTag
import app.benbow.client.archiveTag
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.client.mergeTags
import app.benbow.client.setTagCategory
import app.benbow.client.tagRegistry
import app.benbow.client.trashTag
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Перейти», the filter and «Показать в ленте» without a screen (ADR-036 N4-N6). */
class FeedToolsLogicTest {
    private val services = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
        "desktop",
        "Test PC",
    )
    private val repo = services.repo

    // Rows: 0 month 2026-10, 1 day 10-17, 2 a, 3 day 10-02, 4 b, 5 month 2026-09, 6 day 09-30, 7 c,
    // 8 month 2025-12, 9 day 12-31, 10 d, 11 day 12-30, 12 e.
    private val rows = feedRows(
        listOf(
            FeedKey("a", "2026-10-17", 5),
            FeedKey("b", "2026-10-02", 4, veil = Veil.Curtain),
            FeedKey("c", "2026-09-30", 3),
            FeedKey("d", "2025-12-31", 2),
            FeedKey("e", "2025-12-30", 1),
        ),
    )

    @Test
    fun jumpKnowsTheMonthsAndYearsWithPosts() {
        val index = JumpIndex(rows)
        assertEquals(mapOf("2026-10" to 0, "2026-09" to 5, "2025-12" to 8), index.months)
        assertEquals(listOf("2026", "2025"), index.years)
    }

    @Test
    fun aDateGoesToTheNearestDayOnOrBeforeIt() {
        val index = JumpIndex(rows)
        assertEquals(1, index.dayRow("2026-10-17"), "the day itself")
        assertEquals(3, index.dayRow("2026-10-10"), "the newest day before it")
        assertEquals(1, index.dayRow("2027-01-01"), "after the newest post: the newest day")
        assertEquals(11, index.dayRow("2020-01-01"), "before the first post: the first day")
        assertNull(JumpIndex(emptyList()).dayRow("2026-10-17"))
        assertTrue(index.reaches("2025-12-30"))
        assertFalse(index.reaches("2025-12-29"), "a year ago, in a younger journal, is out of reach")
    }

    @Test
    fun atRandomNeverACurtainNorTheLastMonth() {
        val index = JumpIndex(rows)
        val landed = (0 until 100).mapNotNull { index.randomDay(before = "2026-10-10", random = Random(it)) }.toSet()
        assertEquals(setOf(6, 9, 11), landed, "the days of c, d and e: b is under a curtain, a is too recent")
        assertNull(index.randomDay(before = "2020-01-01"), "nothing that old")
    }

    @Test
    fun aFilterTakesAnyOfItsSubjectsInItsYears() = runBlocking {
        val trip = repo.createNote("2026-08-01", "Поездка")
        val trips = repo.addTag(trip, "поездки")!!
        val sea = repo.createNote("2026-07-01", "Море")
        val seaTag = repo.addTag(sea, "море")!!
        repo.mergeTags(seaTag, trips) // «море» went into «поездки»: its posts are trips now
        val friend = repo.createNote("2025-05-01", "Оля")
        repo.setTagCategory(repo.addTag(friend, "Оля")!!, "люди")
        val plain = repo.createNote("2026-06-01", "Просто день")
        val registry = repo.tagRegistry().first()
        val keys = repo.feedIndex().first().associateBy { it.id }

        val byTrips = FeedFilter(tags = setOf(trips))
        assertTrue(byTrips.matches(keys.getValue(trip), registry))
        assertTrue(byTrips.matches(keys.getValue(sea), registry), "a merged tag counts as the one it went into")
        assertFalse(byTrips.matches(keys.getValue(plain), registry))
        val tripsOrPeople = byTrips.copy(categories = setOf("люди"))
        assertTrue(tripsOrPeople.matches(keys.getValue(friend), registry), "any of the subjects")
        assertFalse(tripsOrPeople.copy(years = setOf("2026")).matches(keys.getValue(friend), registry), "in the chosen years only")
        assertTrue(FeedFilter(years = setOf("2026")).matches(keys.getValue(plain), registry))
        assertTrue(FeedFilter().matches(keys.getValue(plain), registry), "no filter: everything")
    }

    @Test
    fun quietPostsComeOnlyUnderASubject() = runBlocking {
        val hard = repo.createNote("2026-08-01", "Тяжёлый день")
        val loss = repo.addTag(hard, "утрата")!!
        repo.setVeil(hard, Veil.Quiet)
        val registry = repo.tagRegistry().first()
        val key = repo.feedIndex().first().single { it.id == hard }

        assertFalse(FeedFilter().shows(key, gentle = true, registry), "Бережно keeps it out of the feed")
        assertFalse(FeedFilter(years = setOf("2026")).shows(key, gentle = true, registry), "a year alone doesn't bring it")
        assertTrue(FeedFilter(tags = setOf(loss)).shows(key, gentle = true, registry), "asking for its subject does, under a curtain")
        assertTrue(FeedFilter().shows(key, gentle = false, registry), "with Бережно off, everything shows")
    }

    @Test
    fun theFilterOffersTheFeedsYearsCategoriesAndMostUsedTags() = runBlocking {
        repeat(3) { repo.addTag(repo.createNote("2026-0${it + 1}-01", "Работа $it"), "работа") }
        repeat(2) { repo.addTag(repo.createNote("2025-0${it + 1}-01", "Книга $it"), "книги") }
        val gone = repo.addTag(repo.createNote("2025-06-01", "Старое"), "старое")!!
        repo.trashTag(gone)
        val old = repo.addTag(repo.createNote("2025-07-01", "Архив"), "архив")!!
        repo.archiveTag(old, true)
        repo.setTagCategory(repo.addTag(repo.createNote("2024-01-01", "Маша"), "Маша")!!, "люди")
        val registry = repo.tagRegistry().first()
        val choices = FeedChoices.of(repo.feedIndex().first(), registry)

        assertEquals(listOf("2026", "2025", "2024"), choices.years)
        assertEquals(listOf("люди"), choices.categories, "only categories the feed has")
        assertEquals(listOf("работа", "книги"), choices.tags.take(2).map { registry.names[it] }, "most used first")
        assertFalse(choices.tags.any { it == gone || it == old }, "none from the trash or the archive")
    }

    @Test
    fun findGoesPastAFilterButNotPastBerezhno() = runBlocking {
        val old = repo.createNote("2026-01-05", "Старая")
        val tagged = repo.createNote("2026-02-01", "С тегом")
        val trips = repo.addTag(tagged, "поездки")!!
        val quiet = repo.createNote("2026-03-01", "Тихая")
        repo.setVeil(quiet, Veil.Quiet)
        val vm = FeedViewModel(services)

        val (pos, row) = assertNotNull(vm.find(old))
        val item = vm.rows.filterNotNull().first()[row] as FeedRow.Item
        assertEquals(old to pos, item.key to item.pos)

        services.feedFilter.value = FeedFilter(tags = setOf(trips))
        withTimeout(5_000) { vm.rows.filterNotNull().first { rows -> rows.none { it.key == old } } }
        assertNotNull(vm.find(old), "found once the filter that hid it is let go")
        assertEquals(FeedFilter(), services.feedFilter.value)

        assertNull(vm.find(quiet), "Бережно keeps it out")
    }
}
