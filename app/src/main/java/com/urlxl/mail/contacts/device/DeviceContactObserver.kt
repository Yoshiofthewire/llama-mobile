package com.urlxl.mail.contacts.device

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract

class DeviceContactObserver(
    context: Context,
    private val coordinator: DeviceContactSyncCoordinator,
) : ContentObserver(Handler(Looper.getMainLooper())) {
    private val contentResolver = context.contentResolver

    fun register() {
        contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, this)
    }

    fun unregister() {
        contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean) {
        coordinator.syncWithDebounce()
    }
}
