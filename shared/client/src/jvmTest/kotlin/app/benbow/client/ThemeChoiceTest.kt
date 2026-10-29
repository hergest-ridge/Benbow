package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ADR-057: the palette is one for every device, in `system-theme`; only what a choice changes is written. */
class ThemeChoiceTest {
    private var time = 1_000L
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ })

    private fun pending() = db.benbowQueries.pendingCount().executeAsOne()

    @Test
    fun untilAChoiceNothingIsStoredAndEachDeviceKeepsItsOwn() = runTest {
        assertEquals(ThemeChoice(), repo.themeChoice().first())
        assertNull(db.benbowQueries.selectEntry(THEME_REGISTRY_ID).executeAsOneOrNull(), "no system entry before a choice")
    }

    @Test
    fun aChoiceWritesOnlyWhatItChangesAndKeepsOnesOwnColors() = runTest {
        repo.editThemeChoice { it.copy(palette = "Own", seed = "#3F7D4E", character = "Bright") }
        assertEquals(ThemeChoice("Own", "#3F7D4E", "Bright"), repo.themeChoice().first())
        val before = pending()
        repo.editThemeChoice { it.copy(palette = "Sea") }
        assertEquals(before + 1, pending(), "one field: the palette")
        assertEquals(ThemeChoice("Sea", "#3F7D4E", "Bright"), repo.currentThemeChoice(), "one's own colors wait for a return")
        repo.editThemeChoice { it }
        assertEquals(before + 1, pending(), "nothing changed, nothing written")
    }

    @Test
    fun aNameOnlyANewerAppKnowsIsKeptAsItIs() = runTest {
        repo.setRegistryField(ThemeChoice.PALETTE, "Aurora", THEME_REGISTRY_ID)
        repo.editThemeChoice { it.copy(accent = "#B5677D") }
        assertEquals(ThemeChoice(palette = "Aurora", accent = "#B5677D"), repo.currentThemeChoice())
    }
}
