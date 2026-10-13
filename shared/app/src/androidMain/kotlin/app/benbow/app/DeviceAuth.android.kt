package app.benbow.app

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The system prompt: fingerprint or face, with the screen lock (PIN, pattern, password) always as the way in. */
@Composable
internal actual fun rememberDeviceAuth(): DeviceAuth? {
    val context = LocalContext.current
    return remember(context) {
        object : DeviceAuth {
            private val allowed = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

            override fun available(): Boolean =
                context.getSystemService(BiometricManager::class.java).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS

            override fun authenticate(title: String, onResult: (Boolean) -> Unit) {
                val prompt = BiometricPrompt.Builder(context).setTitle(title).setAllowedAuthenticators(allowed).build()
                prompt.authenticate(
                    CancellationSignal(),
                    context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
                    },
                )
            }
        }
    }
}
