package com.urlxl.mail

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.urlxl.mail.mail.MailOutcome
import com.urlxl.mail.mail.MailRepository
import com.urlxl.mail.mail.MailRuntime
import java.util.concurrent.Executors

class EmailDetailActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var mailRepository: MailRepository
    private lateinit var actionButtons: List<ImageButton>
    private lateinit var divider: View
    private lateinit var webView: WebView
    private var lastAppliedThemeName: String = ""

    private var toRecipients: List<String> = emptyList()
    private var ccRecipients: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_detail)
        applyThemeToActivity(this)
        lastAppliedThemeName = getStoredThemeName(this)

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
        webView = findViewById(R.id.emailWebView)
        divider = findViewById(R.id.emailDivider)
        val loading = findViewById<ProgressBar>(R.id.emailBodyLoading)

        subjectView.text = emailSubject
        fromView.text = getString(R.string.email_from) + " " + emailSender

        mailRepository = MailRuntime.graph(this).repository

        val actionArchive = findViewById<ImageButton>(R.id.actionArchive)
        val actionJunk = findViewById<ImageButton>(R.id.actionJunk)
        val actionDelete = findViewById<ImageButton>(R.id.actionDelete)
        val actionReply = findViewById<ImageButton>(R.id.actionReply)
        val actionReplyAll = findViewById<ImageButton>(R.id.actionReplyAll)
        val actionForward = findViewById<ImageButton>(R.id.actionForward)
        actionButtons = listOf(
            actionReply, actionReplyAll, actionForward,
            actionArchive, actionJunk, actionDelete,
        )
        applyDetailChrome()

        MailBackgroundExecutor.submit { mailRepository.markRead(emailId, emailFolder) }

        actionArchive.setOnClickListener {
            runMailActionAndFinish(getString(R.string.action_archive), emailId) { it.archive(emailId, emailFolder) }
        }
        actionDelete.setOnClickListener {
            runMailActionAndFinish(getString(R.string.action_delete), emailId) { it.delete(emailId, emailFolder) }
        }
        actionJunk.setOnClickListener {
            runMailActionAndFinish(getString(R.string.action_junk), emailId) { it.spam(emailId, emailFolder) }
        }
        actionReply.setOnClickListener {
            openCompose(
                to = extractAddress(emailSender),
                subject = withPrefix(emailSubject, "Re:"),
                body = quoteForReply(emailSender, emailPreview),
            )
        }
        actionReplyAll.setOnClickListener {
            val myAddress = MailSettings(this).getUsername()
            val recipients = (listOf(extractAddress(emailSender)) + toRecipients.map(::extractAddress) + ccRecipients.map(::extractAddress))
                .distinct()
                .filter { it.isNotBlank() && !it.equals(myAddress, ignoreCase = true) }
            openCompose(
                to = recipients.joinToString(", "),
                subject = withPrefix(emailSubject, "Re:"),
                body = quoteForReply(emailSender, emailPreview),
            )
        }
        actionForward.setOnClickListener {
            openCompose(
                to = "",
                subject = withPrefix(emailSubject, "Fwd:"),
                body = "\n\n---------- Forwarded message ----------\n" +
                    "From: $emailSender\nSubject: $emailSubject\n\n$emailPreview",
            )
        }

        webView.settings.apply {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            defaultTextEncodingName = "utf-8"
        }

        ioExecutor.execute {
            val outcome = mailRepository.fetchBody(emailId, emailFolder)
            val content = (outcome as? MailOutcome.Success)?.value
            val bodyToRender = content?.html?.takeIf { it.isNotBlank() } ?: TextUtils.htmlEncode(emailPreview)
            val palette = getStoredThemePalette(this)

            val htmlContent = """
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <style>
                        body {
                            font-family: monospace;
                            font-size: 16px;
                            line-height: 1.5;
                            color: ${palette.inkStrong};
                            background-color: ${palette.bg};
                            margin: 0;
                            padding: 8px;
                            word-break: break-word;
                        }
                        a { color: ${palette.accent}; }
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
                if (content != null) {
                    toRecipients = content.toAddresses
                    ccRecipients = content.ccAddresses
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val currentTheme = getStoredThemeName(this)
        if (currentTheme != lastAppliedThemeName) {
            recreate()
            return
        }

        applyThemeToActivity(this)
        applyDetailChrome()
    }

    private fun applyDetailChrome() {
        val palette = getStoredThemePalette(this)
        divider.setBackgroundColor(Color.parseColor(palette.line))
        webView.setBackgroundColor(Color.parseColor(palette.bg))
        actionButtons.forEach { applyIconButtonTheme(this, it) }
    }

    private fun runMailActionAndFinish(actionLabel: String, emailId: String, action: (MailRepository) -> Unit) {
        Toast.makeText(this, actionLabel, Toast.LENGTH_SHORT).show()
        MailBackgroundExecutor.submit { action(mailRepository) }
        // Tell InboxActivity which row to drop immediately, mirroring its own swipe-to-archive/
        // delete optimistic removal. Without this, returning here re-triggers InboxActivity's
        // onStart refresh, which races the still-in-flight mutation above and can redraw the row
        // we just "removed" — the mutation still lands, it just looks like the button did nothing.
        setResult(RESULT_OK, Intent().putExtra(EXTRA_REMOVED_EMAIL_ID, emailId))
        finish()
    }

    private fun openCompose(to: String, subject: String, body: String) {
        val intent = Intent(this, ComposeActivity::class.java)
        intent.putExtra(ComposeActivity.EXTRA_TO, to)
        intent.putExtra(ComposeActivity.EXTRA_SUBJECT, subject)
        intent.putExtra(ComposeActivity.EXTRA_BODY, body)
        startActivity(intent)
    }

    private fun quoteForReply(sender: String, preview: String): String {
        return "\n\n$sender wrote:\n$preview"
    }

    private fun withPrefix(subject: String, prefix: String): String {
        return if (subject.trim().startsWith(prefix, ignoreCase = true)) subject else "$prefix $subject"
    }

    private fun extractAddress(raw: String): String {
        return Regex("<([^>]+)>").find(raw)?.groupValues?.get(1) ?: raw
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    companion object {
        const val EXTRA_REMOVED_EMAIL_ID = "removed_email_id"
    }
}
