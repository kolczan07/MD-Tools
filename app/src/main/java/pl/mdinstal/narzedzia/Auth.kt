package pl.mdinstal.narzedzia

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Prosi o odcisk palca / PIN / wzór telefonu.
 * Gdy urządzenie nie ma żadnej blokady ekranu, potwierdzenie jest pomijane (wynik: true).
 */
fun authenticate(activity: FragmentActivity, title: String, subtitle: String?, onResult: (Boolean) -> Unit) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        onResult(true)
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }
        }
    )
    val b = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(allowed)
    if (subtitle != null) b.setSubtitle(subtitle)
    prompt.authenticate(b.build())
}
