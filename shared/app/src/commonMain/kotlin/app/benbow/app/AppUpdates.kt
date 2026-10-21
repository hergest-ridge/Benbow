package app.benbow.app

import app.benbow.client.BenbowApi
import app.benbow.client.PairingException
import app.benbow.client.Settings
import app.benbow.core.api.AndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** This app's own side of updating itself from its server (ADR-042, U2); Android only. */
interface AppUpdater {
    val packageName: String
    val versionCode: Int

    /** Whether the system lets this app install updates (Android's «Установка неизвестных приложений» for it). */
    fun canInstall(): Boolean

    /** Opens the system setting where the owner allows it. */
    fun allowInstalls()

    /**
     * Fetches [app] from the server (a cut download goes on) and checks it: size and SHA-256 as the server said, then that
     * it is this app, signed by the same key, newer than this build. The checked file's path, or throws why not.
     */
    suspend fun prepare(app: AndroidApp, api: () -> BenbowApi, progress: (Long, Long) -> Unit): String

    /**
     * Hands the checked file to the system; [outcome] hears back when it refuses or, [quiet], when it wants the owner's
     * yes (then nothing is shown over what the owner is doing: the install is dropped and waits for a tap). A success ends
     * this process.
     */
    suspend fun install(file: String, quiet: Boolean, outcome: (InstallOutcome) -> Unit)

    /** Tells the owner outside the app that [app] is ready (a notification). */
    fun announce(app: AndroidApp)
}

/** What the system said of an install that didn't happen. */
sealed interface InstallOutcome {
    data class Refused(val reason: String) : InstallOutcome

    /** A quiet install the system wouldn't do without the owner: it waits for «Обновить». */
    data object NeedsYou : InstallOutcome
}

sealed interface UpdateState {
    data object None : UpdateState

    data class Fetching(val app: AndroidApp, val done: Long, val total: Long) : UpdateState

    data class Ready(val app: AndroidApp, val file: String) : UpdateState

    data class Installing(val app: AndroidApp) : UpdateState

    data class Failed(val app: AndroidApp, val reason: String) : UpdateState
}

/** Why an update may not be installed right now, so nothing under way is cut off by the app's restart. */
enum class UpdateBusy { Recording, Dictating, Playing }

/**
 * Updating this app from its own server (ADR-042, as built 7.19 U2, U3). A sync remembers what app the server offers
 * (`Settings.serverApp`); when it is this app and newer, it is fetched and checked in the background, the owner is told
 * once, and «Ещё» offers «Обновить». The install is the platform's ([AppUpdater]) and never runs while a recording, a
 * dictation or a sound is under way: on the owner's tap, or, with [quiet] on (U3), by itself as the owner leaves the app
 * (only then: at a process's start or in a background sync "not in front" can't be told from "not shown yet", and a
 * dialog Google Play Protect may put up should come when the owner can tell it is Benbow's). Drafts are on disk by then
 * (S12) and the outbox in the database.
 */
class AppUpdates(
    private val updater: AppUpdater?,
    private val settings: () -> Settings,
    private val api: (Settings) -> BenbowApi,
    private val scope: CoroutineScope,
    /** Whether the app is in front; a quiet install waits until it isn't. */
    private val foreground: () -> Boolean = { true },
    private val busy: () -> UpdateBusy?,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state
    private var fetching: Job? = null

    /** Whether this device updates itself here at all (Android). */
    val supported: Boolean get() = updater != null

    private val _quiet = MutableStateFlow(settings().get(QUIET) == "1")

    /** «Обновлять приложение само» (U3): a ready update goes in while the app is away; off until the owner turns it on. */
    val quiet: StateFlow<Boolean> = _quiet

    fun setQuiet(on: Boolean) {
        settings().put(QUIET, if (on) "1" else "0")
        _quiet.value = on
    }

    /** The server's app when it is this app and newer than this build. */
    fun offered(): AndroidApp? {
        val updater = updater ?: return null
        return settings().serverApp?.takeIf { it.packageName == updater.packageName && it.versionCode > updater.versionCode }
    }

    /** After a sync: a newly offered version is fetched and checked; one no longer offered is forgotten. */
    fun check() {
        val updater = updater ?: return
        val app = offered()
        val current = _state.value
        val shown = when (current) {
            UpdateState.None -> null
            is UpdateState.Fetching -> current.app
            is UpdateState.Ready -> current.app
            is UpdateState.Installing -> current.app
            is UpdateState.Failed -> current.app
        }
        if (app == null) {
            if (current !is UpdateState.Installing) _state.value = UpdateState.None
            return
        }
        if (app == shown && current !is UpdateState.Failed) return
        if (fetching?.isActive == true) return
        _state.value = UpdateState.Fetching(app, 0, app.size)
        fetching = scope.launch {
            _state.value = try {
                val file = updater.prepare(app, {
                    api(settings())
                }) { done, total -> _state.value = UpdateState.Fetching(app, done, total) }
                val announced = settings().get(ANNOUNCED)?.toIntOrNull()
                if (announced != app.versionCode) {
                    updater.announce(app)
                    settings().put(ANNOUNCED, app.versionCode.toString())
                }
                UpdateState.Ready(app, file)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                UpdateState.Failed(app, e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** The owner left the app: a quiet update may go in now. */
    fun inBackground() = quietly()

    /** What keeps «Обновить» from working now, or null. */
    fun blocker(): UpdateBusy? = busy()

    fun canInstall(): Boolean = updater?.canInstall() == true

    fun allowInstalls() {
        updater?.allowInstalls()
    }

    /** The owner's «Обновить»: installs a ready update unless something is under way; false when it didn't start. */
    fun install(): Boolean = start(quiet = false)

    /**
     * With [quiet] on, away from the front and nothing under way: the ready update goes in by itself, one try per version.
     * Google Play Protect asks about an app it hasn't seen at every install, over whatever is on screen; a version it
     * didn't take (or Android wanted the owner's yes) waits for «Обновить» instead of asking again each time.
     */
    private fun quietly() {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!_quiet.value || foreground() || settings().get(QUIET_TRIED) == ready.app.versionCode.toString()) return
        if (busy() != null || updater?.canInstall() != true) return
        settings().put(QUIET_TRIED, ready.app.versionCode.toString())
        start(quiet = true)
    }

    private fun start(quiet: Boolean): Boolean {
        val updater = updater ?: return false
        val ready = _state.value as? UpdateState.Ready ?: return false
        if (busy() != null || !updater.canInstall()) return false
        _state.value = UpdateState.Installing(ready.app)
        val outcome = { said: InstallOutcome ->
            _state.value = when (said) {
                is InstallOutcome.Refused -> UpdateState.Failed(ready.app, said.reason)
                InstallOutcome.NeedsYou -> ready
            }
        }
        scope.launch {
            try {
                updater.install(ready.file, quiet, outcome)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                outcome(InstallOutcome.Refused(e.message ?: e::class.simpleName.orEmpty()))
            }
        }
        return true
    }

    private companion object {
        const val ANNOUNCED = "update.announced"
        const val QUIET = "update.quiet"
        const val QUIET_TRIED = "update.quiet.tried"
    }
}

/** A voice note being recorded (the «голос» key held): an update waits for it. */
internal object VoiceRecording {
    val active = MutableStateFlow(false)
}

/** The connected server's API, for the update's download. */
internal fun apiOf(settings: Settings, apis: (app.benbow.client.Connection) -> BenbowApi): BenbowApi =
    apis(settings.connection ?: throw PairingException("Not paired with a server"))
