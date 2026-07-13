package com.urlxl.mail.push

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.urlxl.mail.R
import com.urlxl.mail.applyEmptyStateBackground
import com.urlxl.mail.applyPrimaryButtonTheme
import com.urlxl.mail.applySectionEyebrowLabel
import com.urlxl.mail.applyThemeToActivity
import com.urlxl.mail.applyTopInsetWithHeader
import com.urlxl.mail.getStoredThemePalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date

class PushPairingActivity : AppCompatActivity() {
    private val viewModel: PushHomeViewModel by viewModels()

    private lateinit var historyList: ListView
    private lateinit var historyAdapter: PushHistoryAdapter
    private lateinit var btnResyncToken: Button
    private lateinit var btnClearPairing: Button
    private lateinit var btnScanQr: Button
    private lateinit var btnUseUnifiedPush: Button
    private lateinit var btnUseFirebase: Button

    private lateinit var statusText: TextView
    private lateinit var subscriberText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var lastSyncText: TextView
    private lateinit var syncErrorText: TextView
    private lateinit var transportText: TextView
    private lateinit var latestSenderText: TextView
    private lateinit var latestSubjectText: TextView
    private lateinit var latestKeywordsText: TextView
    private lateinit var historyEmptyText: TextView

    private val dateFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notifications disabled; push still arrives in-app history", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_push_pairing)
        setTitle(R.string.push_pairing_title)

        PushNotificationDispatcher.ensureChannel(this)
        requestNotificationPermissionIfNeeded()
        consumeDeepLink(intent)

        initViews()
        applyTopInsetWithHeader(this, findViewById(R.id.pushPairingRoot))
        historyAdapter = PushHistoryAdapter(this)
        historyList.emptyView = historyEmptyText
        historyList.adapter = historyAdapter

        btnResyncToken.setOnClickListener { viewModel.resyncToken() }
        btnClearPairing.setOnClickListener { viewModel.clearPairing() }
        btnScanQr.setOnClickListener { scanQr() }
        btnUseUnifiedPush.setOnClickListener { viewModel.switchToUnifiedPush(this) }
        btnUseFirebase.setOnClickListener { viewModel.switchToFirebase() }

        applyThemeToActivity(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyThemeToActivity(this)
        applyPrimaryButtonTheme(this, btnResyncToken)
        applyPrimaryButtonTheme(this, btnClearPairing)
        applyPrimaryButtonTheme(this, btnScanQr)
        applyPrimaryButtonTheme(this, btnUseUnifiedPush)
        applyPrimaryButtonTheme(this, btnUseFirebase)
        applyEmptyStateBackground(this, historyEmptyText)
        applySectionEyebrowLabel(this, findViewById(R.id.pushPairingLatestTitle))
        applySectionEyebrowLabel(this, findViewById(R.id.pushPairingHistoryTitle))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        consumeDeepLink(intent)
    }

    private fun initViews() {
        historyList = findViewById(R.id.pushPairingHistoryList)
        btnScanQr = findViewById(R.id.btnScanQr)
        statusText = findViewById(R.id.pushPairingStatus)
        subscriberText = findViewById(R.id.pushPairingSubscriber)
        deviceIdText = findViewById(R.id.pushPairingDeviceId)
        lastSyncText = findViewById(R.id.pushPairingLastSync)
        syncErrorText = findViewById(R.id.pushPairingSyncError)
        transportText = findViewById(R.id.pushPairingTransport)
        latestSenderText = findViewById(R.id.pushPairingLatestSender)
        latestSubjectText = findViewById(R.id.pushPairingLatestSubject)
        latestKeywordsText = findViewById(R.id.pushPairingLatestKeywords)
        historyEmptyText = findViewById(R.id.pushPairingHistoryEmpty)
        btnResyncToken = findViewById(R.id.btnResyncToken)
        btnClearPairing = findViewById(R.id.btnClearPairing)
        btnUseUnifiedPush = findViewById(R.id.btnUseUnifiedPush)
        btnUseFirebase = findViewById(R.id.btnUseFirebase)
    }

    private fun render(state: PushHomeUiState) {
        val baseStatus = getString(
            if (state.pairing == null) R.string.push_pairing_status_not_paired else R.string.push_pairing_status_paired,
        )
        statusText.text = if (state.pairing == null) {
            baseStatus
        } else {
            val modeLabel = if (state.deliveryMode == DeliveryMode.PULL) "App Pull" else "Relay Push"
            "$baseStatus • $modeLabel"
        }
        subscriberText.text = "Subscriber ID: ${state.pairing?.subscriberId?.let { maskTail(it, 6) } ?: "-"}"
        deviceIdText.text = "Device ID: ${state.pairing?.deviceId ?: "-"}"
        lastSyncText.text = "Last token sync: ${state.lastTokenSyncAtEpochMs?.let { dateFormat.format(Date(it)) } ?: "-"}"

        if (state.syncError.isNullOrBlank()) {
            syncErrorText.visibility = View.GONE
        } else {
            syncErrorText.visibility = View.VISIBLE
            syncErrorText.text = "Sync error: ${state.syncError}"
        }

        transportText.text = "Notification method: ${transportLabel(state.transport)}"

        latestSenderText.text = "Sender: ${state.latestPayload?.let { PushPayloadParser.title(it) } ?: "-"}"
        latestSubjectText.text = "Subject: ${state.latestPayload?.let { PushPayloadParser.body(it) } ?: "-"}"
        latestKeywordsText.text = "Keywords: ${state.latestPayload?.keywords?.joinToString() ?: "-"}"

        historyAdapter.submit(state.history)

        val paired = state.pairing != null
        btnResyncToken.isEnabled = !state.isWorking
        btnClearPairing.isEnabled = !state.isWorking
        btnScanQr.isEnabled = !state.isWorking
        btnUseUnifiedPush.isEnabled = !state.isWorking && paired && state.transport != "unifiedpush"
        btnUseFirebase.isEnabled = !state.isWorking && paired && state.transport == "unifiedpush"

        val message = state.localMessage
        if (!message.isNullOrBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeLocalMessage()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun consumeDeepLink(intent: android.content.Intent?) {
        val data = intent?.dataString ?: return
        viewModel.pairFromLink(data)
    }

    private fun scanQr() {
        lifecycleScope.launch {
            runCatching {
                val result = GmsBarcodeScanning.getClient(this@PushPairingActivity).startScan().await()
                result.rawValue.orEmpty()
            }.onSuccess { raw ->
                if (raw.isNotBlank()) {
                    viewModel.pairFromLink(raw)
                }
            }.onFailure {
                Toast.makeText(this@PushPairingActivity, "QR scan canceled or failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class PushHistoryAdapter(context: android.content.Context) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)
        private var items: List<PushPayload> = emptyList()

        fun submit(payloads: List<PushPayload>) {
            items = payloads
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): PushPayload = items[position]
        override fun getItemId(position: Int): Long = items[position].messageId.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_push_history, parent, false)
            val payload = items[position]
            val palette = getStoredThemePalette(view.context)

            val title = view.findViewById<TextView>(R.id.pushHistoryTitle)
            val subject = view.findViewById<TextView>(R.id.pushHistorySubject)
            val date = view.findViewById<TextView>(R.id.pushHistoryDate)

            title.text = PushPayloadParser.title(payload)
            subject.text = PushPayloadParser.body(payload)
            date.text = dateFormat.format(Date(payload.receivedAtEpochMs))

            val panelColor = android.graphics.Color.parseColor(palette.panel)
            val inkStrongColor = android.graphics.Color.parseColor(palette.inkStrong)
            val inkColor = android.graphics.Color.parseColor(palette.ink)
            (view as? androidx.cardview.widget.CardView)?.setCardBackgroundColor(panelColor)
            title.setTextColor(inkStrongColor)
            subject.setTextColor(inkColor)
            date.setTextColor(inkColor)

            return view
        }
    }
}

private fun maskTail(value: String, keepLast: Int): String {
    if (value.length <= keepLast) return value
    val mask = "*".repeat(value.length - keepLast)
    return "$mask${value.takeLast(keepLast)}"
}

// Mirrors the badge labels on the web Notifications page; null means never synced yet.
private fun transportLabel(transport: String?): String = when (transport?.trim()?.lowercase()) {
    "unifiedpush" -> "UnifiedPush"
    "apns" -> "APNs"
    "fcm" -> "Firebase"
    else -> "Firebase (default)"
}
