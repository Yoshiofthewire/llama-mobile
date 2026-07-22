package com.urlxl.mail

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.urlxl.mail.contacts.ContactsRuntime
import com.urlxl.mail.contacts.device.DeviceContactsRuntime
import com.urlxl.mail.push.PushNotificationDispatcher
import com.urlxl.mail.push.PushRuntime
import com.urlxl.mail.security.SecurityRuntime
import com.urlxl.mail.security.UnlockActivity

/**
 * Process-level wiring for push/pull delivery. Observes the process lifecycle so that every
 * time the app foregrounds we re-read the authoritative delivery mode and, when in "App Pull"
 * mode, kick an immediate pull — complementing the WorkManager periodic baseline. In push mode
 * [com.urlxl.mail.push.PullSyncCoordinator.pullOnce] no-ops and disarms the periodic worker.
 */
class KyPostApp : Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        super<Application>.onCreate()
        PushNotificationDispatcher.ensureChannel(this)
        try {
            DeviceContactsRuntime.graph(this).bootstrapIfEnabled()
        } catch (e: Exception) {
            android.util.Log.e("KyPostApp", "Failed to bootstrap device contacts", e)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // App moved to the foreground.
        if (SecurityRuntime.graph(this).appLockManager.locked.value) {
            startActivity(
                Intent(this, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        try {
            PushRuntime.graph(this).pullCoordinator.pullNowAsync()
        } catch (e: Exception) {
            android.util.Log.e("KyPostApp", "Failed to pull", e)
        }
        try {
            ContactsRuntime.graph(this).coordinator.syncNowAsync()
        } catch (e: Exception) {
            android.util.Log.e("KyPostApp", "Failed to sync contacts (relay)", e)
        }
        try {
            DeviceContactsRuntime.graph(this).coordinator.syncNowAsync()
        } catch (e: Exception) {
            android.util.Log.e("KyPostApp", "Failed to sync contacts (device)", e)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        SecurityRuntime.graph(this).appLockManager.lockNow()
    }
}
