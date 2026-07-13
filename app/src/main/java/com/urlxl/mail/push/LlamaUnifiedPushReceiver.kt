package com.urlxl.mail.push

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * Receiver for UnifiedPush (distributor-based push) messages and endpoint updates.
 * Mirrors LlamaFirebaseMessagingService but handles the UnifiedPush protocol.
 */
class LlamaUnifiedPushReceiver : MessagingReceiver() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        // Called when a new UnifiedPush endpoint is registered (e.g., from ntfy distributor).
        // Endpoint is a URL like https://ntfy.sh/<topic>, stored as deviceToken.
        val graph = PushRuntime.graph(context)
        serviceScope.launch {
            graph.syncCoordinator.syncProvidedToken(endpoint, transport = "unifiedpush")
        }
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        // Called when UnifiedPush registration fails (e.g., no distributor app installed).
        // Log and let fallback (FCM or pull-mode) handle delivery.
    }

    override fun onUnregistered(context: Context, instance: String) {
        // Called when UnifiedPush is explicitly unregistered.
        // Clear stored endpoint; let pull-mode or FCM take over.
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        // Called when a message arrives via UnifiedPush.
        // Message body is JSON-serialized; parse and display same way as FCM.
        val text = String(message, Charsets.UTF_8)
        val data = runCatching {
            json.decodeFromString<Map<String, String>>(text)
        }.getOrNull() ?: return

        // Check for MFA challenge first (though UnifiedPush devices are excluded from MFA by design).
        val mfaChallenge = MfaChallengePayloadParser.parse(data)
        if (mfaChallenge != null) {
            PushNotificationDispatcher.showMfaChallenge(context, mfaChallenge)
            return
        }

        // Parse as a mail notification payload.
        val payload = PushPayloadParser.parse(data) ?: return
        val graph = PushRuntime.graph(context)
        serviceScope.launch {
            graph.repository.appendPayload(payload)
        }
        PushNotificationDispatcher.show(context, payload)
    }

    override fun onDestroy() {
        serviceScope.cancel()
    }
}
