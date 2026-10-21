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

    /** Hands the checked file to the system; [failed] hears back when it refuses (a success ends this process). */
    suspend fun install(file: String, failed: (String) -> Unit)

    /** Tells the owner outside the app that [app] is ready (a notification). */
    fun announce(app: AndroidApp)
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
 * Updating this app from its own server (ADR-042, as built 7.19 U2). A sync remembers what app the server offers
 * (`Settings.serverApp`); when it is this app and newer, it is fetched and checked in the background, the owner is told
 * once, and «Ещё» offers «Обновить». The install is the platform's ([AppUpdater]), only on the owner's tap and never
 * while a recording, a dictation or a sound is under way; drafts are saved as the app goes to the background.
 */
class AppUpdates(
    private val updater: AppUpdater?,
    private val settings: () -> Settings,
    private val api: (Settings) -> BenbowApi,
    private val scope: CoroutineScope,
    private val busy: () -> UpdateBusy?,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state
    private var fetching: Job? = null

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

    /** What keeps «Обновить» from working now, or null. */
    fun blocker(): UpdateBusy? = busy()

    fun canInstall(): Boolean = updater?.canInstall() == true

    fun allowInstalls() {
        updater?.allowInstalls()
    }

    /** The owner's «Обновить»: installs a ready update unless something is under way; false when it didn't start. */
    fun install(): Boolean {
        val updater = updater ?: return false
        val ready = _state.value as? UpdateState.Ready ?: return false
        if (busy() != null || !updater.canInstall()) return false
        _state.value = UpdateState.Installing(ready.app)
        val failed = { reason: String -> _state.value = UpdateState.Failed(ready.app, reason) }
        scope.launch {
            try {
                updater.install(ready.file, failed)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
        return true
    }

    private companion object {
        const val ANNOUNCED = "update.announced"
    }
}

/** A voice note being recorded (the «голос» key held): an update waits for it. */
internal object VoiceRecording {
    val active = MutableStateFlow(false)
}

/** The connected server's API, for the update's download. */
internal fun apiOf(settings: Settings, apis: (app.benbow.client.Connection) -> BenbowApi): BenbowApi =
    apis(settings.connection ?: throw PairingException("Not paired with a server"))
