package com.urlxl.mail.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [mergedContactDto] — the pure piece pulled out of [ContactEditActivity.save] (mirrors
 * [ContactSyncRepositoryTest]'s extraction approach for the same reason: unit-testable without a
 * Context-backed Room/Activity). Regression test for a real data-loss bug: `save()` used to build a
 * brand-new [ContactDto] from only the fields this single-screen editor exposes, so saving any edit
 * (even just fixing a phone number) silently wiped every other field — locally immediately, and on
 * the server too, since both the local upsert and the server's PUT/push handlers fully replace the
 * stored contact rather than merging. [mergedContactDto] must `.copy()` off the loaded contact
 * instead.
 */
class ContactEditActivityTest {

    private val loaded = ContactDto(
        uid = "uid-1",
        rev = 5,
        fn = "Old Name",
        givenName = "Old",
        familyName = "Name",
        middleName = "Middle",
        prefix = "Dr.",
        suffix = "Jr.",
        nickname = "Nick",
        org = "Old Org",
        title = "Old Title",
        notes = "Old notes",
        birthday = "1990-01-01",
        emails = listOf(ContactFieldDto(value = "old@example.com")),
        phones = listOf(ContactFieldDto(value = "555-0000")),
        addresses = listOf(ContactAddressDto(city = "Springfield")),
        groupIDs = listOf("group-1"),
        photoRef = "photo-ref-1",
        pgpKey = "pgp-key-1",
        ims = listOf(ContactImDto(service = "signal", value = "old-im")),
        websites = listOf(ContactUrlDto(value = "https://old.example.com")),
        relations = listOf(ContactRelationDto(name = "Spouse")),
        events = listOf(ContactEventDto(date = "2020-01-01")),
        phoneticGivenName = "Oh-ld",
        phoneticFamilyName = "Nay-m",
        department = "Engineering",
        customFields = listOf(ContactCustomFieldDto(label = "Custom", value = "Value")),
        pronouns = "they/them",
        isSelf = true,
    )

    @Test
    fun mergedContactDto_preservesEveryFieldTheEditorHasNoUiFor() {
        val result = mergedContactDto(
            loaded = loaded,
            uid = loaded.uid,
            rev = loaded.rev,
            fn = "New Name",
            org = "New Org",
            notes = "New notes",
            emails = listOf(ContactFieldDto(value = "new@example.com")),
            phones = listOf(ContactFieldDto(value = "555-1111")),
        )

        // Fields the editor's form actually exposes: reflect the new, edited values.
        assertEquals("New Name", result.fn)
        assertEquals("New Org", result.org)
        assertEquals("New notes", result.notes)
        assertEquals(listOf(ContactFieldDto(value = "new@example.com")), result.emails)
        assertEquals(listOf(ContactFieldDto(value = "555-1111")), result.phones)

        // Every other field: must survive untouched — this is exactly what used to get wiped.
        assertEquals(loaded.givenName, result.givenName)
        assertEquals(loaded.familyName, result.familyName)
        assertEquals(loaded.middleName, result.middleName)
        assertEquals(loaded.prefix, result.prefix)
        assertEquals(loaded.suffix, result.suffix)
        assertEquals(loaded.nickname, result.nickname)
        assertEquals(loaded.title, result.title)
        assertEquals(loaded.birthday, result.birthday)
        assertEquals(loaded.addresses, result.addresses)
        assertEquals(loaded.groupIDs, result.groupIDs)
        assertEquals(loaded.photoRef, result.photoRef)
        assertEquals(loaded.pgpKey, result.pgpKey)
        assertEquals(loaded.ims, result.ims)
        assertEquals(loaded.websites, result.websites)
        assertEquals(loaded.relations, result.relations)
        assertEquals(loaded.events, result.events)
        assertEquals(loaded.phoneticGivenName, result.phoneticGivenName)
        assertEquals(loaded.phoneticFamilyName, result.phoneticFamilyName)
        assertEquals(loaded.department, result.department)
        assertEquals(loaded.customFields, result.customFields)
        assertEquals(loaded.pronouns, result.pronouns)
        assertEquals(loaded.isSelf, result.isSelf)
    }

    @Test
    fun mergedContactDto_newContact_leavesUnsetFieldsAtDefaults() {
        // No prior contact to preserve fields from — ContactDto()'s all-default value is correct.
        val result = mergedContactDto(
            loaded = ContactDto(),
            uid = "",
            rev = 0,
            fn = "Brand New",
            org = null,
            notes = null,
            emails = emptyList(),
            phones = emptyList(),
        )

        assertEquals("Brand New", result.fn)
        assertEquals(ContactDto(fn = "Brand New"), result)
    }
}
