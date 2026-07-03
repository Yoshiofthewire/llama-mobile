package com.urlxl.mail

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var mailSettings: MailSettings
    private lateinit var imapHostField: EditText
    private lateinit var imapPortField: EditText
    private lateinit var smtpHostField: EditText
    private lateinit var smtpPortField: EditText
    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var imapFolderField: EditText
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyThemeToActivity(this)

        val root = findViewById<android.view.View>(R.id.settingsRoot)
        applyTopInsetWithHeader(this, root)

        mailSettings = MailSettings(this)
        initViews()
        loadCurrentSettings()

        btnSave.setOnClickListener { saveSettings() }
        applyPrimaryButtonTheme(this, btnSave)
    }

    override fun onResume() {
        super.onResume()
        applyThemeToActivity(this)
        applyPrimaryButtonTheme(this, btnSave)
    }

    private fun initViews() {
        imapHostField = findViewById(R.id.editImapHost)
        imapPortField = findViewById(R.id.editImapPort)
        smtpHostField = findViewById(R.id.editSmtpHost)
        smtpPortField = findViewById(R.id.editSmtpPort)
        usernameField = findViewById(R.id.editUsername)
        passwordField = findViewById(R.id.editPassword)
        imapFolderField = findViewById(R.id.editImapFolder)
        btnSave = findViewById(R.id.btnSaveSettings)
    }

    private fun loadCurrentSettings() {
        imapHostField.setText(mailSettings.getImapHost())
        imapPortField.setText(mailSettings.getImapPort().toString())
        smtpHostField.setText(mailSettings.getSmtpHost())
        smtpPortField.setText(mailSettings.getSmtpPort().toString())
        usernameField.setText(mailSettings.getUsername())
        passwordField.setText(mailSettings.getPassword())
        imapFolderField.setText(mailSettings.getImapFolder())
    }

    private fun saveSettings() {
        val imapHost = imapHostField.text.toString().trim()
        val imapPortStr = imapPortField.text.toString().trim()
        val smtpHost = smtpHostField.text.toString().trim()
        val smtpPortStr = smtpPortField.text.toString().trim()
        val username = usernameField.text.toString().trim()
        val password = passwordField.text.toString().trim()
        val imapFolder = imapFolderField.text.toString().trim().ifBlank { "INBOX" }

        val validationError = validateSettings(
            imapHost = imapHost,
            imapPortStr = imapPortStr,
            smtpHost = smtpHost,
            smtpPortStr = smtpPortStr,
            username = username,
            password = password,
        )

        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_SHORT).show()
            return
        }

        val config = MailAccountConfig(
            imapHost = imapHost,
            imapPort = imapPortStr.toInt(),
            smtpHost = smtpHost,
            smtpPort = smtpPortStr.toInt(),
            username = username,
            password = password,
            folderName = imapFolder,
        )

        mailSettings.saveConfig(config)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

        setResult(RESULT_OK)
        finish()
    }

    private fun validateSettings(
        imapHost: String,
        imapPortStr: String,
        smtpHost: String,
        smtpPortStr: String,
        username: String,
        password: String,
    ): String? {
        if (imapHost.isBlank()) return "IMAP host is required"
        if (smtpHost.isBlank()) return "SMTP host is required"
        if (username.isBlank()) return "Username is required"
        if (password.isBlank()) return "Password is required"

        val imapPort = imapPortStr.toIntOrNull()
        if (imapPort == null || imapPort !in 1..65535) return "IMAP port must be 1-65535"

        val smtpPort = smtpPortStr.toIntOrNull()
        if (smtpPort == null || smtpPort !in 1..65535) return "SMTP port must be 1-65535"

        return null
    }
}
