package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataSearchTest {
    private var time = 1_000L

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val api = FakeApi(server)
        val repo = JournalRepository(db, { time++ })
        val engine = SyncEngine(db, { api }, { time++ })

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }

        suspend fun find(query: SearchQuery) = repo.search(query).first().map { it.title }
    }

    @Test
    fun favoriteMoodAndTagsShowInListsAndDetails() = runTest {
        val phone = Device(FakeServer(), "phone")
        val id = phone.repo.createNote("2026-10-13", "Поход в горы")
        phone.repo.setFavorite(id, true)
        phone.repo.setMood(id, 2)
        phone.repo.addTag(id, "#Путешествия")
        phone.repo.addTag(id, "  друзья ")
        phone.repo.addTag(id, "путешествия") // the same tag, any case, keeps its name
        val summary = phone.repo.journal().first().single()
        assertTrue(summary.favorite)
        assertEquals(2, summary.mood)
        assertEquals(listOf("друзья", "Путешествия"), summary.tags.map { it.name })
        assertEquals(
            listOf("друзья" to 1, "Путешествия" to 1),
            phone.repo.tags().first().map {
                it.name to it.uses
            }.sortedBy { it.first.lowercase() },
        )

        phone.repo.removeTag(id, summary.tags.first().id)
        phone.repo.toggleReaction(id, "❤️", on = true)
        val detail = phone.repo.entry(id).first()!!
        assertEquals(listOf("Путешествия"), detail.tags.map { it.name })
        assertEquals(setOf("❤️"), detail.reactions)
    }

    @Test
    fun tagsAddedOnTwoDevicesAtOnceBothStay() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val desktop = Device(server, "desktop")
        val id = phone.repo.createNote("2026-10-13", "Вечер")
        phone.engine.sync()
        desktop.engine.sync()
        phone.repo.addTag(id, "дом")
        desktop.repo.addTag(id, "семья")
        phone.engine.sync()
        desktop.engine.sync()
        phone.engine.sync()
        assertEquals(listOf("дом", "семья"), phone.repo.entry(id).first()!!.tags.map { it.name })
        assertEquals(listOf("дом", "семья"), desktop.repo.entry(id).first()!!.tags.map { it.name })
    }

    @Test
    fun searchFindsWordsByPrefixIgnoringCaseAndYo() = runTest {
        val phone = Device(FakeServer(), "phone")
        phone.repo.createNote("2026-10-11", "Ёлка во дворе\nнарядили всей семьёй")
        phone.repo.createNote("2026-10-12", "Море и чайки")
        val third = phone.repo.createNote("2026-10-13", "Работа над Benbow")
        phone.repo.addTag(third, "проекты")

        assertEquals(listOf("Ёлка во дворе"), phone.find(SearchQuery("елка")))
        assertEquals(listOf("Ёлка во дворе"), phone.find(SearchQuery("НАРЯД семьей")), "every word, as a prefix, in any case")
        assertEquals(listOf("Море и чайки"), phone.find(SearchQuery("мор")))
        assertEquals(listOf("Работа над Benbow"), phone.find(SearchQuery("проек")), "tags are searchable text too")
        assertEquals(3, phone.find(SearchQuery("")).size)
        assertEquals(emptyList(), phone.find(SearchQuery("\" OR море -чайки NEAR(")), "FTS syntax in the input is just words")
        assertEquals(emptyList(), phone.find(SearchQuery("кошка")))
    }

    @Test
    fun searchFindsEveryFormOfAWord() = runTest {
        val phone = Device(FakeServer(), "phone")
        phone.repo.createNote("2026-10-12", "Гуляли у моря вечером")
        phone.repo.createNote("2026-10-13", "Купить морковь")

        for (query in listOf("море", "морем", "МОРЯ", "вечер", "вечерами", "гулять", "гуля", "у моря")) {
            assertTrue("Гуляли у моря вечером" in phone.find(SearchQuery(query)), query)
        }
        assertEquals(listOf("Гуляли у моря вечером"), phone.find(SearchQuery("море")), "a whole word finds its forms, not other words")
        assertEquals(listOf("Купить морковь", "Гуляли у моря вечером"), phone.find(SearchQuery("мор")), "a beginning is still a prefix")
    }

    @Test
    fun filtersNarrowAndTrashIsLeftOut() = runTest {
        val phone = Device(FakeServer(), "phone")
        val a = phone.repo.createNote("2026-10-12", "Утро на даче")
        val b = phone.repo.createNote("2026-10-13", "Утро в городе")
        val dacha = phone.repo.addTag(a, "дача")
        phone.repo.setFavorite(b, true)
        assertEquals(listOf("Утро на даче"), phone.find(SearchQuery("утро", tag = dacha)))
        assertEquals(listOf("Утро в городе"), phone.find(SearchQuery(favorites = true)))
        phone.repo.moveToTrash(b)
        assertEquals(listOf("Утро на даче"), phone.find(SearchQuery("утро")))
    }

    @Test
    fun whatArrivesBySyncIsSearchableAndTheIndexRebuilds() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        phone.repo.createNote("2026-10-13", "Поезд в Казань")
        phone.engine.sync()
        val desktop = Device(server, "desktop")
        desktop.engine.sync()
        assertEquals(listOf("Поезд в Казань"), desktop.find(SearchQuery("казан")))

        desktop.db.benbowQueries.clearIndex() // e.g. after an update that changed the index format
        Settings(desktop.db).put("search.version", "0")
        desktop.repo.prepareSearch()
        assertEquals(listOf("Поезд в Казань"), desktop.find(SearchQuery("поезд")))
    }
}
