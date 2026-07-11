package com.urlxl.mail.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.urlxl.mail.R
import com.urlxl.mail.applyEmptyStateBackground
import com.urlxl.mail.applyThemeToActivity
import com.urlxl.mail.applyThemedTitle
import com.urlxl.mail.applyTopInsetWithHeader
import com.urlxl.mail.contacts.device.DeviceContactsRuntime
import com.urlxl.mail.contacts.device.DeviceContactSyncScheduler
import com.urlxl.mail.data.ContactEntity
import kotlinx.coroutines.launch

class ContactsListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: View
    private lateinit var adapter: ContactAdapter
    private val contactPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, R.string.contacts_device_sync_permission_denied, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        enableDeviceSyncAfterPermissionGrant()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_contacts_list)
            applyThemeToActivity(this)
            applyThemedTitle(this, getString(R.string.contacts_title))
            applyTopInsetWithHeader(this, findViewById(R.id.contactsRoot))

            recyclerView = findViewById(R.id.recyclerViewContacts)
            emptyText = findViewById(R.id.contactsEmptyText)
            val addButton = findViewById<FloatingActionButton>(R.id.btnAddContact)

            adapter = ContactAdapter { contact ->
                startActivity(
                    Intent(this, ContactEditActivity::class.java)
                        .putExtra(ContactEditActivity.EXTRA_UID, contact.uid),
                )
            }
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = adapter

            addButton.setOnClickListener {
                startActivity(Intent(this, ContactEditActivity::class.java))
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactsListActivity", "onCreate crashed", e)
            finish()
            return
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    ContactsRuntime.graph(this@ContactsListActivity).repository.observeContacts().collect { contacts ->
                        render(contacts)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ContactsListActivity", "Error observing contacts", e)
                    Toast.makeText(this@ContactsListActivity, "Error loading contacts", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // No syncs while debugging - just render empty list
    }

    override fun onStop() {
        super.onStop()
        // Cleanup if needed
    }

    override fun onResume() {
        super.onResume()
        applyThemeToActivity(this)
        applyEmptyStateBackground(this, emptyText)
        adapter.notifyDataSetChanged()
    }

    private fun render(contacts: List<ContactEntity>) {
        adapter.updateContacts(contacts)
        emptyText.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, MENU_REFRESH, 0, R.string.contacts_refresh)
        menu?.add(0, MENU_DEVICE_SYNC, 0, R.string.contacts_device_sync_enable)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val deviceSyncItem = menu?.findItem(MENU_DEVICE_SYNC)
        if (deviceSyncItem != null) {
            try {
                val isEnabled = DeviceContactsRuntime.graph(this).settings.isEnabled()
                deviceSyncItem.title = getString(
                    if (isEnabled) R.string.contacts_device_sync_disable else R.string.contacts_device_sync_enable,
                )
            } catch (e: Exception) {
                android.util.Log.e("ContactsListActivity", "Error getting device sync status", e)
                deviceSyncItem.title = getString(R.string.contacts_device_sync_enable)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_REFRESH -> {
                // User-triggered, so — unlike the silent foreground/post-edit auto-sync — this one
                // reports its outcome back, matching the error table in Mobile_Contact_Sync.md.
                lifecycleScope.launch {
                    val message = when (val outcome = ContactsRuntime.graph(this@ContactsListActivity).repository.sync()) {
                        ContactSyncOutcome.Success -> getString(R.string.contacts_sync_success)
                        ContactSyncOutcome.NotPaired -> getString(R.string.connection_mode_relay_not_paired)
                        ContactSyncOutcome.Unauthorized -> getString(R.string.contacts_sync_unauthorized)
                        is ContactSyncOutcome.ServiceUnavailable -> outcome.message
                        is ContactSyncOutcome.Retry -> outcome.message
                    }
                    Toast.makeText(this@ContactsListActivity, message, Toast.LENGTH_SHORT).show()
                }
                true
            }
            MENU_DEVICE_SYNC -> {
                val graph = DeviceContactsRuntime.graph(this)
                if (graph.settings.isEnabled()) {
                    disableDeviceSync()
                } else {
                    checkAndEnableDeviceSync()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkAndEnableDeviceSync() {
        val readContactsGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        val writeContactsGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

        if (readContactsGranted && writeContactsGranted) {
            enableDeviceSyncAfterPermissionGrant()
        } else {
            contactPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS,
                ),
            )
        }
    }

    private fun enableDeviceSyncAfterPermissionGrant() {
        val graph = DeviceContactsRuntime.graph(this)
        lifecycleScope.launch {
            try {
                graph.accountManager.ensureAccount()
                graph.settings.setEnabled(true)
                graph.observer.register()
                DeviceContactSyncScheduler.ensurePeriodic(this@ContactsListActivity)
                graph.coordinator.syncNowAsync()
                Toast.makeText(
                    this@ContactsListActivity,
                    R.string.contacts_device_sync_enabled_toast,
                    Toast.LENGTH_SHORT,
                ).show()
                invalidateOptionsMenu()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ContactsListActivity,
                    "Failed to enable device sync: ${e.message}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun disableDeviceSync() {
        val graph = DeviceContactsRuntime.graph(this)
        lifecycleScope.launch {
            try {
                graph.settings.setEnabled(false)
                DeviceContactSyncScheduler.cancelPeriodic(this@ContactsListActivity)
                graph.observer.unregister()
                graph.accountManager.removeAccount()
                Toast.makeText(
                    this@ContactsListActivity,
                    R.string.contacts_device_sync_disabled_toast,
                    Toast.LENGTH_SHORT,
                ).show()
                invalidateOptionsMenu()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ContactsListActivity,
                    "Failed to disable device sync: ${e.message}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    companion object {
        private const val MENU_REFRESH = 0
        private const val MENU_DEVICE_SYNC = 1
    }
}
