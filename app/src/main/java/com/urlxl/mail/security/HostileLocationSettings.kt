package com.urlxl.mail.security

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "com.urlxl.mail.hostile_location_settings"
private const val KEY_ENABLED = "enabled"

/**
 * Whether Hostile Location Protection is on (see the 2026-07-22 security-hardening spec) — a
 * plain, unencrypted flag (not a secret) that [com.urlxl.mail.data.DataGraph] reads at Room
 * construction time to decide disk-backed vs in-memory-only storage.
 */
class HostileLocationSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
