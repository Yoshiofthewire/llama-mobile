package com.urlxl.mail.push

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

class PushSyncCoordinator(
    private val repository: PushRepository,
    private val registrationClient: NativeRegistrationClient,
) {
    suspend fun attemptPairing(pairing: PairingData): NativeRegistrationResult {
        val token = fetchFcmTokenOrNull()
            ?: return NativeRegistrationResult.Error("Unable to fetch FCM token")

        val result = registrationClient.register(pairing = pairing, token = token)
        if (result is NativeRegistrationResult.Success) {
            repository.savePairing(pairing.copy(deviceId = result.deviceId ?: pairing.deviceId))
            persistDelivery(pairing, result)
            repository.updateTransport(result.transport)
            repository.updateSyncState(lastSyncAtEpochMs = result.syncedAtEpochMs, syncError = null)
        }
        return result
    }

    suspend fun syncCurrentPairingToken(): NativeRegistrationResult {
        val state = repository.state.first()
        val pairing = state.pairing ?: return NativeRegistrationResult.Error("Device is not paired")

        val token = fetchFcmTokenOrNull()
            ?: run {
                repository.updateSyncState(lastSyncAtEpochMs = null, syncError = "Unable to fetch FCM token")
                return NativeRegistrationResult.Error("Unable to fetch FCM token")
            }

        return syncAndPersist(pairing = pairing, token = token)
    }

    suspend fun syncProvidedToken(token: String, transport: String? = null): NativeRegistrationResult {
        val state = repository.state.first()
        val pairing = state.pairing ?: return NativeRegistrationResult.Error("Device is not paired")
        return syncAndPersist(pairing = pairing, token = token, transport = transport)
    }

    private suspend fun syncAndPersist(pairing: PairingData, token: String, transport: String? = null): NativeRegistrationResult {
        val result = registrationClient.register(pairing = pairing, token = token, transport = transport)
        when (result) {
            is NativeRegistrationResult.Success -> {
                repository.savePairing(pairing.copy(deviceId = result.deviceId ?: pairing.deviceId))
                persistDelivery(pairing, result)
                repository.updateTransport(result.transport)
                repository.updateSyncState(lastSyncAtEpochMs = result.syncedAtEpochMs, syncError = null)
            }
            is NativeRegistrationResult.Error -> repository.updateSyncState(lastSyncAtEpochMs = null, syncError = result.message)
        }
        return result
    }

    private suspend fun persistDelivery(pairing: PairingData, result: NativeRegistrationResult.Success) {
        val endpoint = resolvePullEndpoint(pairing.serverUrl, result.pullEndpoint)
        repository.updateDelivery(result.deliveryMode, endpoint)
    }

    private suspend fun fetchFcmTokenOrNull(): String? {
        return runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
    }
}
