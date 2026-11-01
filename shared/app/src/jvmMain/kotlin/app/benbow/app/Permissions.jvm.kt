package app.benbow.app

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberNotificationPermission(): () -> Unit = {}

@Composable
internal actual fun rememberLocalNetworkAccess(): LocalNetworkAccess = LocalNetworkAccess(missing = false) { it() }
