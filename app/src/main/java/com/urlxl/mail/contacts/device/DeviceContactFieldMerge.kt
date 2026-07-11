package com.urlxl.mail.contacts.device

import com.urlxl.mail.contacts.ContactAddressDto
import com.urlxl.mail.contacts.ContactFieldDto

object DeviceContactFieldMerge {
    fun <T> mergeField(
        roomValue: T?,
        deviceValue: T?,
        isEmpty: (T) -> Boolean,
        recordWinner: (T?, Winner) -> Unit,
    ): T? {
        val roomEmpty = roomValue == null || isEmpty(roomValue)
        val deviceEmpty = deviceValue == null || isEmpty(deviceValue)

        return when {
            roomEmpty && deviceEmpty -> {
                recordWinner(null, Winner.TIE_PREFER_ROOM)
                null
            }
            roomEmpty && !deviceEmpty -> {
                recordWinner(deviceValue, Winner.DEVICE)
                deviceValue
            }
            !roomEmpty && deviceEmpty -> {
                recordWinner(roomValue, Winner.ROOM)
                roomValue
            }
            else -> {
                val winner = Winner.TIE_PREFER_ROOM
                val result = roomValue
                recordWinner(result ?: deviceValue, winner)
                result
            }
        }
    }

    fun mergeStringField(
        roomValue: String?,
        deviceValue: String?,
        roomUpdatedAtEpochMs: Long?,
        deviceUpdatedAtEpochMs: Long?,
    ): String? {
        val roomEmpty = roomValue.isNullOrBlank()
        val deviceEmpty = deviceValue.isNullOrBlank()

        return when {
            roomEmpty && deviceEmpty -> null
            roomEmpty && !deviceEmpty -> deviceValue
            !roomEmpty && deviceEmpty -> roomValue
            else -> {
                val winner = DeviceContactConflictResolver.resolve(roomUpdatedAtEpochMs, deviceUpdatedAtEpochMs)
                when (winner) {
                    Winner.DEVICE -> deviceValue
                    else -> roomValue
                }
            }
        }
    }

    fun mergeEmailList(
        roomEmails: List<ContactFieldDto>,
        deviceEmails: List<ContactFieldDto>,
        roomUpdatedAtEpochMs: Long?,
        deviceUpdatedAtEpochMs: Long?,
    ): List<ContactFieldDto> {
        if (roomEmails.isEmpty() && deviceEmails.isEmpty()) return emptyList()
        if (roomEmails.isEmpty()) return deviceEmails
        if (deviceEmails.isEmpty()) return roomEmails

        val winner = DeviceContactConflictResolver.resolve(roomUpdatedAtEpochMs, deviceUpdatedAtEpochMs)
        return when (winner) {
            Winner.DEVICE -> deviceEmails
            else -> roomEmails
        }
    }

    fun mergePhoneList(
        roomPhones: List<ContactFieldDto>,
        devicePhones: List<ContactFieldDto>,
        roomUpdatedAtEpochMs: Long?,
        deviceUpdatedAtEpochMs: Long?,
    ): List<ContactFieldDto> {
        if (roomPhones.isEmpty() && devicePhones.isEmpty()) return emptyList()
        if (roomPhones.isEmpty()) return devicePhones
        if (devicePhones.isEmpty()) return roomPhones

        val winner = DeviceContactConflictResolver.resolve(roomUpdatedAtEpochMs, deviceUpdatedAtEpochMs)
        return when (winner) {
            Winner.DEVICE -> devicePhones
            else -> roomPhones
        }
    }

    fun mergeAddressList(
        roomAddresses: List<ContactAddressDto>,
        deviceAddresses: List<ContactAddressDto>,
        roomUpdatedAtEpochMs: Long?,
        deviceUpdatedAtEpochMs: Long?,
    ): List<ContactAddressDto> {
        if (roomAddresses.isEmpty() && deviceAddresses.isEmpty()) return emptyList()
        if (roomAddresses.isEmpty()) return deviceAddresses
        if (deviceAddresses.isEmpty()) return roomAddresses

        val winner = DeviceContactConflictResolver.resolve(roomUpdatedAtEpochMs, deviceUpdatedAtEpochMs)
        return when (winner) {
            Winner.DEVICE -> deviceAddresses
            else -> roomAddresses
        }
    }
}
