package app.benbow.app

import app.benbow.client.Settings
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {
    private var time = 0L
    private val settings = Settings(BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema)))

    @Test
    fun locksAfterTheChosenTimeAwayAndAtEveryColdStart() {
        val lock = AppLock(settings) { time }
        assertFalse(lock.locked.value, "off by default")
        lock.set(1)
        assertFalse(lock.locked.value, "turning it on doesn't lock right away")

        lock.onLeave()
        time += 59_000
        lock.onReturn()
        assertFalse(lock.locked.value, "back within the minute")
        lock.onLeave()
        time += 60_000
        lock.onReturn()
        assertTrue(lock.locked.value, "away for the minute")
        lock.unlock()
        assertFalse(lock.locked.value)

        assertTrue(AppLock(settings) { time }.locked.value, "a new process never inherits an unlocked state")
        lock.set(null)
        assertFalse(AppLock(settings) { time }.locked.value, "and off is off")
    }
}
