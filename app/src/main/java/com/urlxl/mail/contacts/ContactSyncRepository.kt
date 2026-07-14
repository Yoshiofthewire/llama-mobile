package com.urlxl.mail.contacts

import com.urlxl.mail.data.AppDatabase
import com.urlxl.mail.data.ContactEntity
import com.urlxl.mail.data.PendingContactChangeEntity
import com.urlxl.mail.push.PairingData
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

sealed class ContactSyncOutcome {
    object Success : ContactSyncOutcome()
    object NotPaired : ContactSyncOutcome()
    object Unauthorized : ContactSyncOutcome()
    data class ServiceUnavailable(val message: String) : ContactSyncOutcome()
    data class Retry(val message: String) : ContactSyncOutcome()
}

/** Mirrors [ContactSyncOutcome]'s shape; kept as a parallel type (rather than a variant of
 * [ContactSyncOutcome]) because `Success` here needs to carry the dedupe report. */
sealed class ContactDedupeOutcome {
    data class Success(val report: ContactDedupeReportDto) : ContactDedupeOutcome()
    object NotPaired : ContactDedupeOutcome()
    object Unauthorized : ContactDedupeOutcome()
    data class ServiceUnavailable(val message: String) : ContactDedupeOutcome()
    data class Retry(val message: String) : ContactDedupeOutcome()
}

/**
 * Orchestrates contact sync: decides pull-vs-push based on the offline change queue, applies the
 * server delta (upsert changed, remove deleted, reconcile locally-created uids), and handles
 * tooOld by discarding the cursor and wiping the local cache for a full re-pull.
 */
class ContactSyncRepository(
    private val db: AppDatabase,
    private val client: ContactSyncClient,
    private val cursorStore: ContactCursorStore,
    private val pairingProvider: suspend () -> PairingData?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeContacts(): Flow<List<ContactEntity>> = db.contactDao().observeAll()

    suspend fun sync(): ContactSyncOutcome {
        val pairing = pairingProvider() ?: return ContactSyncOutcome.NotPaired
        val pendingChanges = db.pendingContactChangeDao().getAllPending()
        val cursor = cursorStore.cursor(pairing.subscriberId)

        val result = if (pendingChanges.isEmpty()) {
            client.pull(pairing.serverUrl, pairing.subscriberId, pairing.subscriberHash, cursor)
        } else {
            client.push(
                serverUrl = pairing.serverUrl,
                subscriberId = pairing.subscriberId,
                subscriberHash = pairing.subscriberHash,
                baseCursor = cursor,
                changes = pendingChanges.map(::toWireDto),
            )
        }

        return when (result) {
            is ContactSyncResult.Success -> {
                applyDelta(pairing.subscriberId, result.response, pendingChanges)
                ContactSyncOutcome.Success
            }
            is ContactSyncResult.Unauthorized -> ContactSyncOutcome.Unauthorized
            is ContactSyncResult.ServiceUnavailable -> ContactSyncOutcome.ServiceUnavailable(result.message)
            is ContactSyncResult.BadRequest -> ContactSyncOutcome.Retry(result.message)
            is ContactSyncResult.Retryable -> ContactSyncOutcome.Retry(result.message)
        }
    }

    /**
     * Calls the server-side dedupe endpoint. Deliberately does NOT call [sync] itself — mirrors
     * [sync]'s single-purpose shape; the caller is responsible for triggering a follow-up sync so
     * the merge's tombstones/survivor land locally.
     */
    suspend fun dedupe(): ContactDedupeOutcome = resolveDedupeOutcome(pairingProvider) { pairing ->
        client.dedupe(pairing.serverUrl, pairing.subscriberId, pairing.subscriberHash)
    }

    /** Creates locally under a temp uid and enqueues the create; reconciled to a server uid on sync. */
    suspend fun queueCreate(contact: ContactDto): String {
        val localUid = UUID.randomUUID().toString()
        val localCopy = contact.copy(uid = localUid)
        db.contactDao().upsertAll(listOf(localCopy.toEntity()))
        db.pendingContactChangeDao().enqueue(
            PendingContactChangeEntity(
                localUid = localUid,
                rev = 0,
                changeType = CHANGE_CREATE,
                payloadJson = json.encodeToString(contact.copy(uid = "")),
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
        return localUid
    }

    suspend fun queueUpdate(contact: ContactDto) {
        db.contactDao().upsertAll(listOf(contact.toEntity()))
        db.pendingContactChangeDao().enqueue(
            PendingContactChangeEntity(
                localUid = contact.uid,
                rev = contact.rev,
                changeType = CHANGE_UPDATE,
                payloadJson = json.encodeToString(contact),
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun queueDelete(uid: String, rev: Long) {
        db.contactDao().deleteByUids(listOf(uid))
        db.pendingContactChangeDao().enqueue(
            PendingContactChangeEntity(
                localUid = uid,
                rev = rev,
                changeType = CHANGE_DELETE,
                payloadJson = "",
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun toWireDto(change: PendingContactChangeEntity): ContactDto = when (change.changeType) {
        CHANGE_DELETE -> ContactDto(uid = change.localUid, rev = change.rev, deleted = true)
        CHANGE_CREATE -> decodePayload(change).copy(uid = "")
        else -> decodePayload(change).copy(uid = change.localUid, rev = change.rev)
    }

    private fun decodePayload(change: PendingContactChangeEntity): ContactDto =
        runCatching { json.decodeFromString<ContactDto>(change.payloadJson) }.getOrDefault(ContactDto())

    private suspend fun applyDelta(
        subscriberId: String,
        response: ContactSyncPullResponseDto,
        flushedChanges: List<PendingContactChangeEntity>,
    ) {
        if (response.tooOld) {
            cursorStore.resetCursor(subscriberId)
            db.contactDao().clearAll()
            return
        }

        val pendingCreates = flushedChanges.filter { it.changeType == CHANGE_CREATE }
        val reconciled = ContactSyncReconciliation.reconcile(pendingCreates, response.changed)
        if (reconciled.isNotEmpty()) {
            // Drop the temp-uid rows; the upsert below inserts the real, server-assigned rows.
            db.contactDao().deleteByUids(reconciled.keys.toList())
        }

        db.contactDao().upsertAll(response.changed.map { it.toEntity() })
        db.contactDao().deleteByUids(response.deleted.map { it.uid })

        if (flushedChanges.isNotEmpty()) {
            db.pendingContactChangeDao().clearFlushed(flushedChanges.map { it.id })
        }
        cursorStore.advanceCursor(subscriberId, response.cursor)
    }

    companion object {
        const val CHANGE_CREATE = "create"
        const val CHANGE_UPDATE = "update"
        const val CHANGE_DELETE = "delete"
    }
}

/**
 * Decides [ContactSyncRepository.dedupe]'s outcome: [ContactDedupeOutcome.NotPaired] if
 * [pairingProvider] yields no pairing, otherwise delegates to [dedupeCall] and maps its result via
 * [contactDedupeOutcomeOf]. Kept as a standalone function, independent of
 * [ContactSyncRepository]'s `AppDatabase`/`ContactCursorStore` dependencies, so it's testable in a
 * plain JVM unit test — mirrors [com.urlxl.mail.mail.reconcileFetchResult]'s extraction for the
 * same reason.
 */
internal suspend fun resolveDedupeOutcome(
    pairingProvider: suspend () -> PairingData?,
    dedupeCall: suspend (PairingData) -> ContactDedupeResult,
): ContactDedupeOutcome {
    val pairing = pairingProvider() ?: return ContactDedupeOutcome.NotPaired
    return contactDedupeOutcomeOf(dedupeCall(pairing))
}

/** Pure mapping from [ContactDedupeResult] to [ContactDedupeOutcome]; `BadRequest` folds into
 * [ContactDedupeOutcome.Retry], matching how [ContactSyncRepository.sync] folds
 * `ContactSyncResult.BadRequest` into `ContactSyncOutcome.Retry`. */
internal fun contactDedupeOutcomeOf(result: ContactDedupeResult): ContactDedupeOutcome = when (result) {
    is ContactDedupeResult.Success -> ContactDedupeOutcome.Success(result.report)
    is ContactDedupeResult.Unauthorized -> ContactDedupeOutcome.Unauthorized
    is ContactDedupeResult.ServiceUnavailable -> ContactDedupeOutcome.ServiceUnavailable(result.message)
    is ContactDedupeResult.BadRequest -> ContactDedupeOutcome.Retry(result.message)
    is ContactDedupeResult.Retryable -> ContactDedupeOutcome.Retry(result.message)
}
