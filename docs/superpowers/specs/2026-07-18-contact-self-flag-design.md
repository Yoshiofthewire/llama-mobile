# Contact `isSelf` flag — design

## Problem

`kypost-server` now has an `isSelf` flag on `Contact` (see
`kypost-server/docs/superpowers/specs/2026-07-15-pgp-qr-contact-card-design.md`):
a user can flag one contact in their own address book as "this is me" via the
web app. The server does two things with it:

1. `GET /api/pgp/qr/key` (the unauthenticated, token-gated endpoint a
   scanning device hits) now includes a `contactCard` object — the flagged
   contact's shareable fields — alongside the existing `name`/`fingerprint`/
   `publicKey`, when the token owner has a self-contact set.
2. Normal contact sync (`ContactDto`/pull) now includes `isSelf` on every
   contact, so at most one synced contact has it `true`.

`kypost-android` (this repo) needs to:

- Sync and store `isSelf` alongside every other contact field (read-only —
  the app never sets it; that stays a web-only action, matching the server
  spec's scope).
- Show the self-contact at the top of the contact list with a label.
- When scanning someone else's PGP QR code and their response includes a
  `contactCard`, let the user create a new contact from it (not just attach
  the key to an existing contact, today's only path).

## Scope

This is the mobile-side follow-up the server spec explicitly deferred
("turning a scanned contact card into a saved Android contact... is a
follow-up, not covered here"). Marking a *local* contact as self from the
app itself is out of scope — the server spec only built that toggle on the
web frontend, and the mobile app has no equivalent edit surface for it.

## Data model — sync

- `ContactDto` (`app/src/main/java/com/urlxl/mail/contacts/ContactSyncModels.kt`):
  add `val isSelf: Boolean = false`.
- `ContactEntity` (`app/src/main/java/com/urlxl/mail/data/ContactEntity.kt`):
  add `@ColumnInfo(defaultValue = "0") val isSelf: Boolean = false` — same
  pattern as the other columns added post-launch (`groupIDsJson`,
  `pronouns`, etc. via `MIGRATION_3_4`).
- `AppDatabase` (`app/src/main/java/com/urlxl/mail/data/AppDatabase.kt`):
  bump `version` 5 → 6, add:
  ```kotlin
  val MIGRATION_5_6 = object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE `contacts` ADD COLUMN `isSelf` INTEGER NOT NULL DEFAULT 0")
      }
  }
  ```
  Register it in `DataRuntime.kt`'s `.addMigrations(...)` call alongside the
  existing ones.
- `ContactMappers.kt`: pass `isSelf` through both `toEntity()` and `toDto()`.
- The app never sends `isSelf` back on create/update — the server ignores/
  preserves the existing value on normal edits regardless (see
  `Store.Upsert` in the server spec), so this is purely a pull-and-display
  concern.
- No changes needed at `DeviceContactMappers.kt` / `DeviceContactRepository.kt`
  / other `ContactDto`/`ContactEntity` construction sites — they use named
  constructor args, so the new field's default (`false`) applies
  automatically.

## Contact list — self-contact pinned to top, labeled

- `ContactDao.observeAll()` (`app/src/main/java/com/urlxl/mail/data/ContactDao.kt`):
  change `ORDER BY fn COLLATE NOCASE` to `ORDER BY isSelf DESC, fn COLLATE NOCASE`.
  The separate `search()` query (autocomplete, a distinct feature) is left
  unchanged.
- `ContactAdapter.ContactViewHolder.bind()` (`app/src/main/java/com/urlxl/mail/contacts/ContactAdapter.kt`):
  when `contact.isSelf`, prepend a new string resource `contact_self_label`
  ("This is you") to the existing `detailView` text, joined with the org
  (if present) via " · ". No new views or layout changes — reuses the
  existing detail line in `item_contact.xml`.

## PGP QR scan — consume `contactCard`

- `PgpQrModels.kt`: add

  ```kotlin
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

  reusing `ContactFieldDto`/`ContactAddressDto`/`ContactImDto`/
  `ContactUrlDto`/`ContactRelationDto`/`ContactEventDto`/
  `ContactCustomFieldDto` from `com.urlxl.mail.contacts` — their JSON shapes
  already match the server's `pgpQRContactCard` field-for-field, so no
  duplicate types. Add `val contactCard: PgpQrContactCardDto? = null` to
  `PgpQrKeyDto`. `PgpQrClient` needs no code changes — `kotlinx.serialization`
  picks up the new field automatically (the client's `Json` already has
  `ignoreUnknownKeys = true`, and the reverse — a missing/absent field —
  just decodes to the `null` default).

- `PgpKeyActivity`:
  - Unchanged when `pendingKey.contactCard == null` (e.g. scanning someone
    with no self-contact set): `onFingerprintConfirmed()` goes straight to
    the existing "pick an existing contact" picker, exactly as today.
  - When `contactCard != null`, `onFingerprintConfirmed()` shows an
    `AlertDialog` with two choices: "Create New Contact" and "Add to
    Existing Contact".
    - **Create New Contact**: maps `PgpQrContactCardDto` → `ContactDto`
      field-for-field (`uid = ""`, `rev = 0`, `isSelf` omitted/default
      `false` since it's the *other* person's flag, not something this
      device should set locally), with `fn` falling back to the scanned
      key's top-level `name` if the card's `fn` is blank (a `ContactDto`
      needs a non-blank `fn` — see `Mobile_Contact_Sync.md`), and
      `pgpKey = key.publicKey`. Calls `graph.repository.queueCreate(dto)`
      then `graph.coordinator.syncNowAsync()` directly — the same
      direct-to-repository pattern `saveKeyToContact` already uses, bypassing
      `ContactEditActivity` because its single-value edit form can't
      represent the richer fields (`addresses`, `ims`, etc.) a card may
      carry.
    - **Add to Existing Contact**: reuses the current `pickContactLauncher`
      → `saveKeyToContact(uid)` flow unchanged.
  - New string resources: a dialog title, the two button labels, and a
    "Contact created with PGP key" success toast (distinct from the
    existing `pgp_qr_scan_saved`, which is worded for the attach-to-existing
    case).

## Testing

- `ContactMappersTest.kt`: round-trip `isSelf` through `toEntity`/`toDto`
  (both `true` and default `false`).
- `MigrationTest.kt`: new `migrate5To6_addsIsSelfColumn_andPreservesExistingRow`
  case, mirroring the existing 3→4/4→5 tests — insert a version-5 row, run
  `MIGRATION_5_6`, assert the row survives and `isSelf` defaults to `0`.
- `PgpQrClientTest.kt`: `fetchKey` decoding with `contactCard` present and
  absent (existing responses with no `contactCard` key must still decode
  cleanly to `contactCard = null`).
- `PgpKeyActivityTest.kt`: unit-test the card→`ContactDto` mapping function
  in isolation (field coverage, the `fn` fallback-to-scanned-name case).
- `ContactAdapterTest`/manual: verify the self-contact sorts first and shows
  "This is you" in the running app (no existing `ContactAdapter` test file
  today — check before adding one from scratch vs. extending).
