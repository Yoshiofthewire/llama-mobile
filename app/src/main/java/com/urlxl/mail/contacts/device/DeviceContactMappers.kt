package com.urlxl.mail.contacts.device

import com.urlxl.mail.contacts.ContactDto
import com.urlxl.mail.data.ContactEntity
import kotlinx.serialization.json.Json

object DeviceContactMappers {
    private val json = Json { ignoreUnknownKeys = true }

    fun ContactEntity.toDto(): ContactDto {
        val emails = runCatching {
            json.decodeFromString<List<com.urlxl.mail.contacts.ContactFieldDto>>(emailsJson)
        }.getOrDefault(emptyList())

        val phones = runCatching {
            json.decodeFromString<List<com.urlxl.mail.contacts.ContactFieldDto>>(phonesJson)
        }.getOrDefault(emptyList())

        val addresses = runCatching {
            json.decodeFromString<List<com.urlxl.mail.contacts.ContactAddressDto>>(addressesJson)
        }.getOrDefault(emptyList())

        return ContactDto(
            uid = uid,
            rev = rev,
            deleted = false,
            createdAt = createdAt,
            updatedAt = updatedAt,
            fn = fn,
            givenName = givenName,
            familyName = familyName,
            middleName = middleName,
            prefix = prefix,
            suffix = suffix,
            nickname = nickname,
            org = org,
            title = title,
            notes = notes,
            birthday = birthday,
            emails = emails,
            phones = phones,
            addresses = addresses,
        )
    }

    fun DeviceRawContactSnapshot.toContactDto(uid: String, rev: Long): ContactDto {
        return ContactDto(
            uid = uid,
            rev = rev,
            deleted = false,
            fn = fn,
            givenName = null,
            familyName = null,
            middleName = null,
            prefix = null,
            suffix = null,
            nickname = null,
            org = org,
            title = null,
            notes = notes,
            birthday = birthday,
            emails = emails,
            phones = phones,
            addresses = addresses,
        )
    }

    fun ContactDto.toDeviceFieldSet(): DeviceFieldSet {
        return DeviceFieldSet(
            fn = fn,
            givenName = givenName,
            familyName = familyName,
            middleName = middleName,
            prefix = prefix,
            suffix = suffix,
            nickname = nickname,
            org = org,
            title = title,
            notes = notes,
            birthday = birthday,
            emails = emails,
            phones = phones,
            addresses = addresses,
        )
    }
}
