package app.benbow.app

import androidx.compose.runtime.Composable

/**
 * The home network, where the server is (ADR-062). Android 17 keeps an app targeting it off the network until
 * «Устройства поблизости» is granted; without it a connection to the server only times out.
 */
internal class LocalNetworkAccess(val missing: Boolean, private val request: (then: () -> Unit) -> Unit) {
    /** Runs [then] once the app may reach the network: right away, or after the system's question is answered yes. */
    fun ask(then: () -> Unit = {}) = if (missing) request(then) else then()
}

/** Open everywhere but on Android 17 and later. */
@Composable
internal expect fun rememberLocalNetworkAccess(): LocalNetworkAccess
