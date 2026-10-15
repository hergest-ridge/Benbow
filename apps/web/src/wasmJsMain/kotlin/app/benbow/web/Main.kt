@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.benbow.app.AppServices
import app.benbow.app.BenbowApp
import app.benbow.app.MediaFactory
import app.benbow.app.WebNotice
import app.benbow.app.installWebImages
import app.benbow.app.preloadTexts
import app.benbow.client.BrowserMedia
import app.benbow.client.BrowserShelf
import app.benbow.client.BrowserSqlDriver
import app.benbow.client.OpenElsewhereException
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Opens this browser's copy of the journal and calendar (ADR-029), then shows the app. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        val texts = async { preloadTexts() }
        val shelf = try {
            BrowserShelf.claim()
        } catch (e: OpenElsewhereException) {
            hideLoading()
            ComposeViewport { WebNotice.OpenElsewhere() }
            return@launch
        }
        val journal = BrowserSqlDriver.open("benbow.db", BenbowDb.Schema, shelf)
        val calendar = BrowserSqlDriver.open("calendar.db", CalendarDb.Schema, shelf)
        val services = AppServices(
            journal,
            calendar,
            mediaStore = MediaFactory { db, apis, now -> BrowserMedia(db, apis, now, shelf) },
            platform = "web",
            deviceName = browserName(),
            mediaCacheBytes = 2L * 1024 * 1024 * 1024, // downloads kept in IndexedDB; the PC has room
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
        texts.await()
        hideLoading()
        ComposeViewport { BenbowApp(services) }
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

private fun hideLoading(): Unit = js("{ document.getElementById('loading')?.remove(); }")
