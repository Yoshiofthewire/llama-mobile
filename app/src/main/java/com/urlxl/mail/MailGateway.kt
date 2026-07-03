package com.urlxl.mail

import android.content.Context
import android.util.Log
import jakarta.mail.Authenticator
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Date
import java.util.Properties

data class MailAccountConfig(
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val username: String,
    val password: String,
    val folderName: String,
)

class MailGateway(private val config: MailAccountConfig) {

    fun isConfigured(): Boolean {
        return config.imapHost.isNotBlank() && config.smtpHost.isNotBlank() && config.username.isNotBlank()
    }

    fun moveEmail(email: Email, targetFolder: String, sourceFolderName: String = config.folderName) {
        if (!isConfigured()) return

        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.imapHost)
            put("mail.imaps.port", config.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.timeout", "10000")
        }

        runCatching {
            val session = Session.getInstance(props, authenticator())
            val store = session.getStore("imaps")
            store.connect(config.imapHost, config.imapPort, config.username, config.password)

            val sourceFolder = store.getFolder(sourceFolderName)
            sourceFolder.open(Folder.READ_WRITE)
            val targetFld = store.getFolder(targetFolder)
            targetFld.open(Folder.READ_WRITE)

            for (msg in sourceFolder.messages) {
                if (msg.getHeader("Message-ID")?.firstOrNull() == email.id) {
                    sourceFolder.copyMessages(arrayOf(msg), targetFld)
                    msg.setFlag(Flags.Flag.DELETED, true)
                    sourceFolder.expunge()
                    break
                }
            }

            sourceFolder.close(true)
            targetFld.close(true)
            store.close()
        }.onFailure {
            Log.w(TAG, "Failed to move email to $targetFolder", it)
        }
    }

    fun deleteEmail(email: Email, sourceFolderName: String = config.folderName) {
        if (!isConfigured()) return

        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.imapHost)
            put("mail.imaps.port", config.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.timeout", "10000")
        }

        runCatching {
            val session = Session.getInstance(props, authenticator())
            val store = session.getStore("imaps")
            store.connect(config.imapHost, config.imapPort, config.username, config.password)

            val folder = store.getFolder(sourceFolderName)
            folder.open(Folder.READ_WRITE)

            for (msg in folder.messages) {
                if (msg.getHeader("Message-ID")?.firstOrNull() == email.id) {
                    msg.setFlag(Flags.Flag.DELETED, true)
                    break
                }
            }

            folder.expunge()
            folder.close(true)
            store.close()
        }.onFailure {
            Log.w(TAG, "Failed to delete email", it)
        }
    }

    fun fetchInboxEmails(limit: Int = 100): List<Email> = fetchEmails(config.folderName, limit)

    fun fetchEmails(folderName: String, limit: Int = 100): List<Email> {
        if (!isConfigured()) {
            return emptyList()
        }

        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.imapHost)
            put("mail.imaps.port", config.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.timeout", "10000")
            put("mail.imaps.connectiontimeout", "10000")
        }

        var store: Store? = null
        var folder: Folder? = null

        return try {
            val session = Session.getInstance(props, authenticator())
            store = session.getStore("imaps")
            store.connect(config.imapHost, config.imapPort, config.username, config.password)
            folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)

            val messages = folder.messages
            if (messages.isEmpty()) {
                return emptyList()
            }

            val start = (messages.size - limit).coerceAtLeast(0)
            messages.copyOfRange(start, messages.size)
                .toList()
                .asReversed()
                .map(::toEmail)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to fetch emails for folder=$folderName", ex)
            emptyList()
        } finally {
            runCatching {
                if (folder?.isOpen == true) {
                    folder.close(false)
                }
            }
            runCatching {
                store?.close()
            }
        }
    }

    fun sendEmail(to: String, subject: String, body: String) {
        if (!isConfigured()) {
            return
        }

        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        }

        runCatching {
            val session = Session.getInstance(props, authenticator())
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.username))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                sentDate = Date()
                setSubject(subject)
                setText(body)
            }
            Transport.send(message)
        }.onFailure {
            Log.w(TAG, "Failed to send email", it)
        }
    }

    fun fetchEmailBodyHtml(messageId: String, folderName: String): String? {
        if (!isConfigured() || messageId.isBlank()) {
            return null
        }

        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.imapHost)
            put("mail.imaps.port", config.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.timeout", "10000")
            put("mail.imaps.connectiontimeout", "10000")
        }

        var store: Store? = null
        var folder: Folder? = null

        return try {
            val session = Session.getInstance(props, authenticator())
            store = session.getStore("imaps")
            store.connect(config.imapHost, config.imapPort, config.username, config.password)
            folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)

            folder.messages.firstNotNullOfOrNull { msg ->
                val id = msg.getHeader("Message-ID")?.firstOrNull()?.ifBlank { null } ?: "${msg.messageNumber}"
                if (id == messageId) {
                    extractHtmlBody(msg)
                } else {
                    null
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to fetch full email body", ex)
            null
        } finally {
            runCatching {
                if (folder?.isOpen == true) {
                    folder.close(false)
                }
            }
            runCatching {
                store?.close()
            }
        }
    }

    private fun authenticator(): Authenticator {
        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(config.username, config.password)
            }
        }
    }

    private fun toEmail(message: Message): Email {
        val messageId = message.getHeader("Message-ID")?.firstOrNull()?.ifBlank { null }
            ?: "${message.messageNumber}"

        val subject = message.subject?.ifBlank { "(No subject)" } ?: "(No subject)"
        val sender = message.from?.firstOrNull()?.toString()?.ifBlank { "Unknown sender" } ?: "Unknown sender"
        val preview = extractPreview(message)
        val keywords = message.flags.userFlags.toSet()

        return Email(
            id = messageId,
            subject = subject,
            sender = sender,
            preview = preview,
            keywords = keywords,
        )
    }

    private fun extractPreview(message: Message): String {
        return runCatching {
            when (val content = message.content) {
                is String -> content
                is Multipart -> extractFromMultipart(content)
                else -> ""
            }
        }.getOrDefault("")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "(No preview)" }
            .take(140)
    }

    private fun extractFromMultipart(multipart: Multipart): String {
        for (index in 0 until multipart.count) {
            val bodyPart = multipart.getBodyPart(index)
            val content = bodyPart.content
            if (content is String) {
                return content
            }
            if (content is Multipart) {
                val nested = extractFromMultipart(content)
                if (nested.isNotBlank()) {
                    return nested
                }
            }
        }
        return ""
    }

    private fun extractHtmlBody(message: Message): String {
        return runCatching {
            when (val content = message.content) {
                is String -> content
                is Multipart -> extractHtmlFromMultipart(content)
                else -> ""
            }
        }.getOrDefault("")
    }

    private fun extractHtmlFromMultipart(multipart: Multipart): String {
        var plainTextFallback = ""
        for (index in 0 until multipart.count) {
            val bodyPart = multipart.getBodyPart(index)
            val contentType = bodyPart.contentType?.lowercase().orEmpty()
            val content = bodyPart.content

            if (content is String && contentType.contains("text/html")) {
                return content
            }

            if (content is String && contentType.contains("text/plain") && plainTextFallback.isBlank()) {
                plainTextFallback = content
            }

            if (content is Multipart) {
                val nested = extractHtmlFromMultipart(content)
                if (nested.isNotBlank()) {
                    return nested
                }
            }
        }
        return plainTextFallback
    }

    companion object {
        private const val TAG = "MailGateway"

        fun fromSettings(context: Context): MailGateway {
            val settings = MailSettings(context)
            return MailGateway(settings.getConfig())
        }

        fun fromBuildConfig(): MailGateway {
            return MailGateway(
                MailAccountConfig(
                    imapHost = BuildConfig.MAIL_IMAP_HOST,
                    imapPort = BuildConfig.MAIL_IMAP_PORT,
                    smtpHost = BuildConfig.MAIL_SMTP_HOST,
                    smtpPort = BuildConfig.MAIL_SMTP_PORT,
                    username = BuildConfig.MAIL_USERNAME,
                    password = BuildConfig.MAIL_PASSWORD,
                    folderName = BuildConfig.MAIL_IMAP_FOLDER,
                ),
            )
        }
    }
}
