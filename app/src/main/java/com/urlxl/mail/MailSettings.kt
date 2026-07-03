package com.urlxl.mail

import android.content.Context
import android.content.SharedPreferences

class MailSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isConfigured(): Boolean {
        return getImapHost().isNotBlank() &&
            getSmtpHost().isNotBlank() &&
            getUsername().isNotBlank() &&
            getPassword().isNotBlank()
    }

    fun getConfig(): MailAccountConfig {
        return MailAccountConfig(
            imapHost = getImapHost(),
            imapPort = getImapPort(),
            smtpHost = getSmtpHost(),
            smtpPort = getSmtpPort(),
            username = getUsername(),
            password = getPassword(),
            folderName = getImapFolder(),
        )
    }

    fun saveConfig(config: MailAccountConfig) {
        prefs.edit().apply {
            putString(KEY_IMAP_HOST, config.imapHost)
            putInt(KEY_IMAP_PORT, config.imapPort)
            putString(KEY_SMTP_HOST, config.smtpHost)
            putInt(KEY_SMTP_PORT, config.smtpPort)
            putString(KEY_USERNAME, config.username)
            putString(KEY_PASSWORD, config.password)
            putString(KEY_IMAP_FOLDER, config.folderName)
            apply()
        }
    }

    fun getImapHost(): String = prefs.getString(KEY_IMAP_HOST, "") ?: ""
    fun getImapPort(): Int = prefs.getInt(KEY_IMAP_PORT, 993)
    fun getSmtpHost(): String = prefs.getString(KEY_SMTP_HOST, "") ?: ""
    fun getSmtpPort(): Int = prefs.getInt(KEY_SMTP_PORT, 587)
    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""
    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""
    fun getImapFolder(): String = prefs.getString(KEY_IMAP_FOLDER, "INBOX") ?: "INBOX"
    fun getShowKeywords(): Boolean = prefs.getBoolean(KEY_SHOW_KEYWORDS, true)
    fun setShowKeywords(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_KEYWORDS, show).apply()
    }

    companion object {
        private const val PREFS_NAME = "com.urlxl.mail.settings"
        private const val KEY_IMAP_HOST = "imap_host"
        private const val KEY_IMAP_PORT = "imap_port"
        private const val KEY_SMTP_HOST = "smtp_host"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_IMAP_FOLDER = "imap_folder"
        private const val KEY_SHOW_KEYWORDS = "show_keywords"
    }
}

