package com.urlxl.mail

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mailSettings = MailSettings(this)

        if (!mailSettings.isConfigured()) {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        val intent = Intent(this, InboxActivity::class.java)
        startActivity(intent)
        finish()
    }
}