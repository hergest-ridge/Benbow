package app.benbow.app

/** The apps pair with any server by address and fingerprint. */
internal actual val pageServer: String? = null

/** The apps have no address bar: Back is the system's (Android) or Alt+← (desktop). */
@androidx.compose.runtime.Composable
internal actual fun PlatformAddress(backStack: List<Route>, nav: Navigator, open: (Link) -> Unit) = Unit
