package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.BenbowApi
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.core.api.AndroidApp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Updating the app from its own server (ADR-042, U2): offered only for itself and newer, installed only on a tap. */
@OptIn(ExperimentalTestApi::class)
class AppUpdatesTest {
    private class FakeUpdater(override val packageName: String = "app.benbow", override val versionCode: Int = 13000) : AppUpdater {
        var allowed = true
        var prepared = 0
        val announced = mutableListOf<Int>()
        val installed = mutableListOf<String>()
        var refuse: String? = null

        override fun canInstall() = allowed

        override fun allowInstalls() {
            allowed = true
        }

        override suspend fun prepare(app: AndroidApp, api: () -> BenbowApi, progress: (Long, Long) -> Unit): String {
            prepared++
            progress(app.size, app.size)
            return "/cache/benbow-${app.versionCode}.apk"
        }

        override suspend fun install(file: String, failed: (String) -> Unit) {
            installed += file
            refuse?.let(failed)
        }

        override fun announce(app: AndroidApp) {
            announced += app.versionCode
        }
    }

    private val updater = FakeUpdater()
    private val services = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
        "android",
        "Test phone",
        updater = updater,
    )
    private val newer = AndroidApp(13001, "0.13.1", "ab".repeat(32), 42_000_000, "app.benbow")

    @AfterTest
    fun stopRecording() {
        VoiceRecording.active.value = false
    }

    private fun offer(app: AndroidApp?): UpdateState {
        services.settings.serverApp = app
        services.updates.check()
        return runBlocking {
            withTimeout(5_000) { services.updates.state.first { it !is UpdateState.Fetching } }
        }
    }

    @Test
    fun onlyANewerBuildOfThisAppIsOffered() {
        assertEquals(UpdateState.None, offer(newer.copy(versionCode = 13000)), "the same version")
        assertEquals(UpdateState.None, offer(newer.copy(versionCode = 12999)), "an older one")
        assertEquals(UpdateState.None, offer(newer.copy(packageName = "app.benbow.debug")), "another app")
        assertEquals(UpdateState.None, offer(null))
        assertEquals(0, updater.prepared, "nothing fetched for any of them")
        assertIs<UpdateState.Ready>(offer(newer))
        assertIs<UpdateState.Ready>(offer(newer))
        assertEquals(1, updater.prepared, "fetched once")
        assertEquals(listOf(13001), updater.announced, "the owner is told once")
        assertEquals(UpdateState.None, offer(null), "a server that stopped offering it")
    }

    @Test
    fun moreOffersTheUpdateAndInstallsItOnlyOnATapWhenNothingIsUnderWay() = runDesktopComposeUiTest(width = 412, height = 860) {
        offer(newer)
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").performClick()
        waitUntilAtLeastOneExists(hasText("Доступна версия 0.13.1"))
        assertTrue(updater.installed.isEmpty(), "nothing goes in by itself")

        VoiceRecording.active.value = true
        waitUntilAtLeastOneExists(hasText("Сначала закончите запись голоса"))
        onNodeWithText("Доступна версия 0.13.1").performClick()
        assertTrue(updater.installed.isEmpty(), "never while recording")
        VoiceRecording.active.value = false

        updater.allowed = false
        waitForIdle()
        onNodeWithText("Ещё").performClick() // a new look at the screen
        waitUntilAtLeastOneExists(hasText("Разрешите Benbow устанавливать обновления"))
        onNodeWithText("Разрешите Benbow устанавливать обновления").performClick()
        assertTrue(updater.allowed)

        updater.refuse = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
        assertTrue(services.updates.install())
        waitUntilAtLeastOneExists(hasText("INSTALL_FAILED_UPDATE_INCOMPATIBLE", substring = true))
        assertEquals(listOf("/cache/benbow-13001.apk"), updater.installed)
        updater.refuse = null
        onNodeWithText("INSTALL_FAILED_UPDATE_INCOMPATIBLE", substring = true).performClick() // try again: fetched and ready
        waitUntilAtLeastOneExists(hasText("Обновить"))
        onNodeWithText("Обновить").performClick()
        waitUntilAtLeastOneExists(hasText("Устанавливается 0.13.1…"))
        assertEquals(2, updater.installed.size)
    }
}
