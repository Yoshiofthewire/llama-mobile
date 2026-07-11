package com.urlxl.mail

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.urlxl.mail.push.MfaApprovalActivity
import com.urlxl.mail.push.MfaChallengePayloadParser
import com.urlxl.mail.push.PushNotificationDispatcher
import com.urlxl.mail.push.PushRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val mailSettings = MailSettings(this)

        lifecycleScope.launch {
            intent.extras?.let { extras ->
                MfaChallengePayloadParser.parse(extras)?.let { mfa ->
                    val mfaIntent = Intent(this@MainActivity, MfaApprovalActivity::class.java)
                    mfaIntent.putExtra(PushNotificationDispatcher.EXTRA_MFA_CHALLENGE_ID, mfa.challengeId)
                    startActivity(mfaIntent)
                    finish()
                    return@launch
                }
            }

            // Manual IMAP mode is "configured" once host/user/pass are filled in; Relay mode is
            // "configured" once the device is paired — checking isConfigured() alone here would
            // trap a paired, relay-only user in a loop back to Settings since they never fill IMAP
            // fields.
            val configured = when (mailSettings.getConnectionMode()) {
                MailConnectionMode.RELAY -> PushRuntime.graph(this@MainActivity).repository.state.first().pairing != null
                MailConnectionMode.MANUAL_IMAP -> mailSettings.isConfigured()
            }

            val targetIntent = if (configured) {
                Intent(this@MainActivity, InboxActivity::class.java).apply {
                    val msgId = intent.getStringExtra(PushNotificationDispatcher.EXTRA_MESSAGE_ID)
                    if (msgId != null) {
                        putExtra(PushNotificationDispatcher.EXTRA_MESSAGE_ID, msgId)
                        putExtra(PushNotificationDispatcher.EXTRA_SENDER, intent.getStringExtra(PushNotificationDispatcher.EXTRA_SENDER))
                        putExtra(PushNotificationDispatcher.EXTRA_SUBJECT, intent.getStringExtra(PushNotificationDispatcher.EXTRA_SUBJECT))
                    }
                }
            } else {
                Intent(this@MainActivity, SettingsActivity::class.java)
            }
            startActivity(targetIntent)
            finish()
        }
    }
}
