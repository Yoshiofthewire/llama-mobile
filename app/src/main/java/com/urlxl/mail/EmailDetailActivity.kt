package com.urlxl.mail

import android.os.Bundle
import android.text.TextUtils
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class EmailDetailActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_detail)
        applyThemeToActivity(this)

        val root = findViewById<android.view.View>(R.id.emailDetailRoot)
        applyTopInsetWithHeader(this, root)

        val emailId = intent.getStringExtra("email_id").orEmpty()
        val emailFolder = intent.getStringExtra("email_folder") ?: "INBOX"
        val emailSubject = intent.getStringExtra("email_subject") ?: "No subject"
        val emailSender = intent.getStringExtra("email_sender") ?: "Unknown sender"
        val emailPreview = intent.getStringExtra("email_preview") ?: "No content"

        setTitle(R.string.email_title)

        val subjectView = findViewById<TextView>(R.id.emailSubject)
        val fromView = findViewById<TextView>(R.id.emailFrom)
        val webView = findViewById<WebView>(R.id.emailWebView)
        val loading = findViewById<ProgressBar>(R.id.emailBodyLoading)

        subjectView.text = emailSubject
        fromView.text = getString(R.string.email_from) + " " + emailSender

        webView.settings.apply {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            defaultTextEncodingName = "utf-8"
        }

        ioExecutor.execute {
            val gateway = MailGateway.fromSettings(this)
            val fetchedBody = gateway.fetchEmailBodyHtml(emailId, emailFolder).orEmpty()
            val bodyToRender = if (fetchedBody.isNotBlank()) fetchedBody else TextUtils.htmlEncode(emailPreview)

            val htmlContent = """
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <style>
                        body {
                            font-family: sans-serif;
                            font-size: 16px;
                            line-height: 1.5;
                            color: #222;
                            margin: 0;
                            padding: 8px;
                            word-break: break-word;
                        }
                        img { max-width: 100%; height: auto; }
                        pre { white-space: pre-wrap; }
                    </style>
                </head>
                <body>$bodyToRender</body>
                </html>
            """.trimIndent()

            runOnUiThread {
                webView.loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", null)
                loading.visibility = android.view.View.GONE
            }
        }
    }

    private fun actionBarSize(): Int {
        val typedArray = theme.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        return try {
            typedArray.getDimensionPixelSize(0, 0)
        } finally {
            typedArray.recycle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}
