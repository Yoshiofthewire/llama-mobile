package com.urlxl.mail.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MfaResponseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val approve = when (intent.action) {
            PushNotificationDispatcher.ACTION_MFA_APPROVE -> true
            PushNotificationDispatcher.ACTION_MFA_DENY -> false
            else -> return
        }
        val challengeId = intent.getStringExtra(PushNotificationDispatcher.EXTRA_MFA_CHALLENGE_ID).orEmpty()
        if (challengeId.isBlank()) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                respond(appContext, challengeId, approve)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        suspend fun respond(context: Context, challengeId: String, approve: Boolean) {
            PushNotificationDispatcher.cancelMfaChallenge(context, challengeId)

            val graph = PushRuntime.graph(context)
            val pairing = graph.repository.pairingForAuthenticatedCall()
            if (pairing == null) {
                showResultToast(context, "Not paired with a server")
                return
            }

            when (val result = graph.mfaResponseClient.respond(pairing, challengeId, approve)) {
                is MfaRespondResult.Success -> showResultToast(
                    context,
                    if (approve) "Sign-in approved" else "Sign-in denied",
                )
                is MfaRespondResult.Error -> showResultToast(context, result.message)
            }
        }

        private suspend fun showResultToast(context: Context, message: String) = withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
