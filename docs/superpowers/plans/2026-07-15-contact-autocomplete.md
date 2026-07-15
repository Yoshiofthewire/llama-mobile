# Contact Autocomplete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user autocomplete recipients from the local contact database while composing an email — a debounced dropdown on TO/CC/BCC that tokenizes picks into removable pills, plus a full address-book picker modal with per-contact TO/CC/BCC action buttons.

**Architecture:** `ContactAutocomplete.md` is written as a generic web/desktop spec ("SQLite or IndexedDB", "modal or sliding panel") — this plan translates it onto this repo's actual stack: native Android, Kotlin, XML Views (no Compose), Room, Material Components. A new `RecipientInputView` compound view (AutoCompleteTextView + ChipGroup) replaces the single TO `EditText` in `ComposeActivity`, backed by a new `ContactDao.search` query. A new `AddressBookSheet` (`BottomSheetDialogFragment`) is the "modal/sidebar" from spec section 3. Both consume a shared, pure `RecipientCandidate` model and matching/validation functions so the two surfaces can't drift.

**Tech Stack:** Kotlin, Android XML Views, Room, kotlinx.serialization, Material Components 1.10.0 (`Chip`/`ChipGroup`/`BottomSheetDialogFragment`), `android.widget.AutoCompleteTextView`/`Filterable` (platform, no new dependency).

## Global Constraints

- **STYLE_GUIDE.md is currently deleted from the working tree** (`git status` shows `D STYLE_GUIDE.md`, unstaged) even though root `AGENTS.md` makes it binding reading for UI work. It still exists in git history (`git show HEAD:STYLE_GUIDE.md`). Before Task 5 (the first task with new visual chrome beyond chip reuse), run `git checkout -- STYLE_GUIDE.md` to restore it, or confirm with the user it was deleted intentionally. This plan already sources every color/shape rule it needs from that file's HEAD content, quoted inline below.
- **No Jetpack Compose.** This app is 100% classic Android Views. Do not introduce Compose dependencies or `@Composable` functions.
- **Reuse platform widgets over hand-rolling.** Use `android.widget.AutoCompleteTextView` + `Filterable` for the dropdown (built into the SDK, handles anchoring/dismiss/keyboard nav for free) instead of a hand-rolled `PopupWindow`. Use `com.google.android.material.bottomsheet.BottomSheetDialogFragment` for the modal (already a transitive dependency via `com.google.android.material:material:1.10.0`, and STYLE_GUIDE.md §6 explicitly prescribes it over a hand-rolled dialog). Use `android.util.Patterns.EMAIL_ADDRESS` for email validation instead of writing a custom RFC 5322 regex.
- **`to`/`cc`/`bcc` wire shape is comma-separated strings**, not arrays — see `MailDraft` (`app/src/main/java/com/urlxl/mail/mail/MailSource.kt:51-59`) and `RelayModels.kt:91-97`. Every new component must produce/consume that shape, not a `List<String>`, at the boundary.
- **Primary-email convention:** where a contact has multiple emails, this codebase always treats `emails.firstOrNull()` as "the" email for single-value UI (see `ContactEditActivity.kt:97`, `loadExisting`). Follow it here too — autocomplete and the address-book picker show/add only a contact's primary email, never a picker over multiple emails per contact. This is an explicit scope decision, not an oversight: `ponytail:` a contact whose matching email is its *second* email will still surface in results (the DAO query matches raw JSON, not just the primary value) but will display/add its *first* email instead. Upgrade path if this ever matters: fan `RecipientCandidate` out per-email instead of per-contact.
- **Room DAO/query tests are instrumented** (`app/src/androidTest`), run via `./gradlew connectedDebugAndroidTest` against a device/emulator — plain JVM unit tests under `app/src/test` can't provide real SQLite (documented precedent: `app/src/androidTest/java/com/urlxl/mail/data/MigrationTest.kt`). Pure Kotlin logic (no Room, no Android framework types) goes in `app/src/test` instead, run via `./gradlew testDebugUnitTest`.
- **DOX contract:** update `app/AGENTS.md` (or a closer child) after this feature lands, per root `AGENTS.md`'s "Update After Editing" rule — Task 6 covers this.
- **Ponytail:** smallest correct diff, reuse existing helpers (`applyPillChipTheme`, `getStoredThemePalette`, `bindAvatar`, `applyThemeToActivity`) rather than inventing new ones; the `@drawable/ic_person` vector already used for the bottom-nav Contacts tab is reused for the address-book icon rather than adding a new asset.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/urlxl/mail/data/ContactDao.kt` (modify) | Add `search(query): List<ContactEntity>` — the one SQL query both new UI surfaces share. |
| `app/src/main/java/com/urlxl/mail/contacts/RecipientMatching.kt` (create) | Pure, JVM-testable: `RecipientCandidate`, `RecipientField`, `toRecipientCandidateOrNull()`, `isDuplicateRecipient()`, `isValidEmailFormat()`, `matchRanges()`. |
| `app/src/main/java/com/urlxl/mail/RecipientInputView.kt` (create) | Compound view: chips + `AutoCompleteTextView` dropdown, debounce, manual-entry validation, duplicate toast. One instance per TO/CC/BCC field. |
| `app/src/main/res/layout/view_recipient_input.xml` (create) | Layout for `RecipientInputView`. |
| `app/src/main/res/layout/item_recipient_suggestion.xml` (create) | One dropdown row (bolded name + email, or "no contacts found"). |
| `app/src/main/java/com/urlxl/mail/ComposeActivity.kt` (modify) | Replace `toField: EditText` with three `RecipientInputView`s; wire `sendEmail()`, EXTRA_TO prefill, address-book launch. |
| `app/src/main/res/layout/activity_compose.xml` (modify) | Replace the single TO row with three `RecipientInputView` instances. |
| `app/src/main/java/com/urlxl/mail/contacts/AddressBookSheet.kt` (create) | `BottomSheetDialogFragment`: search bar + live contact list, TO/CC/BCC pick callback. |
| `app/src/main/java/com/urlxl/mail/contacts/RecipientRowAdapter.kt` (create) | RecyclerView adapter for the sheet's rows (Name/Email/Department + 3 action chips + checkmark state). |
| `app/src/main/res/layout/sheet_address_book.xml` (create) | Modal layout: search field + RecyclerView + empty state. |
| `app/src/main/res/layout/item_recipient_row.xml` (create) | One address-book row. |
| `app/src/main/java/com/urlxl/mail/AppTheme.kt` (modify) | Add `applySuccessChipTheme()` for the "added" checkmark state, mirroring the existing `applyDangerButtonTheme()` shape. |
| `app/src/main/res/values/strings.xml` (modify) | New strings for CC/BCC labels, toasts, address-book copy. |

---

### Task 1: Pure recipient-matching logic

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/contacts/RecipientMatching.kt`
- Test: `app/src/test/java/com/urlxl/mail/contacts/RecipientMatchingTest.kt`

**Interfaces:**
- Consumes: `com.urlxl.mail.data.ContactEntity` (existing), `ContactEntity.toDto()` (existing, `ContactMappers.kt`).
- Produces: `data class RecipientCandidate(val uid: String, val name: String, val email: String, val department: String? = null)`, `enum class RecipientField { TO, CC, BCC }`, `fun ContactEntity.toRecipientCandidateOrNull(): RecipientCandidate?`, `fun isDuplicateRecipient(existingEmails: List<String>, candidateEmail: String): Boolean`, `fun isValidEmailFormat(email: String): Boolean`, `fun matchRanges(text: String, query: String): List<IntRange>` — every later task in this plan calls these exact names.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.contacts

import com.urlxl.mail.data.ContactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientMatchingTest {

    @Test
    fun toRecipientCandidateOrNull_usesPrimaryEmailAndDepartment() {
        val entity = ContactEntity(
            uid = "1",
            rev = 1,
            fn = "Ada Lovelace",
            department = "Analytical Engines",
            emailsJson = """[{"value":"ada@example.com"},{"value":"ada2@example.com"}]""",
        )

        val candidate = entity.toRecipientCandidateOrNull()

        assertEquals(RecipientCandidate("1", "Ada Lovelace", "ada@example.com", "Analytical Engines"), candidate)
    }

    @Test
    fun toRecipientCandidateOrNull_returnsNullWhenNoEmail() {
        val entity = ContactEntity(uid = "1", rev = 1, fn = "No Email Guy", emailsJson = "[]")

        assertNull(entity.toRecipientCandidateOrNull())
    }

    @Test
    fun isDuplicateRecipient_matchesCaseInsensitively() {
        assertTrue(isDuplicateRecipient(listOf("Ada@Example.com"), "ada@example.com"))
        assertFalse(isDuplicateRecipient(listOf("bob@example.com"), "ada@example.com"))
        assertFalse(isDuplicateRecipient(emptyList(), "ada@example.com"))
    }

    @Test
    fun isValidEmailFormat_rejectsMalformedAddresses() {
        assertTrue(isValidEmailFormat("ada@example.com"))
        assertFalse(isValidEmailFormat("not-an-email"))
        assertFalse(isValidEmailFormat("ada@"))
        assertFalse(isValidEmailFormat(""))
    }

    @Test
    fun matchRanges_findsFirstCaseInsensitiveOccurrence() {
        assertEquals(listOf(0..1), matchRanges("Ada Lovelace", "ad"))
        assertEquals(listOf(4..12), matchRanges("Ada Lovelace", "Lovelace"))
        assertEquals(emptyList<IntRange>(), matchRanges("Ada Lovelace", "zz"))
        assertEquals(emptyList<IntRange>(), matchRanges("Ada Lovelace", ""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.contacts.RecipientMatchingTest"`
Expected: FAIL — `RecipientCandidate`/`toRecipientCandidateOrNull`/etc. are unresolved references (the file doesn't exist yet).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.contacts

import android.util.Patterns
import com.urlxl.mail.data.ContactEntity

/** Which composition field a picked contact should be appended to — shared between
 *  [com.urlxl.mail.RecipientInputView] (single field) and [AddressBookSheet] (offers all three
 *  per row). */
enum class RecipientField { TO, CC, BCC }

data class RecipientCandidate(
    val uid: String,
    val name: String,
    val email: String,
    val department: String? = null,
)

/** Picks the contact's primary (first) email — same convention [ContactEditActivity] uses for its
 *  single-email field (see `loadExisting`). Returns null for contacts with no email at all —
 *  nothing usable to autocomplete to. */
fun ContactEntity.toRecipientCandidateOrNull(): RecipientCandidate? {
    val dto = toDto()
    val email = dto.emails.firstOrNull()?.value?.takeIf { it.isNotBlank() } ?: return null
    return RecipientCandidate(uid = dto.uid, name = dto.fn, email = email, department = dto.department)
}

/** Case-insensitive duplicate check against a field's already-added recipient emails. */
fun isDuplicateRecipient(existingEmails: List<String>, candidateEmail: String): Boolean =
    existingEmails.any { it.equals(candidateEmail, ignoreCase = true) }

/** [Patterns.EMAIL_ADDRESS] is the platform's standard "close enough to RFC 5322" validator —
 *  prefer it over hand-rolling a regex (AGENTS.md: prefer stdlib/platform APIs). */
fun isValidEmailFormat(email: String): Boolean = Patterns.EMAIL_ADDRESS.matcher(email).matches()

/** Character range in [text] matching [query], case-insensitively — used to bold the matching
 *  substring in the autocomplete dropdown. Only the first occurrence is highlighted (dropdown rows
 *  are single-line; repeats aren't worth the extra spans). Empty when [query] is blank or absent. */
fun matchRanges(text: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    val index = text.indexOf(query, ignoreCase = true)
    if (index < 0) return emptyList()
    return listOf(index until (index + query.length))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.contacts.RecipientMatchingTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/RecipientMatching.kt app/src/test/java/com/urlxl/mail/contacts/RecipientMatchingTest.kt
git commit -m "feat: add pure recipient-matching logic for contact autocomplete"
```

---

### Task 2: `ContactDao.search` query

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/data/ContactDao.kt`
- Test: `app/src/androidTest/java/com/urlxl/mail/data/ContactDaoSearchTest.kt`

**Interfaces:**
- Consumes: `ContactEntity` (existing).
- Produces: `suspend fun ContactDao.search(query: String): List<ContactEntity>` — Task 3 and Task 5 both call this (Task 3 directly per-keystroke; Task 5 indirectly, see Task 5's design note on why it filters an already-observed list instead of re-querying).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactDaoSearchTest {

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
    fun search_matchesNameCaseInsensitively() = runBlocking {
        dao.upsertAll(
            listOf(
                ContactEntity(uid = "1", rev = 1, fn = "Ada Lovelace", emailsJson = """[{"value":"ada@example.com"}]"""),
                ContactEntity(uid = "2", rev = 1, fn = "Bob Smith", emailsJson = """[{"value":"bob@example.com"}]"""),
            ),
        )

        val results = dao.search("ada")

        assertEquals(1, results.size)
        assertEquals("Ada Lovelace", results.first().fn)
    }

    @Test
    fun search_matchesEmailAddress() = runBlocking {
        dao.upsertAll(
            listOf(ContactEntity(uid = "1", rev = 1, fn = "Ada Lovelace", emailsJson = """[{"value":"ada@example.com"}]""")),
        )

        val results = dao.search("example.com")

        assertEquals(1, results.size)
    }

    @Test
    fun search_excludesContactsWithNoEmail() = runBlocking {
        dao.upsertAll(listOf(ContactEntity(uid = "1", rev = 1, fn = "No Email Guy", emailsJson = "[]")))

        val results = dao.search("no email")

        assertTrue(results.isEmpty())
    }

    @Test
    fun search_ordersResultsByNameCaseInsensitive() = runBlocking {
        dao.upsertAll(
            listOf(
                ContactEntity(uid = "1", rev = 1, fn = "zack test", emailsJson = """[{"value":"zack@example.com"}]"""),
                ContactEntity(uid = "2", rev = 1, fn = "Amy test", emailsJson = """[{"value":"amy@example.com"}]"""),
            ),
        )

        val results = dao.search("test")

        assertEquals(listOf("Amy test", "zack test"), results.map { it.fn })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew connectedDebugAndroidTest --tests "com.urlxl.mail.data.ContactDaoSearchTest"` (requires a connected device/emulator)
Expected: FAIL to compile — `dao.search` is an unresolved reference.

- [ ] **Step 3: Add the query**

In `app/src/main/java/com/urlxl/mail/data/ContactDao.kt`, add alongside the existing queries:

```kotlin
    /** Name-or-email substring match for the contact-autocomplete feature (spec:
     *  ContactAutocomplete.md). LIKE is case-insensitive for ASCII in SQLite by default, so no
     *  explicit COLLATE NOCASE is needed on the LIKE itself. Matches against the raw
     *  [ContactEntity.emailsJson] string rather than decoding it — the email address appears
     *  verbatim inside the encoded JSON, so a substring match is correct without a JOIN/decode;
     *  see RecipientMatching.kt for why only the *primary* email is ever displayed even though
     *  this query can match on a secondary one. Contacts with no email at all
     *  (`emailsJson = '[]'`) are excluded — nothing to autocomplete to. */
    @Query(
        """
        SELECT * FROM contacts
        WHERE (fn LIKE '%' || :query || '%' OR emailsJson LIKE '%' || :query || '%')
          AND emailsJson != '[]'
        ORDER BY fn COLLATE NOCASE
        """,
    )
    suspend fun search(query: String): List<ContactEntity>
```

The full file's `@Dao interface ContactDao` now has five methods: `observeAll`, `getByUid`, `upsertAll`, `deleteByUids`, `clearAll`, plus this new `search`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew connectedDebugAndroidTest --tests "com.urlxl.mail.data.ContactDaoSearchTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/data/ContactDao.kt app/src/androidTest/java/com/urlxl/mail/data/ContactDaoSearchTest.kt
git commit -m "feat: add ContactDao.search for name/email substring lookup"
```

---

### Task 3: `RecipientInputView` (chips + autocomplete dropdown)

**Files:**
- Create: `app/src/main/res/layout/view_recipient_input.xml`
- Create: `app/src/main/res/layout/item_recipient_suggestion.xml`
- Create: `app/src/main/java/com/urlxl/mail/RecipientInputView.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `com.urlxl.mail.contacts.RecipientCandidate`, `isDuplicateRecipient`, `isValidEmailFormat`, `matchRanges` (Task 1); `applyPillChipTheme(context, chip)` (existing, `AppTheme.kt`).
- Produces: `class RecipientInputView(context, attrs)` with `fun setLabel(text: CharSequence)`, `fun configure(search: suspend (String) -> List<RecipientCandidate>, onOpenAddressBook: (() -> Unit)? = null)`, `fun setInitialRecipients(commaSeparated: String)`, `fun addRecipient(email: String, displayName: String? = null): Boolean`, `fun recipientEmails(): List<String>`, `fun commaJoinedRecipients(): String`, `fun applyTheme()` — Task 4 (ComposeActivity) and Task 5 (AddressBookSheet's pick callback target) both call these exact names.

This task has no automated test: it's UI orchestration glue (View lifecycle, `AutoCompleteTextView`/`Filter` wiring, chip rendering) with no precedent for Robolectric or similar in this repo (`ComposeActivity` itself, the closest analog, has zero unit tests — UI orchestration isn't unit-tested here; see `app/src/test/AGENTS.md`). The logic that *is* worth testing in isolation already has JVM tests from Task 1. Verify this task manually per Step 4 below.

- [ ] **Step 1: Add string resources**

In `app/src/main/res/values/strings.xml`, add near the existing `compose_*` strings:

```xml
    <string name="email_cc">Cc:</string>
    <string name="email_bcc">Bcc:</string>
    <string name="recipient_no_contacts_found">No contacts found</string>
    <string name="recipient_duplicate_toast">%1$s is already added</string>
    <string name="recipient_invalid_email_toast">Enter a valid email address</string>
    <string name="compose_address_book_content_description">Open address book</string>
```

- [ ] **Step 2: Create the row layouts**

`app/src/main/res/layout/view_recipient_input.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <TextView
            android:id="@+id/recipientInputLabel"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:gravity="center_vertical"
            android:layout_marginEnd="8dp" />

        <AutoCompleteTextView
            android:id="@+id/recipientInputField"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:inputType="textEmailAddress"
            android:imeOptions="actionDone"
            android:completionThreshold="1" />

        <ImageButton
            android:id="@+id/recipientInputBookButton"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:layout_marginStart="4dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@drawable/ic_person"
            android:contentDescription="@string/compose_address_book_content_description"
            android:visibility="gone" />

    </LinearLayout>

    <com.google.android.material.chip.ChipGroup
        android:id="@+id/recipientInputChips"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:visibility="gone"
        app:chipSpacingHorizontal="6dp" />

</LinearLayout>
```

`app/src/main/res/layout/item_recipient_suggestion.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="12dp">

    <TextView
        android:id="@+id/recipientSuggestionName"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="15sp" />

    <TextView
        android:id="@+id/recipientSuggestionEmail"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="13sp"
        android:layout_marginTop="2dp" />

</LinearLayout>
```

- [ ] **Step 3: Implement `RecipientInputView`**

```kotlin
package com.urlxl.mail

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.urlxl.mail.contacts.RecipientCandidate
import com.urlxl.mail.contacts.isDuplicateRecipient
import com.urlxl.mail.contacts.isValidEmailFormat
import com.urlxl.mail.contacts.matchRanges
import kotlinx.coroutines.runBlocking

/**
 * One TO/CC/BCC recipient field: an [AutoCompleteTextView] backed by a local-contact [Filter],
 * plus a [ChipGroup] of already-added recipient pills. ComposeActivity creates three instances.
 * Implements ContactAutocomplete.md sections 1, 2, and the "invalid formats"/"duplicate
 * prevention" parts of section 4 (the address-book modal itself is [com.urlxl.mail.contacts.AddressBookSheet]).
 */
class RecipientInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val labelView: TextView
    private val field: AutoCompleteTextView
    private val bookButton: View
    private val chipGroup: ChipGroup
    private val recipients = mutableListOf<String>()

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_recipient_input, this, true)
        labelView = findViewById(R.id.recipientInputLabel)
        field = findViewById(R.id.recipientInputField)
        bookButton = findViewById(R.id.recipientInputBookButton)
        chipGroup = findViewById(R.id.recipientInputChips)

        field.setOnItemClickListener { _, _, position, _ ->
            (field.adapter as? SuggestionAdapter)?.getCandidateAt(position)?.let {
                addRecipient(it.email, it.name)
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitTypedEmail()
                true
            } else {
                false
            }
        }
        field.doAfterTextChanged { text ->
            if (text != null && (text.endsWith(",") || text.endsWith(" "))) {
                commitTypedEmail()
            }
        }
    }

    fun setLabel(text: CharSequence) {
        labelView.text = text
    }

    /** Wires local-contact search into the dropdown. Pass [onOpenAddressBook] on exactly one of
     *  the three TO/CC/BCC instances (ComposeActivity uses the TO row) — the address-book modal
     *  itself offers TO/CC/BCC actions per contact, so a single entry point covers all three
     *  fields; showing the icon on every field would just be three doors to the same room. */
    fun configure(search: suspend (String) -> List<RecipientCandidate>, onOpenAddressBook: (() -> Unit)? = null) {
        field.setAdapter(SuggestionAdapter(context, search))
        if (onOpenAddressBook != null) {
            bookButton.visibility = View.VISIBLE
            bookButton.setOnClickListener { onOpenAddressBook() }
        }
    }

    /** Parses a comma-separated address string (matches [com.urlxl.mail.mail.MailDraft]'s wire
     *  shape) into chips — used to prefill from ComposeActivity.EXTRA_TO on reply/forward. */
    fun setInitialRecipients(commaSeparated: String) {
        commaSeparated.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { addRecipient(it) }
    }

    /** Adds [email] as a chip if it isn't already present in this field. Returns false (and shows
     *  a duplicate toast) otherwise — [com.urlxl.mail.contacts.AddressBookSheet] uses the return
     *  value to decide whether to flip its per-row checkmark. */
    fun addRecipient(email: String, displayName: String? = null): Boolean {
        if (isDuplicateRecipient(recipients, email)) {
            Toast.makeText(context, context.getString(R.string.recipient_duplicate_toast, email), Toast.LENGTH_SHORT).show()
            return false
        }
        recipients.add(email)
        val chip = Chip(context).apply {
            text = displayName?.takeIf { it.isNotBlank() } ?: email
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                recipients.remove(email)
                chipGroup.removeView(this)
                chipGroup.visibility = if (chipGroup.childCount == 0) View.GONE else View.VISIBLE
            }
        }
        applyPillChipTheme(context, chip)
        chipGroup.addView(chip)
        chipGroup.visibility = View.VISIBLE
        field.setText("")
        field.dismissDropDown()
        return true
    }

    fun recipientEmails(): List<String> = recipients.toList()

    /** Matches [com.urlxl.mail.mail.MailDraft]'s to/cc/bcc wire shape. */
    fun commaJoinedRecipients(): String = recipients.joinToString(",")

    /** Re-tints existing chips after a theme switch — call from the host Activity's onResume,
     *  alongside its other applyXTheme() calls. */
    fun applyTheme() {
        for (i in 0 until chipGroup.childCount) {
            (chipGroup.getChildAt(i) as? Chip)?.let { applyPillChipTheme(context, it) }
        }
    }

    private fun commitTypedEmail() {
        val typed = field.text.toString().trim(' ', ',')
        if (typed.isBlank()) return
        if (!isValidEmailFormat(typed)) {
            Toast.makeText(context, R.string.recipient_invalid_email_toast, Toast.LENGTH_SHORT).show()
            return
        }
        addRecipient(typed)
    }

    /** [Filterable] adapter backing the dropdown. [Filter.performFiltering] already runs on a
     *  dedicated background thread that [Filter] itself serializes one call at a time — blocking
     *  there via [runBlocking] is safe and mirrors this app's existing "blocking call off a
     *  background thread" convention (e.g. ComposeActivity.sendEmail's ioExecutor usage) rather
     *  than threading coroutines through this view. [Thread.sleep] before querying gives the
     *  150ms debounce ContactAutocomplete.md asks for; [publishResults] then drops stale results
     *  by comparing its constraint against the field's *current* text, so a fast typist never sees
     *  an older query's results clobber a newer one. */
    private inner class SuggestionAdapter(
        context: Context,
        private val search: suspend (String) -> List<RecipientCandidate>,
    ) : BaseAdapter(), Filterable {

        private var results: List<RecipientCandidate> = emptyList()
        private var lastQuery: String = ""
        private val inflater = LayoutInflater.from(context)

        fun getCandidateAt(position: Int): RecipientCandidate? = results.getOrNull(position)

        override fun getCount(): Int = if (results.isEmpty() && lastQuery.isNotBlank()) 1 else results.size

        override fun getItem(position: Int): Any? = results.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_recipient_suggestion, parent, false)
            val nameView = view.findViewById<TextView>(R.id.recipientSuggestionName)
            val emailView = view.findViewById<TextView>(R.id.recipientSuggestionEmail)
            val candidate = results.getOrNull(position)
            if (candidate == null) {
                nameView.text = context.getString(R.string.recipient_no_contacts_found)
                emailView.text = ""
            } else {
                nameView.text = bolded(candidate.name, lastQuery)
                emailView.text = bolded(candidate.email, lastQuery)
            }
            return view
        }

        private fun bolded(text: String, query: String): CharSequence {
            val span = SpannableString(text)
            matchRanges(text, query).forEach { range ->
                span.setSpan(StyleSpan(Typeface.BOLD), range.first, range.last + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return span
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString().orEmpty()
                Thread.sleep(DEBOUNCE_MS)
                val matches = if (query.isBlank()) emptyList() else runBlocking { search(query) }.take(MAX_RESULTS)
                return FilterResults().apply {
                    values = query to matches
                    count = 1
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, filterResults: FilterResults?) {
                val (query, matches) = filterResults?.values as? Pair<String, List<RecipientCandidate>> ?: return
                if (field.text.toString() != query) return
                lastQuery = query
                results = matches
                notifyDataSetChanged()
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val MAX_RESULTS = 5
    }
}
```

- [ ] **Step 4: Manual verification**

Build and install a debug APK, open Compose from the inbox, and check:
1. Typing 1+ characters of a known contact's name (or email) into the TO field shows a dropdown within ~150ms, capped at 5 rows, with the matching substring bolded in both the name and email lines.
2. Typing a query with no matches shows a single "No contacts found" row instead of an empty/blank dropdown.
3. Tapping a suggestion converts it into a removable chip; the field clears and the dropdown closes.
4. Typing a full, well-formed email address not in contacts, then a trailing comma or space, commits it as a chip too.
5. Typing an invalid string (e.g. `notanemail`) followed by a comma shows the invalid-format toast and does *not* create a chip.
6. Adding the same email twice (once via suggestion, once via manual typing) shows the duplicate toast on the second attempt and does not create a second chip.
7. Tapping a chip's "X" removes it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/view_recipient_input.xml app/src/main/res/layout/item_recipient_suggestion.xml app/src/main/java/com/urlxl/mail/RecipientInputView.kt app/src/main/res/values/strings.xml
git commit -m "feat: add RecipientInputView with debounced contact autocomplete"
```

---

### Task 4: Wire `RecipientInputView` into `ComposeActivity`

**Files:**
- Modify: `app/src/main/res/layout/activity_compose.xml`
- Modify: `app/src/main/java/com/urlxl/mail/ComposeActivity.kt`

**Interfaces:**
- Consumes: `RecipientInputView` (Task 3), `ContactDao.search` (Task 2), `ContactEntity.toRecipientCandidateOrNull()` (Task 1), `RecipientField`/`RecipientCandidate` (Task 1), `com.urlxl.mail.contacts.AddressBookSheet` (Task 5 — this task only wires the *call site*; Task 5 creates the class).
- Produces: nothing new consumed elsewhere.

- [ ] **Step 1: Replace the TO row in the layout**

In `app/src/main/res/layout/activity_compose.xml`, replace this block (currently the first `LinearLayout` child of `composeRoot`):

```xml
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginBottom="12dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/email_to"
            android:textSize="12sp"
            android:gravity="center_vertical"
            android:layout_marginEnd="8dp" />

        <EditText
            android:id="@+id/composeToField"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="recipient@example.com"
            android:inputType="textEmailAddress" />

    </LinearLayout>
```

with:

```xml
    <com.urlxl.mail.RecipientInputView
        android:id="@+id/composeToInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginBottom="8dp" />

    <com.urlxl.mail.RecipientInputView
        android:id="@+id/composeCcInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginBottom="8dp" />

    <com.urlxl.mail.RecipientInputView
        android:id="@+id/composeBccInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginBottom="12dp" />
```

- [ ] **Step 2: Update `ComposeActivity.kt` field declarations and `onCreate`**

Replace `private lateinit var toField: EditText` with:

```kotlin
    private lateinit var toInput: RecipientInputView
    private lateinit var ccInput: RecipientInputView
    private lateinit var bccInput: RecipientInputView
```

Add imports:

```kotlin
import com.urlxl.mail.contacts.AddressBookSheet
import com.urlxl.mail.contacts.RecipientField
import com.urlxl.mail.contacts.toRecipientCandidateOrNull
import com.urlxl.mail.data.DataRuntime
```

Replace the `toField = findViewById(R.id.composeToField)` line and the `toField.setText(...)` line with:

```kotlin
        toInput = findViewById(R.id.composeToInput)
        ccInput = findViewById(R.id.composeCcInput)
        bccInput = findViewById(R.id.composeBccInput)
        toInput.setLabel(getString(R.string.email_to))
        ccInput.setLabel(getString(R.string.email_cc))
        bccInput.setLabel(getString(R.string.email_bcc))

        val contactDao = DataRuntime.graph(this).database.contactDao()
        val searchContacts: suspend (String) -> List<com.urlxl.mail.contacts.RecipientCandidate> = { query ->
            contactDao.search(query).mapNotNull { it.toRecipientCandidateOrNull() }
        }
        toInput.configure(searchContacts, onOpenAddressBook = ::openAddressBook)
        ccInput.configure(searchContacts)
        bccInput.configure(searchContacts)

        toInput.setInitialRecipients(intent.getStringExtra(EXTRA_TO).orEmpty())
```

(This replaces the old `toField.setText(intent.getStringExtra(EXTRA_TO).orEmpty())` line — keep it in the same relative position, after `subjectField.setText(...)`.)

- [ ] **Step 3: Add the address-book launch function**

Add this private function to `ComposeActivity`:

```kotlin
    private fun openAddressBook() {
        AddressBookSheet { candidate, field ->
            val target = when (field) {
                RecipientField.TO -> toInput
                RecipientField.CC -> ccInput
                RecipientField.BCC -> bccInput
            }
            target.addRecipient(candidate.email, candidate.name)
        }.show(supportFragmentManager, AddressBookSheet.TAG)
    }
```

- [ ] **Step 4: Update `onResume()` to re-theme the new views**

In `onResume()`, alongside the existing `applyPrimaryButtonTheme`/`applyGhostButtonTheme`/`applyToolbarChipsTheme` calls, add:

```kotlin
        toInput.applyTheme()
        ccInput.applyTheme()
        bccInput.applyTheme()
```

- [ ] **Step 5: Update `sendEmail()`**

Replace:

```kotlin
    private fun sendEmail() {
        val to = toField.text.toString().trim()
        val subject = subjectField.text.toString().trim()
        val isBodyEmpty = bodyEditor.isEmptyFlow.value != false

        if (to.isBlank() || subject.isBlank() || isBodyEmpty) {
```

with:

```kotlin
    private fun sendEmail() {
        val to = toInput.commaJoinedRecipients()
        val cc = ccInput.commaJoinedRecipients()
        val bcc = bccInput.commaJoinedRecipients()
        val subject = subjectField.text.toString().trim()
        val isBodyEmpty = bodyEditor.isEmptyFlow.value != false

        if (to.isBlank() || subject.isBlank() || isBodyEmpty) {
```

And update the `MailDraft` construction further down:

```kotlin
                val outcome = MailRuntime.graph(this).repository.send(
                    MailDraft(to = to, cc = cc, bcc = bcc, subject = subject, body = html, mode = "html", attachments = attachments.toList()),
                )
```

- [ ] **Step 6: Manual verification**

Build and run. Confirm:
1. Opening Compose fresh shows three rows: To, Cc, Bcc, each with its own address-book icon slot (only To's is visible).
2. Replying to an email (via `EmailDetailActivity`'s reply flow) still prefills the To field as a chip.
3. Sending an email with only TO filled works exactly as before.
4. Sending an email with CC and/or BCC recipients added actually delivers to those addresses (check via the receiving inbox, or inspect the relay request if a proxy/logging is available).
5. Sending with an empty TO field still blocks with "Please fill in all fields", matching prior behavior.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/layout/activity_compose.xml app/src/main/java/com/urlxl/mail/ComposeActivity.kt
git commit -m "feat: wire TO/CC/BCC recipient autocomplete into ComposeActivity"
```

---

### Task 5: `AddressBookSheet` modal picker

**Files:**
- Create: `app/src/main/res/layout/sheet_address_book.xml`
- Create: `app/src/main/res/layout/item_recipient_row.xml`
- Create: `app/src/main/java/com/urlxl/mail/contacts/RecipientRowAdapter.kt`
- Create: `app/src/main/java/com/urlxl/mail/contacts/AddressBookSheet.kt`
- Modify: `app/src/main/java/com/urlxl/mail/AppTheme.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `RecipientCandidate`, `RecipientField`, `ContactEntity.toRecipientCandidateOrNull()` (Task 1); `DataRuntime.graph(context).database.contactDao()` → `.observeAll()` (existing); `getStoredThemePalette`, `applyPillChipTheme`, `COLOR_SUCCESS_BORDER`, `COLOR_SUCCESS_TEXT`, `withAlpha` (existing, `AppTheme.kt` — `withAlpha` is `internal`, same Gradle module, so visible here).
- Produces: `class AddressBookSheet(onPick: (RecipientCandidate, RecipientField) -> Boolean) : BottomSheetDialogFragment()` with `companion object { const val TAG }`; `fun applySuccessChipTheme(context: Context, chip: Chip)` on `AppTheme.kt` — Task 4 already calls `AddressBookSheet(...).show(...)` and `AddressBookSheet.TAG`.

**Design note:** unlike `RecipientInputView`'s dropdown (a one-shot `ContactDao.search()` suspend call per keystroke), this sheet keeps `contactDao.observeAll()` subscribed for as long as it's open and filters the resulting list in Kotlin. This is deliberate, not an inconsistency with Task 2: the sheet is a persistent, potentially long-lived surface (spec: "keep the interface open for multi-selection") that should reflect a contact-sync update landing while it's open, exactly like `ContactsListActivity` already does with the same `observeContacts()`/`observeAll()` pattern. A transient dropdown has no such requirement.

No automated test for this task either, for the same reason as Task 3 (UI orchestration, no Robolectric in this repo) — `RecipientRowAdapter`'s checkmark bookkeeping is simple enough that adding a test harness just for it isn't worth a new test-infra pattern this repo doesn't otherwise use. Verify manually per Step 5.

- [ ] **Step 1: Add remaining string resources**

In `app/src/main/res/values/strings.xml`:

```xml
    <string name="address_book_search_hint">Search contacts</string>
    <string name="address_book_action_to">TO</string>
    <string name="address_book_action_cc">CC</string>
    <string name="address_book_action_bcc">BCC</string>
```

- [ ] **Step 2: Add `applySuccessChipTheme` to `AppTheme.kt`**

Add this function near the existing `applyDangerButtonTheme` (it mirrors the same stroke+fill shape, using the success colors instead):

```kotlin
/** Success/"added" state for the address-book picker's TO/CC/BCC action chips — mirrors
 *  [applyDangerButtonTheme]'s stroke+fill shape (STYLE_GUIDE.md §4's danger-button pattern is the
 *  closest documented precedent for a colored actionable state) using [COLOR_SUCCESS_BORDER]/
 *  [COLOR_SUCCESS_TEXT] (STYLE_GUIDE.md §1) instead of the danger palette. */
fun applySuccessChipTheme(context: Context, chip: com.google.android.material.chip.Chip) {
    val border = Color.parseColor(COLOR_SUCCESS_BORDER)
    chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(withAlpha(border, 0.12f))
    chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(border)
    chip.chipStrokeWidth = 1f * context.resources.displayMetrics.density
    chip.setTextColor(Color.parseColor(COLOR_SUCCESS_TEXT))
}
```

- [ ] **Step 3: Create the layouts**

`app/src/main/res/layout/sheet_address_book.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <EditText
        android:id="@+id/addressBookSearchField"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/address_book_search_hint"
        android:inputType="text"
        android:layout_marginBottom="12dp" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/addressBookRecyclerView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <TextView
        android:id="@+id/addressBookEmptyText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/recipient_no_contacts_found"
        android:gravity="center"
        android:padding="24dp"
        android:visibility="gone" />

</LinearLayout>
```

`app/src/main/res/layout/item_recipient_row.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="6dp"
    app:cardCornerRadius="14dp"
    app:cardElevation="2dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <TextView
            android:id="@+id/recipientRowName"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/recipientRowEmail"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="13sp"
            android:layout_marginTop="2dp" />

        <TextView
            android:id="@+id/recipientRowDepartment"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:layout_marginTop="2dp" />

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="8dp">

            <com.google.android.material.chip.Chip
                android:id="@+id/recipientRowToButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/address_book_action_to"
                android:layout_marginEnd="6dp" />

            <com.google.android.material.chip.Chip
                android:id="@+id/recipientRowCcButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/address_book_action_cc"
                android:layout_marginEnd="6dp" />

            <com.google.android.material.chip.Chip
                android:id="@+id/recipientRowBccButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/address_book_action_bcc" />

        </LinearLayout>

    </LinearLayout>

</androidx.cardview.widget.CardView>
```

- [ ] **Step 4: Implement `RecipientRowAdapter` and `AddressBookSheet`**

`app/src/main/java/com/urlxl/mail/contacts/RecipientRowAdapter.kt`:

```kotlin
package com.urlxl.mail.contacts

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.urlxl.mail.R
import com.urlxl.mail.applyPillChipTheme
import com.urlxl.mail.applySuccessChipTheme
import com.urlxl.mail.getStoredThemePalette

/** [onPick] returns whether the pick actually landed (false = duplicate, per
 *  [com.urlxl.mail.RecipientInputView.addRecipient]) — only a true result flips that row/field to
 *  its checkmark state. */
class RecipientRowAdapter(
    private var candidates: List<RecipientCandidate> = emptyList(),
    private val onPick: (RecipientCandidate, RecipientField) -> Boolean,
) : RecyclerView.Adapter<RecipientRowAdapter.RowViewHolder>() {

    private val added = mutableSetOf<Pair<String, RecipientField>>()

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view as CardView
        val name: TextView = view.findViewById(R.id.recipientRowName)
        val email: TextView = view.findViewById(R.id.recipientRowEmail)
        val department: TextView = view.findViewById(R.id.recipientRowDepartment)
        val toButton: Chip = view.findViewById(R.id.recipientRowToButton)
        val ccButton: Chip = view.findViewById(R.id.recipientRowCcButton)
        val bccButton: Chip = view.findViewById(R.id.recipientRowBccButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recipient_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val candidate = candidates[position]
        val palette = getStoredThemePalette(holder.itemView.context)
        holder.card.setCardBackgroundColor(Color.parseColor(palette.panel))
        holder.name.text = candidate.name
        holder.name.setTextColor(Color.parseColor(palette.inkStrong))
        holder.email.text = candidate.email
        holder.email.setTextColor(Color.parseColor(palette.ink))
        holder.department.text = candidate.department.orEmpty()
        holder.department.visibility = if (candidate.department.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.department.setTextColor(Color.parseColor(palette.ink))

        bindActionButton(holder.toButton, candidate, RecipientField.TO)
        bindActionButton(holder.ccButton, candidate, RecipientField.CC)
        bindActionButton(holder.bccButton, candidate, RecipientField.BCC)
    }

    private fun bindActionButton(chip: Chip, candidate: RecipientCandidate, field: RecipientField) {
        if ((candidate.uid to field) in added) {
            applySuccessChipTheme(chip.context, chip)
        } else {
            applyPillChipTheme(chip.context, chip)
        }
        chip.setOnClickListener {
            if (onPick(candidate, field)) {
                added.add(candidate.uid to field)
                notifyItemChanged(candidates.indexOf(candidate))
            }
        }
    }

    fun submitList(newCandidates: List<RecipientCandidate>) {
        candidates = newCandidates
        notifyDataSetChanged()
    }
}
```

`app/src/main/java/com/urlxl/mail/contacts/AddressBookSheet.kt`:

```kotlin
package com.urlxl.mail.contacts

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.urlxl.mail.R
import com.urlxl.mail.data.DataRuntime
import kotlinx.coroutines.launch

/** Address-book picker (ContactAutocomplete.md section 3): search bar + scrollable contact list
 *  with TO/CC/BCC action chips per row. Stays open across picks so the user can multi-select —
 *  [onPick] fires once per successful pick; see [RecipientRowAdapter] for the checkmark state. */
class AddressBookSheet(
    private val onPick: (RecipientCandidate, RecipientField) -> Boolean,
) : BottomSheetDialogFragment() {

    private lateinit var adapter: RecipientRowAdapter
    private lateinit var emptyText: View
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var allCandidates: List<RecipientCandidate> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_address_book, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val searchField = view.findViewById<EditText>(R.id.addressBookSearchField)
        val recyclerView = view.findViewById<RecyclerView>(R.id.addressBookRecyclerView)
        emptyText = view.findViewById(R.id.addressBookEmptyText)

        adapter = RecipientRowAdapter(onPick = onPick)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        searchField.doAfterTextChanged { text ->
            pendingSearch?.let(handler::removeCallbacks)
            val query = text?.toString().orEmpty()
            val runnable = Runnable { render(filter(query)) }
            pendingSearch = runnable
            handler.postDelayed(runnable, DEBOUNCE_MS)
        }

        val contactDao = DataRuntime.graph(requireContext()).database.contactDao()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                contactDao.observeAll().collect { contacts ->
                    allCandidates = contacts.mapNotNull { it.toRecipientCandidateOrNull() }
                    render(filter(searchField.text?.toString().orEmpty()))
                }
            }
        }
    }

    private fun filter(query: String): List<RecipientCandidate> = if (query.isBlank()) {
        allCandidates
    } else {
        allCandidates.filter { it.name.contains(query, ignoreCase = true) || it.email.contains(query, ignoreCase = true) }
    }

    private fun render(list: List<RecipientCandidate>) {
        adapter.submitList(list)
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingSearch?.let(handler::removeCallbacks)
    }

    companion object {
        const val TAG = "AddressBookSheet"
        private const val DEBOUNCE_MS = 150L
    }
}
```

- [ ] **Step 5: Manual verification**

Build and run. From Compose, tap the address-book icon next to To and confirm:
1. The sheet opens showing every contact that has an email, with Name/Email/Department and three TO/CC/BCC chips per row.
2. Typing in the search bar filters the list (name or email match) after a short debounce.
3. Tapping a row's TO chip adds that contact to the To field as a chip in ComposeActivity behind the sheet, and the TO chip in the sheet switches to the success (checkmark-style) color.
4. Tapping the same row's TO chip again shows the duplicate toast (from `RecipientInputView.addRecipient`) and does not add a second chip.
5. Tapping CC then BCC on the same row adds it to those fields too, independently.
6. The sheet stays open after each pick (no auto-dismiss).
7. Closing the sheet and reopening it resets the checkmark state (session-only, as designed).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/layout/sheet_address_book.xml app/src/main/res/layout/item_recipient_row.xml app/src/main/java/com/urlxl/mail/contacts/RecipientRowAdapter.kt app/src/main/java/com/urlxl/mail/contacts/AddressBookSheet.kt app/src/main/java/com/urlxl/mail/AppTheme.kt app/src/main/res/values/strings.xml
git commit -m "feat: add AddressBookSheet modal contact picker"
```

---

### Task 6: DOX update and full spec verification pass

**Files:**
- Modify: `app/AGENTS.md`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new (documentation-only, plus a final cross-cutting manual pass).

- [ ] **Step 1: Update `app/AGENTS.md`**

Add a bullet under "Local Contracts" (after the existing contact-sync bullet):

```markdown
- Contact autocomplete (ContactAutocomplete.md): `ComposeActivity`'s TO/CC/BCC fields are
  `RecipientInputView`s backed by `ContactDao.search` (name/email substring match, debounced
  150ms, top 5 shown). The address-book icon on the TO row opens `AddressBookSheet`
  (`contacts/` package), a `BottomSheetDialogFragment` offering TO/CC/BCC actions per contact.
  Both surfaces share `RecipientCandidate`/`RecipientField`/matching logic in
  `contacts/RecipientMatching.kt` — extend that file, don't duplicate matching logic in either UI
  layer.
```

- [ ] **Step 2: Run the full existing test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS, including the new `RecipientMatchingTest` and every pre-existing test (confirms nothing in `ComposeActivity`/`ContactDao` changes broke an existing unit test — e.g. `ContactMappersTest`, `MailDraft`-related tests if any).

Run: `./gradlew connectedDebugAndroidTest` (device/emulator required)
Expected: PASS, including the new `ContactDaoSearchTest` and the pre-existing `MigrationTest`.

- [ ] **Step 3: Full manual pass against ContactAutocomplete.md section 4 (edge cases)**

Confirm each, end to end in the running app (these were spot-checked per-task above; this is the final cross-cutting pass with all pieces integrated):
1. No results found → dropdown shows "No contacts found", not blank.
2. Duplicate selection (via dropdown, via manual typing, and via the address-book sheet, in any combination) → toast, no duplicate chip, in every field independently (adding the same contact to TO and CC is *not* a duplicate — only same-field duplicates are blocked).
3. Manually typed custom email not in contacts → added as a chip provided it passes `Patterns.EMAIL_ADDRESS`; rejected with a toast otherwise.
4. Recipients survive through to the actual sent email: compose a message with one autocompleted TO, one manually-typed CC, and one address-book-picked BCC, send it, and confirm all three arrive.

- [ ] **Step 4: Commit**

```bash
git add app/AGENTS.md
git commit -m "docs: document contact autocomplete in app/AGENTS.md DOX"
```

---

## Self-Review Notes

- **Spec coverage:** §1 (search engine) → Task 2 (DAO) + Task 3 (debounce/limit). §2 (dropdown UI) → Task 3 (bold spans, keyboard nav via `AutoCompleteTextView`'s native DPAD/Enter handling, tokenization). §3 (modal) → Task 5. §4 (edge cases) → Task 3 Step 4 items 2/5/6 + Task 5 Step 5 items 1/4 + Task 6 Step 3 (integrated pass).
- One spec line not literally implemented: explicit "Tab to confirm" — `AutoCompleteTextView` confirms on Enter (`IME_ACTION_DONE`) and on tap/DPAD-select natively; stock Android soft keyboards don't emit `KEYCODE_TAB`, so this only matters for hardware-keyboard users, and `actionDone` plus comma/space-to-commit (Task 3) covers the same intent. Noted here rather than silently dropped — revisit with an explicit `KEYCODE_TAB` handler on `field` if a hardware-keyboard user actually hits this.
