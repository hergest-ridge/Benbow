package app.benbow.app

import androidx.compose.runtime.Composable

/** No owner check on desktop yet (Windows Hello isn't reachable from the JVM without native code). */
@Composable
internal actual fun rememberDeviceAuth(): DeviceAuth? = null
