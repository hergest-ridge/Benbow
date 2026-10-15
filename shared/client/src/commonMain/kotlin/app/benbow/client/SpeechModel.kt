package app.benbow.client

import kotlinx.coroutines.flow.StateFlow

/**
 * The speech-recognition model on this device (7.6, ADR-025): on Android only ([SpeechModelStore] there); the browser
 * and desktop have none, so the screens show no speech parts there.
 */
interface SpeechModel {
    sealed interface State {
        data object Missing : State

        /** The server doesn't have it yet: the owner downloads it on the server's page first. */
        data object NotOnServer : State

        /** The server predates the model (before 0.5.0): it has to be updated first. */
        data object ServerTooOld : State

        data class Downloading(val done: Long, val total: Long) : State

        data object Ready : State

        data class Failed(val message: String) : State
    }

    val state: StateFlow<State>

    /** One of the model's files on this device. */
    fun file(name: String): LocalFile

    /** Fetches what's missing from the server and checks it; safe to call again after a failure or a restart. */
    suspend fun fetch()

    fun delete()
}
