# Contact `isSelf` Flag Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sync and display the server's new `isSelf` contact flag, and let a PGP QR scan that includes a `contactCard` create a brand-new contact from it.

**Architecture:** Thread `isSelf` through the existing `ContactDto`/`ContactEntity`/`ContactMappers`/Room-migration stack exactly like every other contact field added post-launch (`MIGRATION_3_4` is the template). Add a `contactCard` field to the existing `PgpQrKeyDto`, reusing the app's existing `ContactFieldDto`-family types rather than inventing parallel ones. Wire a new "create contact from card" path into `PgpKeyActivity` alongside its existing "attach key to picked contact" path.

**Tech Stack:** Kotlin, Room (SQLite), kotlinx.serialization, OkHttp, JUnit (plain-JVM unit tests + `androidx.test`/`MigrationTestHelper` instrumented tests) — all pre-existing in this repo, no new dependencies.

## Global Constraints

- `AppDatabase.version` must become `6`; the new `MIGRATION_5_6` must be registered in `DataGraph`'s `.addMigrations(...)` call in `app/src/main/java/com/urlxl/mail/data/DataRuntime.kt`, exactly like `MIGRATION_1_2`..`MIGRATION_4_5` are today.
- `isSelf` is a read-only mirror of the server's flag: the client never sets it locally and never relies on sending it back on create/update.
- `PgpQrContactCardDto`'s JSON field names must exactly match the server's `pgpQRContactCard` struct tags: `fn`, `givenName`, `familyName`, `middleName`, `prefix`, `suffix`, `nickname`, `org`, `title`, `emails`, `phones`, `addresses`, `notes`, `birthday`, `ims`, `websites`, `relations`, `events`, `phoneticGivenName`, `phoneticFamilyName`, `department`, `customFields`, `pronouns`.
- Reuse `ContactFieldDto` / `ContactAddressDto` / `ContactImDto` / `ContactUrlDto` / `ContactRelationDto` / `ContactEventDto` / `ContactCustomFieldDto` from `com.urlxl.mail.contacts` (`ContactSyncModels.kt`) inside `PgpQrContactCardDto` — do not define duplicate types.
- No mocking framework anywhere in this repo's tests — hand-rolled fakes only (see `PgpQrClientTest.kt`'s `FakeCallFactory`).
- New dialogs use `androidx.appcompat.app.AlertDialog` (the pattern already used in `ComposeActivity.kt`, `PushPairingActivity.kt`, `AboutDialog.kt`), not the platform `android.app.AlertDialog`.
- Run `./gradlew testDebugUnitTest` for plain-JVM tests and, where noted, `./gradlew connectedDebugAndroidTest` for instrumented tests (these require a running emulator/device — if none is available, note that in place of a pass/fail result rather than skipping silently).

---

### Task 1: Add `isSelf` to `ContactDto`/`ContactEntity`/`ContactMappers`

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactSyncModels.kt`
- Modify: `app/src/main/java/com/urlxl/mail/data/ContactEntity.kt`
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactMappers.kt`
- Test: `app/src/test/java/com/urlxl/mail/contacts/ContactMappersTest.kt`

**Interfaces:**
- Produces: `ContactDto.isSelf: Boolean` (default `false`), `ContactEntity.isSelf: Boolean` (default `false`, column default `0`). Both consumed by Task 3 (`ContactDao` ordering) and Task 4 (`ContactAdapter` label).

- [ ] **Step 1: Write the failing test**

Add to `ContactMappersTest.kt`, as a new test (don't modify the existing `toEntity_toDto_roundTripsAllExtendedFields` test body — add a new one so the diff is additive):

```kotlin
    @Test
    fun toEntity_toDto_roundTripsIsSelf() {
        val selfDto = ContactDto(uid = "uid-self", fn = "Me", isSelf = true)
        val otherDto = ContactDto(uid = "uid-other", fn = "Not Me", isSelf = false)

        assertEquals(true, selfDto.toEntity().toDto().isSelf)
        assertEquals(true, selfDto.toEntity().isSelf)
        assertEquals(false, otherDto.toEntity().toDto().isSelf)
        assertEquals(false, otherDto.toEntity().isSelf)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.contacts.ContactMappersTest"`
Expected: FAIL — compile error, `isSelf` is not a parameter of `ContactDto`.

- [ ] **Step 3: Add `isSelf` to `ContactDto`**

In `ContactSyncModels.kt`, add the field as the last parameter of `ContactDto` (after `pronouns`):

```kotlin
    val pronouns: String? = null,
    val isSelf: Boolean = false,
)
```

- [ ] **Step 4: Add `isSelf` to `ContactEntity`**

In `ContactEntity.kt`, add as the last property (after `pronouns`):

```kotlin
    val pronouns: String? = null,
    @ColumnInfo(defaultValue = "0") val isSelf: Boolean = false,
)
```

- [ ] **Step 5: Wire it through `ContactMappers.kt`**

In `toEntity()`, add `isSelf = isSelf,` as the last argument (after `pronouns = pronouns,`). In `toDto()`, add `isSelf = isSelf,` as the last argument (after `pronouns = pronouns,`).

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.contacts.ContactMappersTest"`
Expected: PASS (all `ContactMappersTest` tests, including the new one and the pre-existing ones — the new field's default doesn't disturb the existing round-trip assertions since they never set or check `isSelf`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactSyncModels.kt \
        app/src/main/java/com/urlxl/mail/data/ContactEntity.kt \
        app/src/main/java/com/urlxl/mail/contacts/ContactMappers.kt \
        app/src/test/java/com/urlxl/mail/contacts/ContactMappersTest.kt
git commit -m "feat: sync isSelf flag on contacts"
```

---

### Task 2: Room migration 5→6 for the `isSelf` column

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/urlxl/mail/data/DataRuntime.kt`
- Test: `app/src/androidTest/java/com/urlxl/mail/data/MigrationTest.kt`

**Interfaces:**
- Consumes: `ContactEntity.isSelf` from Task 1 (the entity Room validates the post-migration schema against).
- Produces: `AppDatabase.MIGRATION_5_6`, `AppDatabase.version == 6`. Consumed by `DataRuntime.kt`'s migration list and by Task 3's `ContactDaoOrderingTest` (which uses `Room.inMemoryDatabaseBuilder`, so it picks up the new column automatically once `ContactEntity` and `AppDatabase.version` are updated — no direct dependency on the migration itself, but both must compile together).

- [ ] **Step 1: Write the failing migration test**

Add to `MigrationTest.kt`, as a new test after `migrate4To5_createsGroupTables_andPreservesExistingContactRow`:

```kotlin
    @Test
    fun migrate5To6_addsIsSelfColumn_andPreservesExistingRow() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                "INSERT INTO contacts (uid, rev, fn, emailsJson, phonesJson, addressesJson) " +
                    "VALUES ('uid-1', 1, 'Ada Lovelace', '[]', '[]', '[]')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6)

        migrated.query("SELECT * FROM contacts WHERE uid = 'uid-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Ada Lovelace", cursor.getString(cursor.getColumnIndexOrThrow("fn")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isSelf")))
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew connectedDebugAndroidTest --tests "com.urlxl.mail.data.MigrationTest"`
Expected: FAIL — compile error, `AppDatabase.MIGRATION_5_6` doesn't exist yet. (If no emulator/device is attached, this step cannot run — note that explicitly rather than skipping it silently, and confirm the equivalent check once a device is available.)

- [ ] **Step 3: Add `MIGRATION_5_6` and bump the database version**

In `AppDatabase.kt`, change `version = 5` to `version = 6` in the `@Database` annotation, and add after `MIGRATION_4_5`:

```kotlin
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `isSelf` INTEGER NOT NULL DEFAULT 0")
            }
        }
```

- [ ] **Step 4: Register the migration in `DataRuntime.kt`**

In `DataGraph`'s `.addMigrations(...)` call, add `AppDatabase.MIGRATION_5_6,` after `AppDatabase.MIGRATION_4_5,`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew connectedDebugAndroidTest --tests "com.urlxl.mail.data.MigrationTest"`
Expected: PASS (all `MigrationTest` cases, including the new 5→6 one).

- [ ] **Step 6: Regenerate the exported Room schema**

Run: `./gradlew :app:compileDebugKotlin` (or a full `assembleDebug`) so Room's schema-export annotation processor writes `app/schemas/com.urlxl.mail.data.AppDatabase/6.json`. Verify the file now exists:

Run: `ls app/schemas/com.urlxl.mail.data.AppDatabase/`
Expected: includes `6.json` alongside `1.json`..`5.json`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/data/AppDatabase.kt \
        app/src/main/java/com/urlxl/mail/data/DataRuntime.kt \
        app/src/androidTest/java/com/urlxl/mail/data/MigrationTest.kt \
        app/schemas/com.urlxl.mail.data.AppDatabase/6.json
git commit -m "feat: add Room migration for the isSelf contact column"
```

---

### Task 3: Sort the self-contact to the top of the contact list

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/data/ContactDao.kt`
- Create: `app/src/androidTest/java/com/urlxl/mail/data/ContactDaoOrderingTest.kt`

**Interfaces:**
- Consumes: `ContactEntity.isSelf` from Task 1.
- Produces: `ContactDao.observeAll()` now orders self-contact first — consumed implicitly by `ContactsListActivity`/`ContactAdapter` (Task 4), no signature change.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/urlxl/mail/data/ContactDaoOrderingTest.kt`:

```kotlin
package com.urlxl.mail.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Mirrors [ContactDaoSearchTest]'s in-memory-DB setup; covers [ContactDao.observeAll]'s
 *  self-contact-first ordering rather than [ContactDao.search]'s substring matching. */
@RunWith(AndroidJUnit4::class)
class ContactDaoOrderingTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.contactDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeAll_sortsSelfContactFirst_thenByNameCaseInsensitive() = runBlocking {
        dao.upsertAll(
            listOf(
                ContactEntity(uid = "1", rev = 1, fn = "Zack Test"),
                ContactEntity(uid = "2", rev = 1, fn = "Zzz Self", isSelf = true),
                ContactEntity(uid = "3", rev = 1, fn = "Bob Test"),
            ),
        )

        val results = dao.observeAll().first()

        // "Zzz Self" sorts last alphabetically but must come first because it's the self-contact —
        // this fixture only passes once ORDER BY actually prioritizes isSelf over fn.
        assertEquals(listOf("Zzz Self", "Bob Test", "Zack Test"), results.map { it.fn })
        assertEquals(true, results.first().isSelf)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew connectedDebugAndroidTest --tests "com.urlxl.mail.data.ContactDaoOrderingTest"`
Expected: FAIL — with today's `ORDER BY fn COLLATE NOCASE`, results come back as `[Bob Test, Zack Test, Zzz Self]`, not `[Zzz Self, Bob Test, Zack Test]`.

- [ ] **Step 3: Change `ContactDao.observeAll()`'s ordering**

In `ContactDao.kt`, change:

```kotlin
    @Query("SELECT * FROM contacts ORDER BY fn COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>
```

to:

```kotlin
    @Query("SELECT * FROM contacts ORDER BY isSelf DESC, fn COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>
```

Leave `search()`'s query untouched — it's a separate feature (autocomplete) not covered by this change.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew connectedDebugAndroidTest --tests "com.urlxl.mail.data.ContactDaoOrderingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/data/ContactDao.kt \
        app/src/androidTest/java/com/urlxl/mail/data/ContactDaoOrderingTest.kt
git commit -m "feat: sort the self-contact to the top of the contact list"
```

---

### Task 4: Label the self-contact in the contact list UI

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactAdapter.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ContactEntity.isSelf` from Task 1, `ContactDao.observeAll()`'s self-first ordering from Task 3 (so this is the last piece needed to see the full feature working end to end in the app).

- [ ] **Step 1: Add the string resource**

In `strings.xml`, add after `contact_status_no_key` (line 80):

```xml
    <string name="contact_self_label">This is you</string>
```

- [ ] **Step 2: Update `ContactAdapter.ContactViewHolder.bind()`**

In `ContactAdapter.kt`, replace:

```kotlin
            detailView.text = contact.org?.takeIf { it.isNotBlank() } ?: ""
            detailView.visibility = if (detailView.text.isBlank()) View.GONE else View.VISIBLE
```

with:

```kotlin
            val orgText = contact.org?.takeIf { it.isNotBlank() }
            val selfLabel = if (contact.isSelf) itemView.context.getString(R.string.contact_self_label) else null
            detailView.text = listOfNotNull(selfLabel, orgText).joinToString(" · ")
            detailView.visibility = if (detailView.text.isBlank()) View.GONE else View.VISIBLE
```

- [ ] **Step 3: Build and manually verify**

There is no existing `ContactAdapter` unit/instrumented test file in this repo to extend (checked: none present), and `ContactAdapter` binds a real `ThemePalette`/context, so this step is manual rather than automated, matching the spec's testing section. Build and install the debug app:

Run: `./gradlew installDebug`

Then in the running app: open Contacts. If any synced contact has `isSelf = true` (set via the kypost-server web app's "Use as my contact card" toggle on a contact, then a manual sync in this app), confirm that contact appears first in the list and its detail line reads "This is you" (or "This is you · <org>" if it also has an org). If no self-contact exists yet in the test account, mark one via the web app first, then trigger a sync (Contacts screen menu → "Sync now") before checking.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactAdapter.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat: label the self-contact in the contact list"
```

---

### Task 5: Add `contactCard` to the PGP QR key response model

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/pgp/PgpQrModels.kt`
- Test: `app/src/test/java/com/urlxl/mail/pgp/PgpQrClientTest.kt`

**Interfaces:**
- Produces: `PgpQrContactCardDto` (all fields nullable/default-empty, matching the server's `omitempty` response shape) and `PgpQrKeyDto.contactCard: PgpQrContactCardDto?` (default `null`). Consumed by Task 6 (the card→`ContactDto` mapping function) and Task 7 (`PgpKeyActivity`'s dialog branching).

- [ ] **Step 1: Write the failing test**

Add to `PgpQrClientTest.kt`, in the `// ---- fetchKey ----` section, after `fetchKey_200_decodesKeyAndSendsExpectedRequest_noAuthParams`:

```kotlin
    @Test
    fun fetchKey_200_withContactCard_decodesCardFields() = runBlocking {
        val callFactory = FakeCallFactory { request ->
            response(
                request,
                """
                {
                  "name": "Alice",
                  "fingerprint": "A1B2C3D4E5F6",
                  "publicKey": "-----BEGIN PGP PUBLIC KEY BLOCK-----...",
                  "contactCard": {
                    "fn": "Alice Example",
                    "org": "Example Corp",
                    "emails": [{"label": "work", "value": "alice@example.com"}],
                    "phones": [{"value": "+1-555-0100"}]
                  }
                }
                """.trimIndent(),
                200,
            )
        }
        val client = PgpQrClient(callFactory = callFactory)

        val result = client.fetchKey("https://relay.example.com", "tok-1")

        assertTrue(result is PgpQrKeyResult.Success)
        val card = (result as PgpQrKeyResult.Success).key.contactCard
        assertEquals("Alice Example", card?.fn)
        assertEquals("Example Corp", card?.org)
        assertEquals("alice@example.com", card?.emails?.single()?.value)
        assertEquals("+1-555-0100", card?.phones?.single()?.value)
    }

    @Test
    fun fetchKey_200_withoutContactCard_decodesNullCard() = runBlocking {
        val callFactory = FakeCallFactory { request ->
            response(
                request,
                """{"name": "Alice", "fingerprint": "A1B2C3D4E5F6", "publicKey": "-----BEGIN PGP PUBLIC KEY BLOCK-----..."}""",
                200,
            )
        }
        val client = PgpQrClient(callFactory = callFactory)

        val result = client.fetchKey("https://relay.example.com", "tok-1")

        assertTrue(result is PgpQrKeyResult.Success)
        assertNull((result as PgpQrKeyResult.Success).key.contactCard)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.pgp.PgpQrClientTest"`
Expected: FAIL — compile error, `PgpQrKeyDto` has no `contactCard` property.

- [ ] **Step 3: Add `PgpQrContactCardDto` and wire it into `PgpQrKeyDto`**

Replace the full contents of `PgpQrModels.kt` with:

```kotlin
package com.urlxl.mail.pgp

import com.urlxl.mail.contacts.ContactAddressDto
import com.urlxl.mail.contacts.ContactCustomFieldDto
import com.urlxl.mail.contacts.ContactEventDto
import com.urlxl.mail.contacts.ContactFieldDto
import com.urlxl.mail.contacts.ContactImDto
import com.urlxl.mail.contacts.ContactRelationDto
import com.urlxl.mail.contacts.ContactUrlDto
import kotlinx.serialization.Serializable

/** Response body of `GET /api/pgp/qr/token` (pairing-authenticated). */
@Serializable
data class PgpQrTokenDto(
    val token: String = "",
    val expiresAt: String = "",
    val url: String = "",
)

/** Response body of `GET /api/pgp/qr/key` (unauthenticated, token-gated). */
@Serializable
data class PgpQrKeyDto(
    val name: String = "",
    val fingerprint: String = "",
    val publicKey: String = "",
    val contactCard: PgpQrContactCardDto? = null,
)

/** The shareable subset of the token owner's self-contact (server's `contacts.Contact` with
 *  `isSelf == true`), included in [PgpQrKeyDto] when they have one set. Field names and types
 *  mirror the server's `pgpQRContactCard` struct exactly (`backend/internal/api/pgp_qr_handlers.go`
 *  in kypost-server); it reuses this app's existing [ContactFieldDto]-family types rather than
 *  duplicating them, since [com.urlxl.mail.contacts.ContactDto] already models the identical JSON
 *  shapes for the app's own contact sync. */
@Serializable
data class PgpQrContactCardDto(
    val fn: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
    val middleName: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val nickname: String? = null,
    val org: String? = null,
    val title: String? = null,
    val emails: List<ContactFieldDto> = emptyList(),
    val phones: List<ContactFieldDto> = emptyList(),
    val addresses: List<ContactAddressDto> = emptyList(),
    val notes: String? = null,
    val birthday: String? = null,
    val ims: List<ContactImDto> = emptyList(),
    val websites: List<ContactUrlDto> = emptyList(),
    val relations: List<ContactRelationDto> = emptyList(),
    val events: List<ContactEventDto> = emptyList(),
    val phoneticGivenName: String? = null,
    val phoneticFamilyName: String? = null,
    val department: String? = null,
    val customFields: List<ContactCustomFieldDto> = emptyList(),
    val pronouns: String? = null,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.pgp.PgpQrClientTest"`
Expected: PASS (all `PgpQrClientTest` tests, including the two new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/pgp/PgpQrModels.kt \
        app/src/test/java/com/urlxl/mail/pgp/PgpQrClientTest.kt
git commit -m "feat: decode contactCard from the PGP QR key response"
```

---

### Task 6: Map a scanned `contactCard` to a `ContactDto`

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/pgp/PgpKeyActivity.kt`
- Test: `app/src/test/java/com/urlxl/mail/pgp/PgpKeyActivityTest.kt`

**Interfaces:**
- Consumes: `PgpQrContactCardDto` from Task 5, `com.urlxl.mail.contacts.ContactDto` (already defined).
- Produces: `PgpKeyActivity.contactDtoFromCard(card: PgpQrContactCardDto, fallbackName: String, pgpKey: String): ContactDto` (companion-object function, same visibility/testability pattern as the existing `parsePgpQrKeyUrl`). Consumed by Task 7's `createNewContactFromCard`.

- [ ] **Step 1: Write the failing test**

Add to `PgpKeyActivityTest.kt`:

```kotlin
    @Test
    fun contactDtoFromCard_mapsAllFields() {
        val card = PgpQrContactCardDto(
            fn = "Alice Example",
            givenName = "Alice",
            familyName = "Example",
            org = "Example Corp",
            title = "Engineer",
            emails = listOf(ContactFieldDto(label = "work", value = "alice@example.com")),
            phones = listOf(ContactFieldDto(value = "+1-555-0100")),
            notes = "Met at conference",
            pronouns = "she/her",
        )

        val dto = PgpKeyActivity.contactDtoFromCard(card, fallbackName = "Alice", pgpKey = "PUBKEY")

        assertEquals("Alice Example", dto.fn)
        assertEquals("Alice", dto.givenName)
        assertEquals("Example", dto.familyName)
        assertEquals("Example Corp", dto.org)
        assertEquals("Engineer", dto.title)
        assertEquals("alice@example.com", dto.emails.single().value)
        assertEquals("+1-555-0100", dto.phones.single().value)
        assertEquals("Met at conference", dto.notes)
        assertEquals("she/her", dto.pronouns)
        assertEquals("PUBKEY", dto.pgpKey)
    }

    @Test
    fun contactDtoFromCard_blankCardName_fallsBackToScannedName() {
        val card = PgpQrContactCardDto(fn = null)

        val dto = PgpKeyActivity.contactDtoFromCard(card, fallbackName = "Alice", pgpKey = "PUBKEY")

        assertEquals("Alice", dto.fn)
    }
```

Add the needed imports at the top of the test file:

```kotlin
import com.urlxl.mail.contacts.ContactFieldDto
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.pgp.PgpKeyActivityTest"`
Expected: FAIL — compile error, `PgpKeyActivity.contactDtoFromCard` doesn't exist yet.

- [ ] **Step 3: Add `contactDtoFromCard` to `PgpKeyActivity`'s companion object**

In `PgpKeyActivity.kt`, add the import `import com.urlxl.mail.contacts.ContactDto` alongside the existing `com.urlxl.mail.contacts.*` imports, and add this function inside the `companion object` block, after `parsePgpQrKeyUrl`'s closing brace (still inside the companion object, before its own closing `}`):

```kotlin

        /** Maps a scanned [PgpQrContactCardDto] to a creatable [ContactDto], for the "Create New
         *  Contact" path in [showSaveChoiceDialog]. [fallbackName] (the scan's top-level `name`)
         *  fills in `fn` when the card itself carries no name — `ContactDto.fn` must be non-blank
         *  per Mobile_Contact_Sync.md, and a card's `fn` is `omitempty` server-side so it can be
         *  legitimately absent. */
        internal fun contactDtoFromCard(card: PgpQrContactCardDto, fallbackName: String, pgpKey: String): ContactDto =
            ContactDto(
                fn = card.fn?.takeIf { it.isNotBlank() } ?: fallbackName,
                givenName = card.givenName,
                familyName = card.familyName,
                middleName = card.middleName,
                prefix = card.prefix,
                suffix = card.suffix,
                nickname = card.nickname,
                org = card.org,
                title = card.title,
                notes = card.notes,
                birthday = card.birthday,
                emails = card.emails,
                phones = card.phones,
                addresses = card.addresses,
                ims = card.ims,
                websites = card.websites,
                relations = card.relations,
                events = card.events,
                phoneticGivenName = card.phoneticGivenName,
                phoneticFamilyName = card.phoneticFamilyName,
                department = card.department,
                customFields = card.customFields,
                pronouns = card.pronouns,
                pgpKey = pgpKey,
            )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.pgp.PgpKeyActivityTest"`
Expected: PASS (all `PgpKeyActivityTest` tests, including the two new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/pgp/PgpKeyActivity.kt \
        app/src/test/java/com/urlxl/mail/pgp/PgpKeyActivityTest.kt
git commit -m "feat: map a scanned PGP QR contact card to a ContactDto"
```

---

### Task 7: Offer "Create New Contact" when a scan includes a contact card

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/pgp/PgpKeyActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `PgpKeyActivity.contactDtoFromCard(...)` from Task 6, `PgpQrKeyDto.contactCard` from Task 5, `ContactsRuntime.graph(this).repository.queueCreate(dto)` / `.coordinator.syncNowAsync()` (existing, same calls `saveKeyToContact` already makes).
- Produces: no new public interface — this is the final UI wiring task.

- [ ] **Step 1: Add the new string resources**

In `strings.xml`, add after `pgp_qr_scan_saved` (line 156):

```xml
    <string name="pgp_qr_scan_save_choice_title">Save this contact</string>
    <string name="pgp_qr_scan_save_new_button">Create New Contact</string>
    <string name="pgp_qr_scan_save_existing_button">Add to Existing Contact</string>
    <string name="pgp_qr_scan_saved_new">Contact created with PGP key</string>
```

- [ ] **Step 2: Add the `AlertDialog` import**

In `PgpKeyActivity.kt`, add `import androidx.appcompat.app.AlertDialog` alongside the existing `androidx.appcompat.app.AppCompatActivity` import.

- [ ] **Step 3: Replace `onFingerprintConfirmed` with the branching version**

In `PgpKeyActivity.kt`, replace:

```kotlin
    private fun onFingerprintConfirmed() {
        if (pendingKey == null) return
        pickContactLauncher.launch(
            Intent(this, ContactsListActivity::class.java).putExtra(ContactsListActivity.EXTRA_PICK_MODE, true),
        )
    }
```

with:

```kotlin
    private fun onFingerprintConfirmed() {
        val key = pendingKey ?: return
        if (key.contactCard != null) {
            showSaveChoiceDialog(key)
        } else {
            launchContactPicker()
        }
    }

    private fun showSaveChoiceDialog(key: PgpQrKeyDto) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_qr_scan_save_choice_title)
            .setPositiveButton(R.string.pgp_qr_scan_save_new_button) { _, _ -> createNewContactFromCard(key) }
            .setNegativeButton(R.string.pgp_qr_scan_save_existing_button) { _, _ -> launchContactPicker() }
            .show()
    }

    private fun launchContactPicker() {
        pickContactLauncher.launch(
            Intent(this, ContactsListActivity::class.java).putExtra(ContactsListActivity.EXTRA_PICK_MODE, true),
        )
    }

    private fun createNewContactFromCard(key: PgpQrKeyDto) {
        val card = key.contactCard ?: return
        val dto = contactDtoFromCard(card, fallbackName = key.name, pgpKey = key.publicKey)
        lifecycleScope.launch {
            val graph = ContactsRuntime.graph(this@PgpKeyActivity)
            graph.repository.queueCreate(dto)
            graph.coordinator.syncNowAsync()

            Toast.makeText(this@PgpKeyActivity, R.string.pgp_qr_scan_saved_new, Toast.LENGTH_SHORT).show()
            resetConfirmationState()
            scanStatusText.text = ""
            scanButton.setText(R.string.pgp_qr_scan_scan_button)
        }
    }
```

- [ ] **Step 4: Build and manually verify**

`PgpKeyActivity` has no Espresso/instrumented UI test today (only its pure-function `parsePgpQrKeyUrl`/`contactDtoFromCard` logic is unit-tested), so this step is manual, matching the spec's testing section.

Run: `./gradlew installDebug`

Then in the running app, with two paired devices/accounts where the scanned account has a self-contact set (via the kypost-server web app):
1. Open PGP Key Signing, scan the other account's QR code, confirm the fingerprint.
2. Confirm the "Save this contact" dialog appears with "Create New Contact" and "Add to Existing Contact".
3. Tap "Create New Contact" — confirm the toast reads "Contact created with PGP key", then open Contacts and confirm a new contact was created with the scanned card's name/org/email/etc. and a PGP key attached (shows "Secure key" status).
4. Repeat the scan against an account with **no** self-contact set — confirm the flow is unchanged from before this feature (no dialog, straight to the existing contact picker).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/pgp/PgpKeyActivity.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat: create a new contact from a scanned PGP QR contact card"
```
