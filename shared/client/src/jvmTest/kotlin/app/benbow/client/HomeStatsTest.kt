package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeStatsTest {
    private var time = 1_000L
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ }, today = { "2026-10-17" })
    private val media = MediaStore(Files.createTempDirectory("benbow-stats").toFile(), db, { FakeApi(FakeServer()) }, { time++ })

    private suspend fun attach(entry: String, kind: String, seed: Int) =
        repo.attach(entry, media.import(Random(seed).nextBytes(200).inputStream(), "image/jpeg", "$seed.jpg", null), kind)

    private suspend fun stats(gentle: Boolean = false) =
        repo.homeStats("2026-10-01", "2026-10-31", "2026-10-11", "2026-10-17", gentle).first()

    @Test
    fun theMonthCountedUpGently() = runTest {
        repo.createNote("2024-03-05", "Первая запись")
        val sea = repo.createNote("2026-10-02", "Море")
        val walk = repo.createNote("2026-10-12", "Прогулка у моря")
        val talk = repo.createNote("2026-10-12", "Тяжёлый разговор")
        repo.createCheckin("2026-10-16", -1, listOf("тревога", "усталость", "тревога2"), "")
        repo.createCheckin("2026-10-09", 2, emptyList(), "") // last week: in the month, not in the week's mood
        repo.addTag(sea, "Море")
        repo.addTag(walk, "Море")
        repo.addTag(walk, "Саша")
        repo.addTag(talk, "Врач")
        attach(sea, "image", 1)
        attach(sea, "video", 2)
        attach(walk, "voice", 3)
        repo.createNote("2026-09-30", "Не этот месяц")

        val all = stats()
        assertEquals(4, all.days, "Oct 2, 9, 12 and 16: days, not entries")
        assertEquals(5, all.moments)
        assertEquals(2, all.pictures, "photos and videos, not a voice note")
        assertEquals(listOf("Море", "Врач", "Саша"), all.tags.map { it.name }, "most used first; feelings are the mood's, not topics")
        assertEquals("2024-03-05", all.since)
        assertEquals(-1.0, all.weekMood)

        // Бережно: a curtained entry's tag never comes up on Home; the counts stay.
        repo.setVeil(talk, Veil.Curtain)
        val gentle = stats(gentle = true)
        assertEquals(listOf("Море", "Саша"), gentle.tags.map { it.name })
        assertEquals(all.moments, gentle.moments)
    }

    @Test
    fun aStatsBlockShowsWhatItWasGiven() {
        val block = HomeBlock.of(HomeBlockType.Stats)
        assertEquals(listOf(StatFigure.Days, StatFigure.Moments, StatFigure.Pictures), block.figures)
        assertEquals(listOf(StatFigure.Days), block.copy(size = "s").figures, "S shows one")
        val chosen = block.copy(config = mapOf(HomeBlock.FIGURES to "since,later,mood"))
        assertEquals(listOf(StatFigure.Since, StatFigure.Mood), chosen.figures, "a figure from a newer app is skipped")
    }
}
