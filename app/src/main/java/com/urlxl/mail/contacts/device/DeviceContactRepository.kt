package com.urlxl.mail.contacts.device

import android.content.Context
import android.provider.ContactsContract
import com.urlxl.mail.contacts.ContactDto
import com.urlxl.mail.contacts.ContactFieldDto
import com.urlxl.mail.contacts.ContactSyncRepository
import com.urlxl.mail.contacts.device.DeviceContactMappers.toDto
import com.urlxl.mail.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

class DeviceContactRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val syncRepository: ContactSyncRepository,
) {
    private val contentResolver = context.contentResolver

    suspend fun syncAll() {
        pullDeviceChangesForOwnAccount()
        importNewDeviceContacts()
        pushRoomChangesToDevice()
    }

    private suspend fun pullDeviceChangesForOwnAccount() = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.CONTACT_ID,
            ContactsContract.RawContacts.DELETED,
        )

        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?"
        val selectionArgs = arrayOf(DeviceContactAccount.ACCOUNT_TYPE)

        val dirtyRawContacts = mutableListOf<Long>()

        contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val rawContactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID))
                val deleted = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.DELETED)) != 0

                val link = db.deviceContactLinkDao().getByRawContactId(rawContactId)

                if (deleted && link != null) {
                    syncRepository.queueDelete(link.uid, 0)
                    db.deviceContactLinkDao().deleteByUid(link.uid)
                } else if (!deleted && link != null) {
                    dirtyRawContacts.add(rawContactId)
                }
            }
        }

        for (rawContactId in dirtyRawContacts) {
            val snapshot = readRawContactSnapshot(rawContactId) ?: continue
            val link = db.deviceContactLinkDao().getByRawContactId(rawContactId) ?: continue

            val roomEntity = db.contactDao().getByUid(link.uid) ?: continue
            val roomDto = roomEntity.toDto()

            val roomUpdatedAtEpochMs = roomDto.updatedAt?.let { DeviceContactConflictResolver.parseIso(it) }
            val deviceUpdatedAtEpochMs = snapshot.lastUpdatedEpochMs

            val mergedFn = DeviceContactFieldMerge.mergeStringField(
                roomValue = roomDto.fn,
                deviceValue = snapshot.fn,
                roomUpdatedAtEpochMs = roomUpdatedAtEpochMs,
                deviceUpdatedAtEpochMs = deviceUpdatedAtEpochMs,
            )

            val mergedOrg = DeviceContactFieldMerge.mergeStringField(
                roomValue = roomDto.org,
                deviceValue = snapshot.org,
                roomUpdatedAtEpochMs = roomUpdatedAtEpochMs,
                deviceUpdatedAtEpochMs = deviceUpdatedAtEpochMs,
            )

            val mergedNotes = DeviceContactFieldMerge.mergeStringField(
                roomValue = roomDto.notes,
                deviceValue = snapshot.notes,
                roomUpdatedAtEpochMs = roomUpdatedAtEpochMs,
                deviceUpdatedAtEpochMs = deviceUpdatedAtEpochMs,
            )

            val mergedBirthday = DeviceContactFieldMerge.mergeStringField(
                roomValue = roomDto.birthday,
                deviceValue = snapshot.birthday,
                roomUpdatedAtEpochMs = roomUpdatedAtEpochMs,
                deviceUpdatedAtEpochMs = deviceUpdatedAtEpochMs,
            )

            val mergedEmails = DeviceContactFieldMerge.mergeEmailList(
                roomEmails = roomDto.emails,
                deviceEmails = snapshot.emails,
                roomUpdatedAtEpochMs = roomUpdatedAtEpochMs,
                deviceUpdatedAtEpochMs = deviceUpdatedAtEpochMs,
            )

            val mergedPhones = DeviceContactFieldMerge.mergePhoneList(
                roomPhones = roomDto.phones,
                devicePhones = snapshot.phones,
                roomUpdatedAtEpochMs = roomUpdatedAtEpochMs,
                deviceUpdatedAtEpochMs = deviceUpdatedAtEpochMs,
            )

            val mergedAddresses = DeviceContactFieldMerge.mergeAddressList(
                roomAddresses = roomDto.addresses,
                deviceAddresses = snapshot.addresses,
                roomUpdatedAtEpochMs = roomUpdatedAtEpochMs,
                deviceUpdatedAtEpochMs = deviceUpdatedAtEpochMs,
            )

            val changed = mergedFn != roomDto.fn || mergedOrg != roomDto.org ||
                mergedNotes != roomDto.notes || mergedBirthday != roomDto.birthday ||
                mergedEmails != roomDto.emails || mergedPhones != roomDto.phones ||
                mergedAddresses != roomDto.addresses

            if (changed) {
                val mergedDto = roomDto.copy(
                    fn = mergedFn,
                    org = mergedOrg,
                    notes = mergedNotes,
                    birthday = mergedBirthday,
                    emails = mergedEmails,
                    phones = mergedPhones,
                    addresses = mergedAddresses,
                )
                syncRepository.queueUpdate(mergedDto)
            }

            clearDirtyFlag(rawContactId)
            db.deviceContactLinkDao().upsert(
                link.copy(deviceUpdatedAtEpochMs = System.currentTimeMillis()),
            )
        }
    }

    private suspend fun clearDirtyFlag(rawContactId: Long) = withContext(Dispatchers.IO) {
        val ops = arrayListOf(
            android.content.ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts._ID} = ?", arrayOf(rawContactId.toString()))
                .withValue(ContactsContract.RawContacts.DIRTY, 0)
                .withValue("caller_is_syncadapter", true)
                .build(),
        )
        runCatching {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }
    }

    private suspend fun importNewDeviceContacts() = withContext(Dispatchers.IO) {
        val settings = DeviceContactSyncSettings(context)
        val watermarkMs = settings.lastForeignScanAtEpochMs()

        val projection = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.CONTACT_ID,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME,
        )

        val selection =
            "(${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} != ?)"
        val selectionArgs = arrayOf(DeviceContactAccount.ACCOUNT_TYPE)

        val rawContactCandidates = mutableListOf<Long>()
        contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val rawContactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID))
                val contactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID))

                val lastUpdated = queryContactLastUpdated(contactId)
                if (lastUpdated > watermarkMs) {
                    rawContactCandidates.add(rawContactId)
                }
            }
        }

        if (rawContactCandidates.isEmpty()) {
            return@withContext
        }

        val existing = db.contactDao().observeAll().first().map { it.toDto() }

        for (rawContactId in rawContactCandidates) {
            val candidate = readRawContactSnapshot(rawContactId)
            if (candidate == null) continue

            val matchedUid = DeviceContactMatcher.findMatch(
                candidate.emails.map { it.value },
                candidate.phones.map { it.value },
                existing,
            )

            if (matchedUid != null) {
                db.deviceContactLinkDao().upsert(
                    com.urlxl.mail.data.DeviceContactLinkEntity(
                        uid = matchedUid,
                        rawContactId = rawContactId,
                        deviceUpdatedAtEpochMs = candidate.lastUpdatedEpochMs,
                    ),
                )
            } else {
                val newDto = candidate.toContactDto(UUID.randomUUID().toString(), 0)
                syncRepository.queueCreate(newDto)
            }
        }

        settings.setLastForeignScanAtEpochMs(System.currentTimeMillis())
    }

    private suspend fun pushRoomChangesToDevice() = withContext(Dispatchers.IO) {
        val currentRoomContacts = db.contactDao().observeAll().first()

        for (entity in currentRoomContacts) {
            val dto = entity.toDto()
            val existingLink = db.deviceContactLinkDao().getByUid(dto.uid)

            if (existingLink == null) {
                createRawContactForDto(dto)
            } else {
                updateRawContactForDto(dto, existingLink)
            }
        }
    }

    private suspend fun createRawContactForDto(dto: ContactDto) = withContext(Dispatchers.IO) {
        val ops = arrayListOf<android.content.ContentProviderOperation>()

        val rawContactUri = ops.add(
            android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, DeviceContactAccount.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, DeviceContactAccount.ACCOUNT_NAME)
                .withValue("caller_is_syncadapter", true)
                .build(),
        )

        ops.add(
            android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactUri)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, dto.fn)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, dto.givenName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, dto.familyName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, dto.middleName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, dto.prefix)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, dto.suffix)
                .withValue("caller_is_syncadapter", true)
                .build(),
        )

        if (!dto.org.isNullOrBlank()) {
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactUri)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, dto.org)
                    .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, dto.title)
                    .withValue("caller_is_syncadapter", true)
                    .build(),
            )
        }

        if (!dto.notes.isNullOrBlank()) {
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactUri)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, dto.notes)
                    .withValue("caller_is_syncadapter", true)
                    .build(),
            )
        }

        if (!dto.birthday.isNullOrBlank()) {
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactUri)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, dto.birthday)
                    .withValue(ContactsContract.CommonDataKinds.Event.TYPE, 3)
                    .withValue("caller_is_syncadapter", true)
                    .build(),
            )
        }

        for (email in dto.emails) {
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactUri)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.value)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, email.label ?: "")
                    .withValue("caller_is_syncadapter", true)
                    .build(),
            )
        }

        for (phone in dto.phones) {
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactUri)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.value)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phone.label ?: "")
                    .withValue("caller_is_syncadapter", true)
                    .build(),
            )
        }

        for (address in dto.addresses) {
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactUri)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, address.street)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, address.city)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, address.region)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, address.postalCode)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, address.country)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, address.label ?: "")
                    .withValue("caller_is_syncadapter", true)
                    .build(),
            )
        }

        val results = runCatching {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }.getOrNull() ?: return@withContext

        if (results.isNotEmpty() && results[0] != null) {
            val rawContactUri = results[0]!!.uri
            val rawContactId = rawContactUri.lastPathSegment?.toLongOrNull() ?: return@withContext
            db.deviceContactLinkDao().upsert(
                com.urlxl.mail.data.DeviceContactLinkEntity(
                    uid = dto.uid,
                    rawContactId = rawContactId,
                    deviceUpdatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun updateRawContactForDto(
        dto: ContactDto,
        link: com.urlxl.mail.data.DeviceContactLinkEntity,
    ) = withContext(Dispatchers.IO) {
        val currentSnapshot = readRawContactSnapshot(link.rawContactId) ?: return@withContext

        val mergedNameDisplay = DeviceContactFieldMerge.mergeStringField(
            roomValue = dto.fn,
            deviceValue = currentSnapshot.fn,
            roomUpdatedAtEpochMs = dto.updatedAt?.let { DeviceContactConflictResolver.parseIso(it) },
            deviceUpdatedAtEpochMs = link.deviceUpdatedAtEpochMs,
        )

        val mergedOrg = DeviceContactFieldMerge.mergeStringField(
            roomValue = dto.org,
            deviceValue = currentSnapshot.org,
            roomUpdatedAtEpochMs = dto.updatedAt?.let { DeviceContactConflictResolver.parseIso(it) },
            deviceUpdatedAtEpochMs = link.deviceUpdatedAtEpochMs,
        )

        val mergedNotes = DeviceContactFieldMerge.mergeStringField(
            roomValue = dto.notes,
            deviceValue = currentSnapshot.notes,
            roomUpdatedAtEpochMs = dto.updatedAt?.let { DeviceContactConflictResolver.parseIso(it) },
            deviceUpdatedAtEpochMs = link.deviceUpdatedAtEpochMs,
        )

        val mergedBirthday = DeviceContactFieldMerge.mergeStringField(
            roomValue = dto.birthday,
            deviceValue = currentSnapshot.birthday,
            roomUpdatedAtEpochMs = dto.updatedAt?.let { DeviceContactConflictResolver.parseIso(it) },
            deviceUpdatedAtEpochMs = link.deviceUpdatedAtEpochMs,
        )

        val mergedEmails = DeviceContactFieldMerge.mergeEmailList(
            roomEmails = dto.emails,
            deviceEmails = currentSnapshot.emails,
            roomUpdatedAtEpochMs = dto.updatedAt?.let { DeviceContactConflictResolver.parseIso(it) },
            deviceUpdatedAtEpochMs = link.deviceUpdatedAtEpochMs,
        )

        val mergedPhones = DeviceContactFieldMerge.mergePhoneList(
            roomPhones = dto.phones,
            devicePhones = currentSnapshot.phones,
            roomUpdatedAtEpochMs = dto.updatedAt?.let { DeviceContactConflictResolver.parseIso(it) },
            deviceUpdatedAtEpochMs = link.deviceUpdatedAtEpochMs,
        )

        val mergedAddresses = DeviceContactFieldMerge.mergeAddressList(
            roomAddresses = dto.addresses,
            deviceAddresses = currentSnapshot.addresses,
            roomUpdatedAtEpochMs = dto.updatedAt?.let { DeviceContactConflictResolver.parseIso(it) },
            deviceUpdatedAtEpochMs = link.deviceUpdatedAtEpochMs,
        )

        val ops = arrayListOf<android.content.ContentProviderOperation>()

        if (mergedNameDisplay != currentSnapshot.fn) {
            ops.add(
                android.content.ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(
                            link.rawContactId.toString(),
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                        ),
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, mergedNameDisplay)
                    .withValue("caller_is_syncadapter", true)
                    .build(),
            )
        }

        if (ops.isNotEmpty()) {
            runCatching {
                contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            }
        }

        db.deviceContactLinkDao().upsert(
            link.copy(deviceUpdatedAtEpochMs = System.currentTimeMillis()),
        )
    }

    suspend fun deleteDeviceRawContact(uid: String) = withContext(Dispatchers.IO) {
        val link = db.deviceContactLinkDao().getByUid(uid) ?: return@withContext

        val deleteOps = arrayListOf(
            android.content.ContentProviderOperation.newDelete(
                ContactsContract.RawContacts.CONTENT_URI,
            ).withSelection(
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(link.rawContactId.toString()),
            ).withValue("caller_is_syncadapter", true).build(),
        )

        runCatching {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, deleteOps)
        }

        db.deviceContactLinkDao().deleteByUid(uid)
    }

    private suspend fun queryContactLastUpdated(contactId: Long): Long = withContext(Dispatchers.IO) {
        val projection = arrayOf(ContactsContract.Contacts.CONTACTS_LAST_UPDATED_TIMESTAMP)
        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.CONTACTS_LAST_UPDATED_TIMESTAMP)
                return@withContext cursor.getLong(idx)
            }
        }
        return@withContext 0L
    }

    private suspend fun readRawContactSnapshot(rawContactId: Long): DeviceRawContactSnapshot? =
        withContext(Dispatchers.IO) {
            val rawContactProjection = arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME,
            )

            val rawContactData = contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                rawContactProjection,
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Triple(
                        cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)),
                    )
                } else {
                    null
                }
            } ?: return@withContext null

            val (actualRawContactId, contactId, accountType) = rawContactData
            val lastUpdated = queryContactLastUpdated(contactId)

            val dataProjection = arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA5,
                ContactsContract.Data.DATA6,
                ContactsContract.Data.DATA7,
            )

            var fn = ""
            var org: String? = null
            var notes: String? = null
            var birthday: String? = null
            val emails = mutableListOf<ContactFieldDto>()
            val phones = mutableListOf<ContactFieldDto>()
            val addresses = mutableListOf<com.urlxl.mail.contacts.ContactAddressDto>()

            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                dataProjection,
                "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                arrayOf(rawContactId.toString()),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE))
                    val data1 = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)) ?: ""
                    val data2 = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2))
                    val data3 = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3))
                    val data4 = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA4))
                    val data5 = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA5))
                    val data6 = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA6))
                    val data7 = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA7))

                    when (mimeType) {
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                            val given = data2?.takeIf { it.isNotBlank() }
                            val family = data3?.takeIf { it.isNotBlank() }
                            val middle = data4?.takeIf { it.isNotBlank() }
                            val prefix = data5?.takeIf { it.isNotBlank() }
                            val suffix = data6?.takeIf { it.isNotBlank() }
                            fn = listOfNotNull(prefix, given, middle, family, suffix).joinToString(" ")
                            if (fn.isBlank()) fn = data1
                        }

                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                            if (data1.isNotBlank()) {
                                val label = data2?.takeIf { it.isNotBlank() }
                                emails.add(ContactFieldDto(label = label, value = data1))
                            }
                        }

                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            if (data1.isNotBlank()) {
                                val label = data2?.takeIf { it.isNotBlank() }
                                phones.add(ContactFieldDto(label = label, value = data1))
                            }
                        }

                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                            org = data1.takeIf { it.isNotBlank() }
                        }

                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                            notes = data1.takeIf { it.isNotBlank() }
                        }

                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                            if (data2 == "3") {
                                birthday = data1
                            }
                        }

                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                            val street = data1?.takeIf { it.isNotBlank() }
                            val city = data3?.takeIf { it.isNotBlank() }
                            val region = data4?.takeIf { it.isNotBlank() }
                            val postalCode = data5?.takeIf { it.isNotBlank() }
                            val country = data6?.takeIf { it.isNotBlank() }
                            val label = data2?.takeIf { it.isNotBlank() }
                            if (street != null || city != null || region != null || postalCode != null || country != null) {
                                addresses.add(
                                    com.urlxl.mail.contacts.ContactAddressDto(
                                        label = label,
                                        street = street,
                                        city = city,
                                        region = region,
                                        postalCode = postalCode,
                                        country = country,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            if (fn.isBlank()) {
                return@withContext null
            }

            return@withContext DeviceRawContactSnapshot(
                rawContactId = actualRawContactId,
                contactId = contactId,
                accountType = accountType,
                accountName = null,
                lastUpdatedEpochMs = lastUpdated,
                dirty = false,
                fn = fn,
                org = org,
                notes = notes,
                birthday = birthday,
                emails = emails,
                phones = phones,
                addresses = addresses,
            )
        }
}
