package org.thechain.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** System fingerprint or face. The app never reads or stores a biometric template. */
object BiometricLogin {
    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun status(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)

    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        return
                    }
                    onError(errString.toString())
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.login_bio_title))
            .setSubtitle(activity.getString(R.string.login_bio_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setNegativeButtonText(activity.getString(R.string.login_bio_cancel))
            .build()
        prompt.authenticate(info)
    }

    fun enrollIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                AUTHENTICATORS,
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun sensorMissing(status: Int): Boolean =
        status == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
            status == BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
}
