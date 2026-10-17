@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.benbow.app.AppMessage
import app.benbow.app.AppServices
import app.benbow.app.BenbowApp
import app.benbow.app.BrowserAudio
import app.benbow.app.BrowserReminderClock
import app.benbow.app.MediaFactory
import app.benbow.app.WebNotice
import app.benbow.app.WithBundledFonts
import app.benbow.app.installWebImages
import app.benbow.app.installWebShortcuts
import app.benbow.app.keepMiddleButton
import app.benbow.app.preloadTexts
import app.benbow.app.showNotification
import app.benbow.client.BrowserMedia
import app.benbow.client.BrowserShelf
import app.benbow.client.BrowserSqlDriver
import app.benbow.client.OpenElsewhereException
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.client.installTimeZones
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Opens this browser's copy of the journal and calendar (ADR-029), then shows the app. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        try {
            start()
        } catch (e: Throwable) {
            showStartupError(e.message ?: e::class.simpleName.orEmpty())
            throw e
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun start() {
    installTimeZones()
    coroutineScope {
        val texts = async { preloadTexts() }
        val shelf = try {
            BrowserShelf.claim()
        } catch (e: OpenElsewhereException) {
            hideLoading()
            ComposeViewport { WebNotice.OpenElsewhere() }
            return@coroutineScope
        }
        val journal = BrowserSqlDriver.open("benbow.db", BenbowDb.Schema, shelf)
        val calendar = BrowserSqlDriver.open("calendar.db", CalendarDb.Schema, shelf)
        lateinit var services: AppServices
        val reminders = BrowserReminderClock(
            MainScope(),
            ring = { text -> if (!showNotification(text)) services.messages.tryEmit(AppMessage.Reminder(text)) },
        ) { services.rescheduleReminders() }
        services = AppServices(
            journal,
            calendar,
            mediaStore = MediaFactory { db, apis, now -> BrowserMedia(db, apis, now, shelf) },
            platform = "web",
            deviceName = browserName(),
            mediaCacheBytes = 2L * 1024 * 1024 * 1024, // downloads kept in IndexedDB; the PC has room
            reminderClock = reminders,
            audio = BrowserAudio(),
        )
        val media = services.media as BrowserMedia
        // Hidden (another tab, minimized, closing): typing becomes a revision and everything goes to browser storage now.
        val saveAll = {
            services.scope.launch {
                services.commitOpenDrafts().joinAll()
                journal.flush()
                calendar.flush()
            }
        }
        whenHidden { saveAll() }
        warnBeforeClosing {
            val unsaved = services.draftsPending || journal.unsaved || calendar.unsaved || media.unsaved
            if (unsaved) saveAll() // finishes while the browser asks
            unsaved
        }
        installWebImages()
        installWebShortcuts(services)
        keepMiddleButton()
        texts.await()
        ComposeViewport { WithBundledFonts(onReady = ::hideLoading) { BenbowApp(services) } }
    }
}

/** "Edge, Windows": how this browser shows in the server's device list. */
private fun browserName(): String = js(
    """(() => {
        const ua = navigator.userAgent;
        const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /OPR\//.test(ua) ? 'Opera'
            : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
        const os = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'macOS' : /Android/.test(ua) ? 'Android'
            : /Linux/.test(ua) ? 'Linux' : '';
        return os ? browser + ', ' + os : browser;
    })()""",
)

/** The browser's "Leave site?" question while changes are still on their way to its storage. */
private fun warnBeforeClosing(unsaved: () -> Boolean): Unit = js(
    """{ window.addEventListener('beforeunload', e => { if (unsaved()) { e.preventDefault(); e.returnValue = ''; } }); }""",
)

/** The tab goes to the background or is closing: the last moment to save without waiting. */
private fun whenHidden(block: () -> Unit): Unit = js(
    "{ document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') block(); }); }",
)

/** Fades the page's loading screen out once the first frame is ready. */
private fun hideLoading(): Unit = js(
    "{ const el = document.getElementById('loading'); if (el) { el.classList.add('gone'); setTimeout(() => el.remove(), 300); } }",
)

/** The loading screen says what went wrong instead of spinning forever. */
private fun showStartupError(message: String): Unit = js(
    """{
        const el = document.getElementById('loading');
        if (el) {
            el.querySelector('.bar')?.remove();
            const p = document.createElement('div');
            p.className = 'error';
            p.textContent = 'Benbow не запустился: ' + message + '. Обновите страницу; если повторится, откройте её в другом браузере.';
            el.appendChild(p);
        }
    }""",
)
