package com.urlxl.mail.security

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.urlxl.mail.R
import com.urlxl.mail.applyThemeToActivity
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * Full-screen PIN gate shown whenever [AppLockManager.locked] is true — see "Require Unlock to
 * Open" in the 2026-07-22 security-hardening spec. Biometric unlock (Task 14) layers on top of
 * this; the PIN field here is always present as the fallback.
 */
class UnlockActivity : AppCompatActivity() {
    private lateinit var pinField: EditText
    private lateinit var errorText: TextView
    private lateinit var submitButton: Button
    private lateinit var appLockManager: AppLockManager
    private var countdown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)
        applyThemeToActivity(this)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)

        appLockManager = SecurityRuntime.graph(this).appLockManager

        pinField = findViewById(R.id.unlockPinField)
        errorText = findViewById(R.id.unlockErrorText)
        submitButton = findViewById(R.id.unlockSubmitButton)
        submitButton.setOnClickListener { attemptUnlock() }

        if (AppLockStore(this).isBiometricEnabled()) {
            showBiometricPromptIfAvailable()
        }
    }

    override fun onResume() {
        super.onResume()
        applyRemainingLockout()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdown?.cancel()
    }

    private fun attemptUnlock() {
        val pin = pinField.text.toString()
        when (val result = appLockManager.attemptPin(pin)) {
            is UnlockAttemptResult.Success -> {
                // Closes the gap where a background FCM token rotation saved the pairing
                // unwrapped because no credential key was cached yet in this process — see
                // rewrapPairingIfNeeded's doc comment. finish() runs inside the same coroutine,
                // after the rewrap, rather than concurrently with it: finish() begins tearing the
                // activity down, which would cancel a still-running lifecycleScope coroutine
                // launched alongside it. The rewrap itself is a couple of fast, local-only
                // SharedPreferences reads/writes, so this doesn't visibly delay unlock.
                lifecycleScope.launch {
                    rewrapPairingIfNeeded(this@UnlockActivity, appLockManager)
                    finish()
                }
            }
            is UnlockAttemptResult.Wiped -> restartToFirstRun()
            is UnlockAttemptResult.Rejected -> {
                pinField.text.clear()
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.unlock_wrong_pin)
                if (result.delayMillis > 0) applyRemainingLockout()
            }
        }
    }

    private fun applyRemainingLockout() {
        val remaining = appLockManager.remainingLockoutMillis()
        countdown?.cancel()
        if (remaining <= 0) {
            submitButton.isEnabled = true
            pinField.isEnabled = true
            return
        }
        submitButton.isEnabled = false
        pinField.isEnabled = false
        countdown = object : CountDownTimer(remaining, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.unlock_locked_out, "${millisUntilFinished / 1_000}s")
            }

            override fun onFinish() {
                submitButton.isEnabled = true
                pinField.isEnabled = true
                errorText.visibility = View.GONE
            }
        }.start()
    }

    private fun showBiometricPromptIfAvailable() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) return

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_title))
            .setNegativeButtonText(getString(R.string.unlock_use_pin_button))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appLockManager.unlockWithBiometric()
                    finish()
                }
                // onAuthenticationError (includes the user tapping "Use PIN") and
                // onAuthenticationFailed both just leave the always-visible PIN field as the
                // fallback — no separate handling needed.
            },
        )
        prompt.authenticate(promptInfo)
    }

    private fun restartToFirstRun() {
        lifecycleScope.launch {
            // SecurityWipe already ran (inside AppLockManager.attemptPin's onWipe callback) by
            // the time UnlockAttemptResult.Wiped is returned — this just relaunches so the app
            // picks up the now-cleared state.
            AppRestart.relaunch(this@UnlockActivity)
        }
    }
}
