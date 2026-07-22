package com.urlxl.mail.security

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val AUTHORITY_SUFFIX = ".ephemeralattachments"

// How long a registered-but-never-opened attachment's bytes may linger in the process heap — see
// [EphemeralAttachmentBytes.purgeExpired]. Short: this is only meant to bridge "user tapped View"
// to "the OS finished picking/launching a viewer app", not a general cache lifetime.
private const val ATTACHMENT_TTL_MILLIS = 60_000L

internal data class PendingAttachment(
    val bytes: ByteArray,
    val mimeType: String,
    val registeredAtMillis: Long = System.currentTimeMillis(),
)

/**
 * In-memory holder for attachment bytes awaiting a single ephemeral read, keyed by a one-time
 * token. Nothing here is ever written to disk — see [EphemeralAttachmentProvider], the
 * `ContentProvider` that actually serves these bytes to a viewer app.
 */
object EphemeralAttachmentBytes {
    private val pending = ConcurrentHashMap<String, PendingAttachment>()
    private var authority: String = ""

    internal fun configure(authority: String) {
        this.authority = authority
    }

    fun register(bytes: ByteArray, mimeType: String): Uri {
        purgeExpired()
        val token = UUID.randomUUID().toString()
        pending[token] = PendingAttachment(bytes, mimeType)
        return Uri.parse("content://$authority/$token")
    }

    internal fun take(token: String): PendingAttachment? = pending.remove(token)

    internal fun peekMimeType(token: String): String? = pending[token]?.mimeType

    /**
     * Bounds how long bytes for a never-opened attachment can sit in memory — if the user backs
     * out before a viewer app actually opens the `content://` URI (or no app handles the MIME
     * type), nothing else ever calls [take] to remove the entry. Checked lazily on every
     * [register] call rather than via a scheduled background sweep — deliberately simple: the
     * `pending` map is expected to hold at most a handful of entries at a time (one per
     * in-flight "View" tap), so an O(n) scan here costs nothing and needs no extra
     * threads/WorkManager.
     */
    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - ATTACHMENT_TTL_MILLIS
        pending.entries.removeIf { it.value.registeredAtMillis < cutoff }
    }
}

/**
 * Serves attachment bytes registered via [EphemeralAttachmentBytes.register] through a pipe,
 * never a file — see "Attachments" under Hostile Location Protection in the 2026-07-22
 * security-hardening spec. Each token is single-use: [EphemeralAttachmentBytes.take] removes it
 * from memory the moment this provider starts serving it.
 */
class EphemeralAttachmentProvider : ContentProvider() {
    override fun attachInfo(context: android.content.Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        EphemeralAttachmentBytes.configure(info.authority)
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = EphemeralAttachmentBytes.peekMimeType(tokenFrom(uri))

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val attachment = EphemeralAttachmentBytes.take(tokenFrom(uri))
            ?: throw IOException("Attachment already consumed or unknown: $uri")
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { it.write(attachment.bytes) }
            } catch (e: Exception) {
                // Expected/benign, not a bug: the viewer app can close its read side before this
                // finishes writing (user backs out of the app chooser, the viewer only reads a
                // MIME-sniffing prefix, etc.), which surfaces here as a broken-pipe IOException.
                // A bare Thread has no default handler for an uncaught exception other than
                // crashing the whole process, so this must be caught, not just left to propagate.
                android.util.Log.w(
                    "EphemeralAttachmentProvider",
                    "Attachment write aborted (reader likely closed early)",
                    e,
                )
            }
        }.start()
        return readSide
    }

    private fun tokenFrom(uri: Uri): String = uri.lastPathSegment.orEmpty()

    // Not a real data table — attachments are single-use byte streams, not queryable rows.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
