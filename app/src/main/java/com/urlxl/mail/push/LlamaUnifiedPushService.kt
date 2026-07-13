package com.urlxl.mail.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush protocol events from whichever distributor the user picked
 * (ntfy, etc). Mirrors LlamaFirebaseMessagingService but for the UnifiedPush transport.
 * PushService is the current API; the older MessagingReceiver broadcast-based API
 * this replaced is deprecated upstream.
 */
class LlamaUnifiedPushService : PushService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val graph = PushRuntime.graph(applicationContext)
        serviceScope.launch {
            graph.syncCoordinator.syncProvidedToken(endpoint.url, transport = "unifiedpush")
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        // Distributor rejected registration (e.g. it requires VAPID/encryption, which this
        // first cut doesn't implement — see UNIFIEDPUSH_IMPLEMENTATION.md). Clear the stale
        // distributor selection and fall back to FCM so the user isn't left with no delivery
        // at all, and surface the failure through the same syncError the FCM path already
        // renders in the pairing UI.
        UnifiedPush.removeDistributor(applicationContext)
        val graph = PushRuntime.graph(applicationContext)
        serviceScope.launch {
            graph.repository.updateSyncState(lastSyncAtEpochMs = null, syncError = "UnifiedPush registration failed: $reason — reverted to Firebase")
            graph.syncCoordinator.syncCurrentPairingToken()
        }
    }

    override fun onUnregistered(instance: String) {
        // Explicit unregistration (user switched distributor away, or picked "none"):
        // fall back to FCM so delivery keeps working without user intervention.
        val graph = PushRuntime.graph(applicationContext)
        serviceScope.launch {
            graph.syncCoordinator.syncCurrentPairingToken()
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val text = String(message.content, Charsets.UTF_8)
        val data = runCatching {
            json.decodeFromString<Map<String, String>>(text)
        }.getOrNull() ?: return

        // MFA challenges are excluded from UnifiedPush by design (unencrypted transport);
        // only mail notifications are expected here, but parse defensively via the same path.
        val payload = PushPayloadParser.parse(data) ?: return
        val graph = PushRuntime.graph(applicationContext)
        serviceScope.launch {
            graph.repository.appendPayload(payload)
        }
        PushNotificationDispatcher.show(applicationContext, payload)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
