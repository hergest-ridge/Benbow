package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GentleRulesTest {
    private var time = 1_000L
    private val repo = JournalRepository(BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema)), { time++ })

    private suspend fun veilOf(id: String) = repo.journal().first().single { it.id == id }.veil

    @Test
    fun rulesByTagCategoryAndPeriodVeilEntriesTheOwnMarkWins() = runTest {
        val loss = repo.createNote("2025-03-10", "Похороны")
        repo.addTag(loss, "Утрата")
        val friend = repo.createNote("2026-10-01", "Разговор с Олей")
        repo.addTag(friend, "Оля")
        repo.setTagCategory("Оля", "Люди")
        val spring = repo.createNote("2024-04-02", "Больница")
        val calm = repo.createNote("2026-10-02", "Прогулка")

        repo.setGentleRule(GentleRule("r1", "Утрата", GentleRule.TAG, value = "утрата", level = GentleRule.QUIET))
        repo.setGentleRule(GentleRule("r2", "Люди", GentleRule.CATEGORY, value = "люди"))
        repo.setGentleRule(GentleRule("r3", "Весна 2024", GentleRule.PERIOD, from = "2024-03-01", to = "2024-05-31"))
        assertEquals(Veil.Quiet, veilOf(loss), "a tag, any case")
        assertEquals(Veil.Curtain, veilOf(friend), "a tag in the category")
        assertEquals(Veil.Curtain, veilOf(spring), "a day in the period")
        assertNull(veilOf(calm))
        assertEquals(Veil.Quiet, repo.feed().first().single { it.summary.id == loss }.summary.veil, "the feed applies them too")

        // The strongest rule wins; the entry's own mark wins over all of them, "open" clears them.
        repo.addTag(spring, "Утрата")
        assertEquals(Veil.Quiet, veilOf(spring))
        repo.setVeil(spring, Veil.Curtain)
        assertEquals(Veil.Curtain, veilOf(spring))
        repo.setVeil(spring, Veil.Open)
        assertNull(veilOf(spring))
        assertEquals(Veil.Quiet, repo.journal().first().single { it.id == spring }.ruleVeil, "the rules still say so")

        // Off keeps the rule without applying it; removing it forgets it.
        repo.setGentleRule(repo.gentleRules().first().rules.single { it.id == "r1" }.copy(level = GentleRule.OFF))
        assertNull(veilOf(loss))
        repo.removeGentleRule("r2")
        assertNull(veilOf(friend))
        assertEquals(listOf("r1", "r3"), repo.gentleRules().first().rules.map { it.id }.sorted())
    }

    @Test
    fun aQuietEntryTintsNoDayEvenByARule() = runTest {
        val hard = repo.createNote("2026-10-05", "Тяжёлый день")
        repo.setMood(hard, -2)
        val good = repo.createNote("2026-10-05", "Но вечер хороший")
        repo.setMood(good, 2)
        repo.addTag(hard, "Больница")
        repo.setGentleRule(GentleRule("r", "Больница", GentleRule.TAG, value = "больница", level = GentleRule.QUIET))

        val day = repo.monthDays("2026-10-01", "2026-10-31").first().getValue("2026-10-05")
        assertEquals(2, day.entries, "counted all the same")
        assertEquals(0.0, day.mood)
        assertEquals(2.0, day.openMood)
    }
}
