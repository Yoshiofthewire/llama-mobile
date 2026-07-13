package com.urlxl.mail.push

/**
 * Provides push tokens for different transports (FCM, UnifiedPush, etc.).
 * The token and its associated transport are returned via [onTokenReceived].
 */
interface PushTokenProvider {
    /**
     * Fetch a push token synchronously or trigger async registration.
     * For sync fetches (e.g., FCM), returns the token directly.
     * For async flows (e.g., UnifiedPush distributor picker), returns null
     * and the token arrives later via [onTokenReceived].
     */
    suspend fun fetchToken(): String?

    /**
     * Callback invoked when a new token arrives (sync or async).
     * [transport] identifies the delivery method: "fcm", "unifiedpush", etc.
     */
    fun onTokenReceived(token: String, transport: String)
}

/**
 * FCM token provider: fetches synchronously from Firebase.
 */
class FcmTokenProvider(
    private val onTokenReceived: (token: String, transport: String) -> Unit,
) : PushTokenProvider {
    override suspend fun fetchToken(): String? {
        return runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .let { kotlinx.coroutines.tasks.await() }
        }.getOrNull().also { token ->
            if (token != null) {
                onTokenReceived(token, "fcm")
            }
        }
    }

    override fun onTokenReceived(token: String, transport: String) {
        onTokenReceived.invoke(token, transport)
    }
}

/**
 * UnifiedPush token provider: triggers the distributor picker and receives
 * the endpoint URL asynchronously via a broadcast receiver callback.
 */
class UnifiedPushTokenProvider(
    private val onTokenReceived: (token: String, transport: String) -> Unit,
) : PushTokenProvider {
    override suspend fun fetchToken(): String? {
        // Registration is async; endpoint arrives via onTokenReceived callback from the receiver.
        // Return null here; the actual token will trigger onTokenReceived when available.
        return null
    }

    override fun onTokenReceived(token: String, transport: String) {
        onTokenReceived.invoke(token, transport)
    }
}
