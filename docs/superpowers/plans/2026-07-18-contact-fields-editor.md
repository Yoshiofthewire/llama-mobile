# Contact Fields Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `ContactEditActivity` to display and edit every contact field the backend supports (except `photoRef` and `groupIDs`, deferred; `isSelf`/`pgpKey` stay read-only), organized into collapsible sections.

**Architecture:** Two new reusable, contact-agnostic UI primitives — `ExpandableSectionView` (collapsible header+body container) and `RepeatableFieldList<T>` (generic add/remove row list) — get composed once per section in a rewritten `activity_contact_edit.xml` and wired up in `ContactEditActivity`. `mergedContactDto()` (already extracted in a prior fix) grows to cover every newly-editable field, still `.copy()`-ing off the loaded contact so untouched fields survive.

**Tech Stack:** Kotlin, Android Views (no Compose in this codebase), Room (existing), kotlinx.serialization DTOs (existing), JUnit4 + AndroidJUnit4 instrumented tests (existing pattern, run via `./gradlew :app:connectedDebugAndroidTest`).

## Global Constraints

- Only `fn` is required; every other field stays optional (per `Mobile_Contact_Sync.md`'s field table — no new required-field validation).
- `photoRef` and `groupIDs` are out of scope — must remain untouched by every change in this plan (protected automatically via `mergedContactDto`'s `.copy()`).
- `isSelf` and `pgpKey` are read-only in this screen — no edit controls, ever.
- A list row counts as "blank" (dropped on save) only if *every* one of its sub-fields is blank.
- No manual date text entry — birthday/event dates go through `DatePickerDialog` only, formatted `yyyy-MM-dd`.
- Follow this codebase's existing conventions: plain Android Views (no Compose), string resources for all user-facing text (no hardcoded UI strings in Kotlin), `applyThemeToActivity`/`applyPrimaryButtonTheme`/`applyDangerButtonTheme`/`applyStatusBadgeTheme` for theming (never hardcode colors).

---

### Task 1: `RepeatableFieldList<T>` generic component

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/contacts/RepeatableFieldList.kt`
- Create: `app/src/main/res/layout/row_contact_two_field.xml`
- Create: `app/src/main/res/values/strings.xml` (add `contacts_field_remove_cd`, `contacts_row_a_hint`, `contacts_row_b_hint` — the latter two are placeholder hints used only by this task's test row; real per-section hints are set in Tasks 7/9/10/11)
- Test: `app/src/androidTest/java/com/urlxl/mail/contacts/RepeatableFieldListTest.kt`

**Interfaces:**
- Produces: `RepeatableFieldList<T>(container: ViewGroup, addButton: Button, rowLayoutRes: Int, removeButtonId: Int, bind: (rowView: View, item: T, onItemChanged: (T) -> Unit) -> Unit, isBlank: (T) -> Boolean, default: () -> T, onChanged: () -> Unit = {})` with methods `fun setItems(items: List<T>)` and `fun items(): List<T>`. `removeButtonId` is a constructor param (not hardcoded to one id) from the start, since later tasks' row layouts (addresses, IMs, events) each need their own differently-named remove-button id alongside their other differently-named fields.
- Produces: `row_contact_two_field.xml` with ids `rowFieldA` (EditText), `rowFieldB` (EditText), `rowFieldRemove` (TextView, tappable).

- [ ] **Step 1: Add the row layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- app/src/main/res/layout/row_contact_two_field.xml -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:layout_marginBottom="8dp">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <EditText
            android:id="@+id/rowFieldA"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="4dp" />

        <EditText
            android:id="@+id/rowFieldB"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

    </LinearLayout>

    <TextView
        android:id="@+id/rowFieldRemove"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:layout_marginStart="8dp"
        android:gravity="center"
        android:text="✕"
        android:textSize="18sp"
        android:clickable="true"
        android:focusable="true"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/contacts_field_remove_cd" />

</LinearLayout>
```

- [ ] **Step 2: Add supporting strings**

In `app/src/main/res/values/strings.xml`, add these lines immediately after the existing `contacts_notes_label` entry (line 121):

```xml
    <string name="contacts_field_remove_cd">Remove</string>
    <string name="contacts_row_a_hint">A</string>
    <string name="contacts_row_b_hint">B</string>
```

- [ ] **Step 3: Write `RepeatableFieldList`**

```kotlin
// app/src/main/java/com/urlxl/mail/contacts/RepeatableFieldList.kt
package com.urlxl.mail.contacts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button

/**
 * Manages the "rows + Add button" pattern for one list-typed contact field inside an
 * [ExpandableSectionView]'s body. Each row is inflated from [rowLayoutRes] and wired by [bind];
 * [isBlank] decides which rows [items] drops (e.g. an "+Add" tapped but left empty); [default] is
 * what a fresh row starts as. [onChanged] fires after every add/remove/edit so callers can keep an
 * item-count badge live. Removal and edits look up the row's *current* index via
 * `container.indexOfChild(rowView)` rather than capturing a fixed index at add-time, so earlier
 * rows being removed doesn't corrupt later rows' bookkeeping. Purely a layout primitive — knows
 * nothing about contact fields; every DTO-specific mapping lives in [bind]/[isBlank]/[default].
 */
class RepeatableFieldList<T>(
    private val container: ViewGroup,
    addButton: Button,
    private val rowLayoutRes: Int,
    private val removeButtonId: Int,
    private val bind: (rowView: View, item: T, onItemChanged: (T) -> Unit) -> Unit,
    private val isBlank: (T) -> Boolean,
    private val default: () -> T,
    private val onChanged: () -> Unit = {},
) {
    private val rows = mutableListOf<T>()

    init {
        addButton.setOnClickListener { addRow(default()) }
    }

    fun setItems(items: List<T>) {
        container.removeAllViews()
        rows.clear()
        items.forEach { addRow(it) }
    }

    fun items(): List<T> = rows.filterNot(isBlank)

    private fun addRow(item: T) {
        rows.add(item)
        val rowView = LayoutInflater.from(container.context).inflate(rowLayoutRes, container, false)
        val removeButton = rowView.findViewById<View>(removeButtonId)
        removeButton.setOnClickListener {
            val index = container.indexOfChild(rowView)
            if (index >= 0) {
                rows.removeAt(index)
                container.removeViewAt(index)
                onChanged()
            }
        }
        bind(rowView, item) { updated ->
            val index = container.indexOfChild(rowView)
            if (index >= 0) rows[index] = updated
            onChanged()
        }
        container.addView(rowView)
        onChanged()
    }
}
```

- [ ] **Step 4: Write the instrumented test**

```kotlin
// app/src/androidTest/java/com/urlxl/mail/contacts/RepeatableFieldListTest.kt
package com.urlxl.mail.contacts

import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepeatableFieldListTest {

    private lateinit var container: LinearLayout
    private lateinit var addButton: Button
    private var changeCount = 0

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        container = LinearLayout(context)
        addButton = Button(context)
        changeCount = 0
    }

    private fun newList(): RepeatableFieldList<ContactFieldDto> = RepeatableFieldList(
        container = container,
        addButton = addButton,
        rowLayoutRes = R.layout.row_contact_two_field,
        removeButtonId = R.id.rowFieldRemove,
        bind = { rowView, item, onItemChanged ->
            val a = rowView.findViewById<EditText>(R.id.rowFieldA)
            val b = rowView.findViewById<EditText>(R.id.rowFieldB)
            a.setText(item.label.orEmpty())
            b.setText(item.value)
            a.doAfterTextChanged { onItemChanged(item.copy(label = a.text.toString().ifBlank { null })) }
            b.doAfterTextChanged { onItemChanged(item.copy(value = b.text.toString())) }
        },
        isBlank = { it.label.isNullOrBlank() && it.value.isBlank() },
        default = { ContactFieldDto() },
        onChanged = { changeCount++ },
    )

    @Test
    fun setItems_thenItems_roundTripsNonBlankRows() {
        val list = newList()
        list.setItems(listOf(ContactFieldDto(label = "Home", value = "a@example.com")))

        assertEquals(1, container.childCount)
        assertEquals(listOf(ContactFieldDto(label = "Home", value = "a@example.com")), list.items())
    }

    @Test
    fun tappingAddButton_addsABlankRow_droppedByItemsUntilFilled() {
        val list = newList()
        list.setItems(emptyList())

        addButton.performClick()

        assertEquals(1, container.childCount)
        assertTrue("a freshly-added blank row must not appear in items()", list.items().isEmpty())

        val row = container.getChildAt(0)
        row.findViewById<EditText>(R.id.rowFieldB).setText("new@example.com")

        assertEquals(listOf(ContactFieldDto(value = "new@example.com")), list.items())
    }

    @Test
    fun tappingRemove_removesExactlyThatRow_evenAfterEarlierRemovals() {
        val list = newList()
        list.setItems(
            listOf(
                ContactFieldDto(label = "First", value = "1@example.com"),
                ContactFieldDto(label = "Second", value = "2@example.com"),
                ContactFieldDto(label = "Third", value = "3@example.com"),
            ),
        )

        container.getChildAt(0).findViewById<android.view.View>(R.id.rowFieldRemove).performClick()
        assertEquals(2, container.childCount)

        // Removing the (now first) row again must remove "Second", not stale-index into "Third".
        container.getChildAt(0).findViewById<android.view.View>(R.id.rowFieldRemove).performClick()

        assertEquals(listOf(ContactFieldDto(label = "Third", value = "3@example.com")), list.items())
    }

    @Test
    fun everyMutation_firesOnChanged() {
        val list = newList()
        list.setItems(listOf(ContactFieldDto(value = "a@example.com")))
        val afterSetItems = changeCount
        assertTrue(afterSetItems > 0)

        addButton.performClick()
        assertTrue(changeCount > afterSetItems)
    }
}
```

This test uses `androidx.core.widget.doAfterTextChanged` (imported above) — `androidx.core:core-ktx` is already on the `implementation` classpath (`app/build.gradle.kts:108`), which `androidTestImplementation` inherits, so no dependency changes are needed. `ContactEditActivity.kt`'s own production code keeps its existing verbose `TextWatcher` pattern unchanged (Tasks 7/8/9/10/11 add a small shared `SimpleTextWatcher` helper for that, not `doAfterTextChanged` — see Task 7) — this test's use of the ktx extension is local to the test file only.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.urlxl.mail.contacts.RepeatableFieldListTest`
Expected: `BUILD SUCCESSFUL`, 4 tests passed (check `app/build/reports/androidTests/connected/debug/com.urlxl.mail.contacts.RepeatableFieldListTest.html` for per-test pass/fail if the summary alone isn't conclusive).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/RepeatableFieldList.kt \
        app/src/main/res/layout/row_contact_two_field.xml \
        app/src/main/res/values/strings.xml \
        app/src/androidTest/java/com/urlxl/mail/contacts/RepeatableFieldListTest.kt
git commit -m "feat(contacts): add generic RepeatableFieldList component"
```

---

### Task 2: `ExpandableSectionView` collapsible container

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/contacts/ExpandableSectionView.kt`
- Create: `app/src/main/res/layout/view_expandable_section_header.xml`
- Test: `app/src/androidTest/java/com/urlxl/mail/contacts/ExpandableSectionViewTest.kt`

**Interfaces:**
- Produces: `class ExpandableSectionView(context, attrs) : LinearLayout`, usable as an XML tag whose declared children are auto-collected into its `body`. Public API: `val body: LinearLayout`, `var isExpanded: Boolean` (read-only externally), `fun setTitle(title: String)`, `fun setItemCount(count: Int)`, `fun setExpanded(expanded: Boolean)`.

- [ ] **Step 1: Add the header layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- app/src/main/res/layout/view_expandable_section_header.xml -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingTop="12dp"
    android:paddingBottom="12dp"
    android:clickable="true"
    android:focusable="true"
    android:background="?attr/selectableItemBackground">

    <TextView
        android:id="@+id/sectionHeaderTitle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="14sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/sectionHeaderCount"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="8dp"
        android:textSize="12sp"
        android:visibility="gone" />

    <TextView
        android:id="@+id/sectionHeaderChevron"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="16sp" />

</LinearLayout>
```

- [ ] **Step 2: Write `ExpandableSectionView`**

```kotlin
// app/src/main/java/com/urlxl/mail/contacts/ExpandableSectionView.kt
package com.urlxl.mail.contacts

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.urlxl.mail.R

/**
 * Collapsible section container: a tappable header (title + item-count badge + chevron) that
 * toggles [body]'s visibility. Any children declared in XML inside this tag are automatically
 * moved into [body] (see [onFinishInflate]) so callers can populate a section's static fields
 * declaratively in the layout file; list-typed fields are added to [body] at runtime instead, via
 * [RepeatableFieldList]. Purely a layout primitive — knows nothing about contact fields.
 */
class ExpandableSectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    val body: LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
    }

    private val headerTitle: TextView
    private val headerCount: TextView
    private val headerChevron: TextView

    var isExpanded: Boolean = false
        private set

    init {
        orientation = VERTICAL
        val header = LayoutInflater.from(context).inflate(R.layout.view_expandable_section_header, this, false)
        headerTitle = header.findViewById(R.id.sectionHeaderTitle)
        headerCount = header.findViewById(R.id.sectionHeaderCount)
        headerChevron = header.findViewById(R.id.sectionHeaderChevron)
        header.setOnClickListener { setExpanded(!isExpanded) }
        addView(header)
        addView(body)
        setExpanded(false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Index 0 is the header, index 1 is body, both added in init above. Anything declared in
        // XML inside this tag lands after them and belongs in body instead.
        if (childCount > 2) {
            val staticChildren = (2 until childCount).map { getChildAt(it) as View }
            staticChildren.forEach { removeView(it) }
            staticChildren.forEach { body.addView(it) }
        }
    }

    fun setTitle(title: String) {
        headerTitle.text = title
    }

    fun setItemCount(count: Int) {
        headerCount.visibility = if (count > 0) VISIBLE else GONE
        headerCount.text = count.toString()
    }

    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        body.visibility = if (expanded) VISIBLE else GONE
        headerChevron.text = if (expanded) "▾" else "▸"
    }
}
```

- [ ] **Step 3: Write the instrumented test**

```kotlin
// app/src/androidTest/java/com/urlxl/mail/contacts/ExpandableSectionViewTest.kt
package com.urlxl.mail.contacts

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.urlxl.mail.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpandableSectionViewTest {

    // The header layout references ?attr/selectableItemBackground (view_expandable_section_header.xml),
    // an AppCompat/MaterialComponents theme attribute. The bare instrumentation targetContext isn't
    // themed (it resolves to the plain framework theme), so it fails to inflate with
    // "Failed to resolve attribute" — wrap it in the app's real theme, exactly like every Activity in
    // this app gets via the manifest's android:theme="@style/Theme.LlamaMailForAndroid".
    private val context = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.Theme_LlamaMailForAndroid,
    )

    @Test
    fun startsCollapsed_bodyGoneUntilExpanded() {
        val section = ExpandableSectionView(context, null)

        assertFalse(section.isExpanded)
        assertEquals(View.GONE, section.body.visibility)

        section.setExpanded(true)

        assertTrue(section.isExpanded)
        assertEquals(View.VISIBLE, section.body.visibility)
    }

    @Test
    fun tappingHeader_togglesExpansion() {
        val section = ExpandableSectionView(context, null)
        val header = section.getChildAt(0)

        header.performClick()
        assertTrue(section.isExpanded)

        header.performClick()
        assertFalse(section.isExpanded)
    }

    @Test
    fun programmaticallyAddedChild_landsInBody_viaOnFinishInflate() {
        val section = ExpandableSectionView(context, null)
        val staticField = EditText(context)
        section.addView(staticField)

        // onFinishInflate only runs for XML-inflated views; call it directly to simulate that path
        // for a view constructed programmatically in this unit test.
        section.onFinishInflateForTest()

        assertEquals(0, (section as LinearLayout).let { (2 until it.childCount).count() })
        assertTrue(section.body.indexOfChild(staticField) >= 0)
    }
}
```

`onFinishInflate()` is `protected` on `View`, so the third test needs a test-only public shim. Add this to `ExpandableSectionView.kt` right after the class's closing brace... actually, add it as an internal member instead of a top-level extension, since `onFinishInflate` isn't accessible from outside the class:

Amend Step 2's `ExpandableSectionView` class to add, right after `setExpanded`:

```kotlin
    /** Test-only: [onFinishInflate] is protected and only invoked by the inflater; this lets
     *  [ExpandableSectionViewTest] exercise the same move-children-into-body logic for a view built
     *  programmatically instead of from XML. */
    internal fun onFinishInflateForTest() = onFinishInflate()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.urlxl.mail.contacts.ExpandableSectionViewTest`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ExpandableSectionView.kt \
        app/src/main/res/layout/view_expandable_section_header.xml \
        app/src/androidTest/java/com/urlxl/mail/contacts/ExpandableSectionViewTest.kt
git commit -m "feat(contacts): add generic ExpandableSectionView component"
```

---

### Task 3: Extend `mergedContactDto` for every newly-editable field

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt:169-186` (the `mergedContactDto` function added by the prior data-loss fix)
- Modify: `app/src/test/java/com/urlxl/mail/contacts/ContactEditActivityTest.kt`

**Interfaces:**
- Consumes: `ContactDto` (existing, all fields already defined in `ContactSyncModels.kt`), `ContactAddressDto`, `ContactImDto`, `ContactUrlDto`, `ContactRelationDto`, `ContactEventDto`, `ContactCustomFieldDto` (existing).
- Produces: `mergedContactDto(loaded: ContactDto, uid: String, rev: Long, fn: String, givenName: String?, familyName: String?, middleName: String?, prefix: String?, suffix: String?, nickname: String?, org: String?, title: String?, department: String?, notes: String?, birthday: String?, emails: List<ContactFieldDto>, phones: List<ContactFieldDto>, addresses: List<ContactAddressDto>, ims: List<ContactImDto>, websites: List<ContactUrlDto>, relations: List<ContactRelationDto>, events: List<ContactEventDto>, phoneticGivenName: String?, phoneticFamilyName: String?, customFields: List<ContactCustomFieldDto>, pronouns: String?): ContactDto` — used by every later task's `save()` wiring.

- [ ] **Step 1: Write the failing test**

Replace `ContactEditActivityTest.kt`'s `mergedContactDto_preservesEveryFieldTheEditorHasNoUiFor` test (it currently asserts the *new* fields are preserved-because-untouched; now that they're editable, it must assert they reflect edits instead) with:

```kotlin
// app/src/test/java/com/urlxl/mail/contacts/ContactEditActivityTest.kt
package com.urlxl.mail.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun mergedContactDto_editableFields_reflectEdits() {
        val result = mergedContactDto(
            loaded = loaded,
            uid = loaded.uid,
            rev = loaded.rev,
            fn = "New Name",
            givenName = "New",
            familyName = "Surname",
            middleName = "Mid",
            prefix = "Mx.",
            suffix = "III",
            nickname = "Newy",
            org = "New Org",
            title = "New Title",
            department = "Sales",
            notes = "New notes",
            birthday = "1991-02-02",
            emails = listOf(ContactFieldDto(value = "new@example.com")),
            phones = listOf(ContactFieldDto(value = "555-1111")),
            addresses = listOf(ContactAddressDto(city = "Shelbyville")),
            ims = listOf(ContactImDto(service = "telegram", value = "new-im")),
            websites = listOf(ContactUrlDto(value = "https://new.example.com")),
            relations = listOf(ContactRelationDto(name = "Sibling")),
            events = listOf(ContactEventDto(date = "2021-03-03")),
            phoneticGivenName = "New-uh",
            phoneticFamilyName = "Sur-name",
            customFields = listOf(ContactCustomFieldDto(label = "New Custom", value = "New Value")),
            pronouns = "she/her",
        )

        assertEquals("New Name", result.fn)
        assertEquals("New", result.givenName)
        assertEquals("Surname", result.familyName)
        assertEquals("Mid", result.middleName)
        assertEquals("Mx.", result.prefix)
        assertEquals("III", result.suffix)
        assertEquals("Newy", result.nickname)
        assertEquals("New Org", result.org)
        assertEquals("New Title", result.title)
        assertEquals("Sales", result.department)
        assertEquals("New notes", result.notes)
        assertEquals("1991-02-02", result.birthday)
        assertEquals(listOf(ContactFieldDto(value = "new@example.com")), result.emails)
        assertEquals(listOf(ContactFieldDto(value = "555-1111")), result.phones)
        assertEquals(listOf(ContactAddressDto(city = "Shelbyville")), result.addresses)
        assertEquals(listOf(ContactImDto(service = "telegram", value = "new-im")), result.ims)
        assertEquals(listOf(ContactUrlDto(value = "https://new.example.com")), result.websites)
        assertEquals(listOf(ContactRelationDto(name = "Sibling")), result.relations)
        assertEquals(listOf(ContactEventDto(date = "2021-03-03")), result.events)
        assertEquals("New-uh", result.phoneticGivenName)
        assertEquals("Sur-name", result.phoneticFamilyName)
        assertEquals(listOf(ContactCustomFieldDto(label = "New Custom", value = "New Value")), result.customFields)
        assertEquals("she/her", result.pronouns)
    }

    @Test
    fun mergedContactDto_deferredAndReadOnlyFields_alwaysSurviveUntouched() {
        val result = mergedContactDto(
            loaded = loaded,
            uid = loaded.uid,
            rev = loaded.rev,
            fn = "New Name",
            givenName = null,
            familyName = null,
            middleName = null,
            prefix = null,
            suffix = null,
            nickname = null,
            org = null,
            title = null,
            department = null,
            notes = null,
            birthday = null,
            emails = emptyList(),
            phones = emptyList(),
            addresses = emptyList(),
            ims = emptyList(),
            websites = emptyList(),
            relations = emptyList(),
            events = emptyList(),
            phoneticGivenName = null,
            phoneticFamilyName = null,
            customFields = emptyList(),
            pronouns = null,
        )

        // Never editable in ContactEditActivity — must survive regardless of what else changed.
        assertEquals(loaded.groupIDs, result.groupIDs)
        assertEquals(loaded.photoRef, result.photoRef)
        assertEquals(loaded.pgpKey, result.pgpKey)
        assertEquals(loaded.isSelf, result.isSelf)
    }

    @Test
    fun mergedContactDto_newContact_leavesUnsetFieldsAtDefaults() {
        val result = mergedContactDto(
            loaded = ContactDto(),
            uid = "",
            rev = 0,
            fn = "Brand New",
            givenName = null,
            familyName = null,
            middleName = null,
            prefix = null,
            suffix = null,
            nickname = null,
            org = null,
            title = null,
            department = null,
            notes = null,
            birthday = null,
            emails = emptyList(),
            phones = emptyList(),
            addresses = emptyList(),
            ims = emptyList(),
            websites = emptyList(),
            relations = emptyList(),
            events = emptyList(),
            phoneticGivenName = null,
            phoneticFamilyName = null,
            customFields = emptyList(),
            pronouns = null,
        )

        assertEquals(ContactDto(fn = "Brand New"), result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.contacts.ContactEditActivityTest"`
Expected: FAIL — compile error, `mergedContactDto` doesn't accept most of these named parameters yet.

- [ ] **Step 3: Extend `mergedContactDto`**

In `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt`, replace the existing `mergedContactDto` function (added by the prior data-loss fix) with:

```kotlin
internal fun mergedContactDto(
    loaded: ContactDto,
    uid: String,
    rev: Long,
    fn: String,
    givenName: String?,
    familyName: String?,
    middleName: String?,
    prefix: String?,
    suffix: String?,
    nickname: String?,
    org: String?,
    title: String?,
    department: String?,
    notes: String?,
    birthday: String?,
    emails: List<ContactFieldDto>,
    phones: List<ContactFieldDto>,
    addresses: List<ContactAddressDto>,
    ims: List<ContactImDto>,
    websites: List<ContactUrlDto>,
    relations: List<ContactRelationDto>,
    events: List<ContactEventDto>,
    phoneticGivenName: String?,
    phoneticFamilyName: String?,
    customFields: List<ContactCustomFieldDto>,
    pronouns: String?,
): ContactDto = loaded.copy(
    uid = uid,
    rev = rev,
    fn = fn,
    givenName = givenName,
    familyName = familyName,
    middleName = middleName,
    prefix = prefix,
    suffix = suffix,
    nickname = nickname,
    org = org,
    title = title,
    department = department,
    notes = notes,
    birthday = birthday,
    emails = emails,
    phones = phones,
    addresses = addresses,
    ims = ims,
    websites = websites,
    relations = relations,
    events = events,
    phoneticGivenName = phoneticGivenName,
    phoneticFamilyName = phoneticFamilyName,
    customFields = customFields,
    pronouns = pronouns,
)
```

This will break the *existing* call site in `save()` (it still only passes the original 7 args) — that's expected and fixed in Task 5 (Name section) once the new fields have UI to read from. For now, update the call site minimally so the project compiles, passing `null`/`emptyList()` for every field this task doesn't yet wire up (Tasks 5–11 replace each `null`/`emptyList()` with a real UI-backed value one section at a time):

In `save()`, replace:

```kotlin
        val dto = mergedContactDto(
            loaded = loadedDto,
            uid = existingUid,
            rev = existingRev,
            fn = fn,
            org = orgField.text.toString().trim().ifBlank { null },
            notes = notesField.text.toString().trim().ifBlank { null },
            emails = emails,
            phones = phones,
        )
```

with:

```kotlin
        val dto = mergedContactDto(
            loaded = loadedDto,
            uid = existingUid,
            rev = existingRev,
            fn = fn,
            givenName = null,
            familyName = null,
            middleName = null,
            prefix = null,
            suffix = null,
            nickname = null,
            org = orgField.text.toString().trim().ifBlank { null },
            title = null,
            department = null,
            notes = notesField.text.toString().trim().ifBlank { null },
            birthday = null,
            emails = emails,
            phones = phones,
            addresses = emptyList(),
            ims = emptyList(),
            websites = emptyList(),
            relations = emptyList(),
            events = emptyList(),
            phoneticGivenName = null,
            phoneticFamilyName = null,
            customFields = emptyList(),
            pronouns = null,
        )
```

(Passing `null`/`emptyList()` here is a genuine, temporary intermediate state — not a placeholder in the "No Placeholders" sense, since it's real code that compiles and behaves correctly: those fields simply aren't editable *yet* in this task, matching `loadedDto`'s actual preserved value would be wrong here since we're intentionally testing that unwired fields reset to blank until their section task wires real UI. Tasks 5–11 replace every one of these with the real field read.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.contacts.ContactEditActivityTest"`
Expected: PASS, all 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt \
        app/src/test/java/com/urlxl/mail/contacts/ContactEditActivityTest.kt
git commit -m "feat(contacts): extend mergedContactDto to cover every editable field"
```

---

### Task 4: Rewrite `activity_contact_edit.xml` with all sections

**Files:**
- Modify: `app/src/main/res/layout/activity_contact_edit.xml` (full rewrite)
- Modify: `app/src/main/res/values/strings.xml` (add every section/field/hint string used below)

**Interfaces:**
- Consumes: `ExpandableSectionView` (Task 2).
- Produces: every view ID referenced by Tasks 5–11 (enumerated per-section below).

- [ ] **Step 1: Add every new string resource**

In `app/src/main/res/values/strings.xml`, add these lines immediately after the `contacts_row_b_hint` entry added in Task 1 (i.e. still within the existing contacts block):

```xml
    <string name="contacts_section_name">Name</string>
    <string name="contacts_given_name_label">Given name (optional)</string>
    <string name="contacts_family_name_label">Family name (optional)</string>
    <string name="contacts_middle_name_label">Middle name (optional)</string>
    <string name="contacts_prefix_label">Prefix (optional)</string>
    <string name="contacts_suffix_label">Suffix (optional)</string>
    <string name="contacts_nickname_label">Nickname (optional)</string>
    <string name="contacts_phonetic_given_name_label">Phonetic given name (optional)</string>
    <string name="contacts_phonetic_family_name_label">Phonetic family name (optional)</string>
    <string name="contacts_pronouns_label">Pronouns (optional)</string>

    <string name="contacts_section_work">Work</string>
    <string name="contacts_job_title_label">Job title (optional)</string>
    <string name="contacts_department_label">Department (optional)</string>

    <string name="contacts_section_contact">Contact</string>
    <string name="contacts_emails_add">+ Add email</string>
    <string name="contacts_phones_add">+ Add phone</string>
    <string name="contacts_email_row_label_hint">Label (e.g. Home, Work)</string>
    <string name="contacts_email_row_value_hint">Email address</string>
    <string name="contacts_phone_row_label_hint">Label (e.g. Home, Work, Mobile)</string>
    <string name="contacts_phone_row_value_hint">Phone number</string>

    <string name="contacts_section_addresses">Addresses</string>
    <string name="contacts_addresses_add">+ Add address</string>
    <string name="contacts_address_label_hint">Label (e.g. Home, Work)</string>
    <string name="contacts_address_street_hint">Street</string>
    <string name="contacts_address_city_hint">City</string>
    <string name="contacts_address_region_hint">State / Region</string>
    <string name="contacts_address_postal_code_hint">Postal code</string>
    <string name="contacts_address_country_hint">Country</string>

    <string name="contacts_section_online">Online</string>
    <string name="contacts_websites_add">+ Add website</string>
    <string name="contacts_website_row_label_hint">Label (e.g. Personal, Blog)</string>
    <string name="contacts_website_row_value_hint">https://…</string>
    <string name="contacts_ims_add">+ Add IM</string>
    <string name="contacts_im_service_hint">Service (e.g. Signal, Telegram)</string>
    <string name="contacts_im_label_hint">Label (optional)</string>
    <string name="contacts_im_value_hint">Username / handle</string>

    <string name="contacts_section_personal">Personal</string>
    <string name="contacts_birthday_label">Birthday (optional)</string>
    <string name="contacts_birthday_hint">Tap to set date</string>
    <string name="contacts_events_add">+ Add event</string>
    <string name="contacts_event_label_hint">Label (e.g. Anniversary)</string>
    <string name="contacts_event_date_hint">Tap to set date</string>
    <string name="contacts_relations_add">+ Add relation</string>
    <string name="contacts_relation_row_label_hint">Relationship (e.g. Spouse, Sibling)</string>
    <string name="contacts_relation_row_value_hint">Name</string>

    <string name="contacts_section_notes">Notes</string>

    <string name="contacts_section_other">Other</string>
    <string name="contacts_customfields_add">+ Add custom field</string>
    <string name="contacts_customfield_row_label_hint">Field name</string>
    <string name="contacts_customfield_row_value_hint">Value</string>

    <string name="contacts_pgp_badge_visible">PGP key on file</string>
```

- [ ] **Step 2: Rewrite the layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- app/src/main/res/layout/activity_contact_edit.xml -->
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/contactEditRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:id="@+id/contactEditAvatar"
            android:layout_width="52dp"
            android:layout_height="52dp"
            android:layout_gravity="center_horizontal"
            android:layout_marginBottom="8dp"
            android:textSize="18sp"
            android:textStyle="bold" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center"
            android:layout_marginBottom="16dp">

            <com.google.android.material.chip.Chip
                android:id="@+id/contactEditSelfBadge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="8dp"
                android:visibility="gone"
                app:chipMinHeight="24dp"
                app:ensureMinTouchTargetSize="false"
                xmlns:app="http://schemas.android.com/apk/res-auto" />

            <com.google.android.material.chip.Chip
                android:id="@+id/contactEditPgpBadge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:visibility="gone"
                app:chipMinHeight="24dp"
                app:ensureMinTouchTargetSize="false" />

        </LinearLayout>

        <com.urlxl.mail.contacts.ExpandableSectionView
            android:id="@+id/sectionName"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="8dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_name_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactName"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="@string/contacts_name_hint"
                android:inputType="textPersonName"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_given_name_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactGivenName"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="textPersonName"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_family_name_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactFamilyName"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="textPersonName"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_middle_name_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactMiddleName"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="textPersonName"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_prefix_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactPrefix"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_suffix_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactSuffix"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_nickname_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactNickname"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_phonetic_given_name_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactPhoneticGivenName"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_phonetic_family_name_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactPhoneticFamilyName"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_pronouns_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactPronouns"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text"
                android:layout_marginBottom="4dp" />

        </com.urlxl.mail.contacts.ExpandableSectionView>

        <com.urlxl.mail.contacts.ExpandableSectionView
            android:id="@+id/sectionWork"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="8dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_org_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactOrg"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_job_title_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactTitle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text"
                android:layout_marginBottom="12dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_department_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactDepartment"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text"
                android:layout_marginBottom="4dp" />

        </com.urlxl.mail.contacts.ExpandableSectionView>

        <com.urlxl.mail.contacts.ExpandableSectionView
            android:id="@+id/sectionContact"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="8dp">

            <LinearLayout
                android:id="@+id/emailRowsContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <Button
                android:id="@+id/btnAddEmail"
                style="?android:attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_emails_add"
                android:layout_marginBottom="12dp" />

            <LinearLayout
                android:id="@+id/phoneRowsContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <Button
                android:id="@+id/btnAddPhone"
                style="?android:attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_phones_add"
                android:layout_marginBottom="4dp" />

        </com.urlxl.mail.contacts.ExpandableSectionView>

        <com.urlxl.mail.contacts.ExpandableSectionView
            android:id="@+id/sectionAddresses"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="8dp">

            <LinearLayout
                android:id="@+id/addressRowsContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <Button
                android:id="@+id/btnAddAddress"
                style="?android:attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_addresses_add"
                android:layout_marginBottom="4dp" />

        </com.urlxl.mail.contacts.ExpandableSectionView>

        <com.urlxl.mail.contacts.ExpandableSectionView
            android:id="@+id/sectionOnline"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="8dp">

            <LinearLayout
                android:id="@+id/websiteRowsContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <Button
                android:id="@+id/btnAddWebsite"
                style="?android:attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_websites_add"
                android:layout_marginBottom="12dp" />

            <LinearLayout
                android:id="@+id/imRowsContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <Button
                android:id="@+id/btnAddIm"
                style="?android:attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_ims_add"
                android:layout_marginBottom="4dp" />

        </com.urlxl.mail.contacts.ExpandableSectionView>

        <com.urlxl.mail.contacts.ExpandableSectionView
            android:id="@+id/sectionPersonal"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="8dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_birthday_label"
                android:textSize="12sp"
                android:layout_marginBottom="4dp" />

            <EditText
                android:id="@+id/editContactBirthday"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="@string/contacts_birthday_hint"
                android:focusable="false"
                android:clickable="true"
                android:layout_marginBottom="12dp" />

            <LinearLayout
                android:id="@+id/eventRowsContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <Button
                android:id="@+id/btnAddEvent"
                style="?android:attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_events_add"
                android:layout_marginBottom="12dp" />

            <LinearLayout
                android:id="@+id/relationRowsContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <Button
                android:id="@+id/btnAddRelation"
                style="?android:attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_relations_add"
                android:layout_marginBottom="4dp" />

        </com.urlxl.mail.contacts.ExpandableSectionView>

        <com.urlxl.mail.contacts.ExpandableSectionView
            android:id="@+id/sectionNotes"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="8dp">

            <EditText
                android:id="@+id/editContactNotes"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="textMultiLine"
                android:minLines="3"
                android:layout_marginBottom="4dp" />

        </com.urlxl.mail.contacts.ExpandableSectionView>

        <com.urlxl.mail.contacts.ExpandableSectionView
            android:id="@+id/sectionOther"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="24dp">

            <LinearLayout
                android:id="@+id/customFieldRowsContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <Button
                android:id="@+id/btnAddCustomField"
                style="?android:attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/contacts_customfields_add"
                android:layout_marginBottom="4dp" />

        </com.urlxl.mail.contacts.ExpandableSectionView>

        <Button
            android:id="@+id/btnSaveContact"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/save_settings"
            android:layout_marginBottom="12dp" />

        <Button
            android:id="@+id/btnDeleteContact"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/action_delete" />

    </LinearLayout>

</ScrollView>
```

Note the `xmlns:app` declared inline on `contactEditSelfBadge` — Android layout XML only allows namespace declarations on any element, not just the root; keep it exactly as written there (needed for the two `Chip`'s `app:chipMinHeight`/`app:ensureMinTouchTargetSize` attributes) since the root `ScrollView` here doesn't otherwise declare `xmlns:app`.

- [ ] **Step 2: Verify the project still compiles**

`ContactEditActivity.kt` still references the old `activity_contact_edit.xml` ids it knows about (`editContactName`, `editContactOrg`, `editContactEmail` — **now removed**, `editContactPhone` — **now removed**, `editContactNotes`, `btnSaveContact`, `btnDeleteContact`, `contactEditAvatar`). `editContactEmail`/`editContactPhone` no longer exist (replaced by `emailRowsContainer`/`phoneRowsContainer` + `btnAddEmail`/`btnAddPhone`), so compilation **will fail** until Task 7 rewires the Contact section — that's expected; this task's own compile check only needs to confirm the *resource* files (layout + strings) are well-formed XML, not that `ContactEditActivity.kt` builds yet.

Run: `./gradlew :app:processDebugResources`
Expected: `BUILD SUCCESSFUL` (this validates the XML/resource references without needing the Kotlin side to compile).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/activity_contact_edit.xml app/src/main/res/values/strings.xml
git commit -m "feat(contacts): rewrite contact edit layout with collapsible sections"
```

---

### Task 5: Wire Name section + read-only `isSelf`/`pgpKey` badges

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt`

**Interfaces:**
- Consumes: `activity_contact_edit.xml` ids from Task 4 (`sectionName`, `editContactGivenName`, `editContactFamilyName`, `editContactMiddleName`, `editContactPrefix`, `editContactSuffix`, `editContactNickname`, `editContactPhoneticGivenName`, `editContactPhoneticFamilyName`, `editContactPronouns`, `contactEditSelfBadge`, `contactEditPgpBadge`), `mergedContactDto` (Task 3), existing `R.string.contact_self_label`/`R.string.contact_status_secure_key`/`R.string.contact_status_no_key` and `applyStatusBadgeTheme` (both pre-existing, used by `ContactAdapter.kt`).

- [ ] **Step 1: Add the new fields and wire `onCreate`/`loadExisting`/`save`**

In `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt`, add these imports:

```kotlin
import com.google.android.material.chip.Chip
import com.urlxl.mail.applyStatusBadgeTheme
```

Add these properties alongside the existing `fnField`/`orgField`/etc. declarations:

```kotlin
    private lateinit var givenNameField: EditText
    private lateinit var familyNameField: EditText
    private lateinit var middleNameField: EditText
    private lateinit var prefixField: EditText
    private lateinit var suffixField: EditText
    private lateinit var nicknameField: EditText
    private lateinit var phoneticGivenNameField: EditText
    private lateinit var phoneticFamilyNameField: EditText
    private lateinit var pronounsField: EditText
    private lateinit var selfBadge: Chip
    private lateinit var pgpBadge: Chip
```

In `onCreate`, right after the existing `notesField = findViewById(...)` line, add:

```kotlin
        givenNameField = findViewById(R.id.editContactGivenName)
        familyNameField = findViewById(R.id.editContactFamilyName)
        middleNameField = findViewById(R.id.editContactMiddleName)
        prefixField = findViewById(R.id.editContactPrefix)
        suffixField = findViewById(R.id.editContactSuffix)
        nicknameField = findViewById(R.id.editContactNickname)
        phoneticGivenNameField = findViewById(R.id.editContactPhoneticGivenName)
        phoneticFamilyNameField = findViewById(R.id.editContactPhoneticFamilyName)
        pronounsField = findViewById(R.id.editContactPronouns)
        selfBadge = findViewById(R.id.contactEditSelfBadge)
        pgpBadge = findViewById(R.id.contactEditPgpBadge)
        findViewById<ExpandableSectionView>(R.id.sectionName).setTitle(getString(R.string.contacts_section_name))
        findViewById<ExpandableSectionView>(R.id.sectionName).setExpanded(true)
```

In `loadExisting`, right after the existing `notesField.setText(dto.notes.orEmpty())` line, add:

```kotlin
            givenNameField.setText(dto.givenName.orEmpty())
            familyNameField.setText(dto.familyName.orEmpty())
            middleNameField.setText(dto.middleName.orEmpty())
            prefixField.setText(dto.prefix.orEmpty())
            suffixField.setText(dto.suffix.orEmpty())
            nicknameField.setText(dto.nickname.orEmpty())
            phoneticGivenNameField.setText(dto.phoneticGivenName.orEmpty())
            phoneticFamilyNameField.setText(dto.phoneticFamilyName.orEmpty())
            pronounsField.setText(dto.pronouns.orEmpty())
            selfBadge.visibility = if (dto.isSelf) View.VISIBLE else View.GONE
            if (dto.isSelf) {
                selfBadge.text = getString(R.string.contact_self_label)
                applyStatusBadgeTheme(this@ContactEditActivity, selfBadge, active = true)
            }
            val hasKey = !dto.pgpKey.isNullOrBlank()
            pgpBadge.visibility = if (hasKey) View.VISIBLE else View.GONE
            if (hasKey) {
                pgpBadge.text = getString(R.string.contacts_pgp_badge_visible)
                applyStatusBadgeTheme(this@ContactEditActivity, pgpBadge, active = true)
            }
```

In `save()`, replace the `givenName = null,` through `pronouns = null,` lines (added by Task 3's temporary compile-fix) with:

```kotlin
            givenName = givenNameField.text.toString().trim().ifBlank { null },
            familyName = familyNameField.text.toString().trim().ifBlank { null },
            middleName = middleNameField.text.toString().trim().ifBlank { null },
            prefix = prefixField.text.toString().trim().ifBlank { null },
            suffix = suffixField.text.toString().trim().ifBlank { null },
            nickname = nicknameField.text.toString().trim().ifBlank { null },
```

and, further down in the same `mergedContactDto(...)` call, replace `phoneticGivenName = null,` and `phoneticFamilyName = null,` and `pronouns = null,` with:

```kotlin
            phoneticGivenName = phoneticGivenNameField.text.toString().trim().ifBlank { null },
            phoneticFamilyName = phoneticFamilyNameField.text.toString().trim().ifBlank { null },
```

and

```kotlin
            pronouns = pronounsField.text.toString().trim().ifBlank { null },
```

(leave every other still-`null`/`emptyList()` parameter as Task 3 left it — Tasks 6–11 replace the rest one section at a time).

- [ ] **Step 2: Build and fix any remaining compile errors in this file**

Run: `./gradlew :app:compileDebugKotlin`
Expected: still fails — `editContactEmail`/`editContactPhone` are referenced by the old single-value email/phone code in `loadExisting`/`save`, and those ids no longer exist in the layout (removed in Task 4). This is expected; Task 7 removes that old code. Confirm the *only* remaining errors mention `editContactEmail`/`editContactPhone`/`emailField`/`phoneField` — if any other error appears, it's a mistake in this task's edits and must be fixed before moving on.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt
git commit -m "feat(contacts): wire Name section fields and read-only isSelf/pgpKey badges"
```

(This task intentionally leaves the build red — `editContactEmail`/`editContactPhone` references are cleaned up in Task 7. Committing mid-build-break matches this plan's task-per-section decomposition; the tree is compilable again by the end of Task 7.)

---

### Task 6: Wire Work section

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt`

**Interfaces:**
- Consumes: `sectionWork`, `editContactTitle`, `editContactDepartment` ids from Task 4; `mergedContactDto` (Task 3); existing `orgField`/`editContactOrg` (unchanged id, just now inside `sectionWork` instead of the flat layout).

- [ ] **Step 1: Wire the two new fields**

Add property:

```kotlin
    private lateinit var titleField: EditText
    private lateinit var departmentField: EditText
```

In `onCreate`, alongside the Task 5 additions:

```kotlin
        titleField = findViewById(R.id.editContactTitle)
        departmentField = findViewById(R.id.editContactDepartment)
        findViewById<ExpandableSectionView>(R.id.sectionWork).setTitle(getString(R.string.contacts_section_work))
```

In `loadExisting`, alongside the Task 5 additions:

```kotlin
            titleField.setText(dto.title.orEmpty())
            departmentField.setText(dto.department.orEmpty())
            findViewById<ExpandableSectionView>(R.id.sectionWork).setExpanded(
                dto.org != null || dto.title != null || dto.department != null,
            )
```

In `save()`, replace `title = null,` and `department = null,` with:

```kotlin
            title = titleField.text.toString().trim().ifBlank { null },
            department = departmentField.text.toString().trim().ifBlank { null },
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt
git commit -m "feat(contacts): wire Work section (title, department)"
```

(Build stays red until Task 7, per Task 5's note.)

---

### Task 7: Wire Contact section (full emails/phones lists)

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt`

**Interfaces:**
- Consumes: `RepeatableFieldList` (Task 1), `row_contact_two_field.xml` (Task 1), `sectionContact`, `emailRowsContainer`, `btnAddEmail`, `phoneRowsContainer`, `btnAddPhone` ids (Task 4).
- Produces: removes `emailField`/`phoneField`/`extraEmails`/`extraPhones` entirely (superseded by `RepeatableFieldList`).

- [ ] **Step 1: Remove the old single-value email/phone code**

Delete these properties (no longer needed):

```kotlin
    private lateinit var emailField: EditText
    private lateinit var phoneField: EditText
```

and

```kotlin
    private var extraEmails: List<ContactFieldDto> = emptyList()
    private var extraPhones: List<ContactFieldDto> = emptyList()
```

Delete these lines from `onCreate`:

```kotlin
        emailField = findViewById(R.id.editContactEmail)
        phoneField = findViewById(R.id.editContactPhone)
```

Delete these lines from `loadExisting`:

```kotlin
            emailField.setText(dto.emails.firstOrNull()?.value.orEmpty())
            phoneField.setText(dto.phones.firstOrNull()?.value.orEmpty())
            extraEmails = dto.emails.drop(1)
            extraPhones = dto.phones.drop(1)
```

Delete these lines from `save()`:

```kotlin
        val email = emailField.text.toString().trim()
        val phone = phoneField.text.toString().trim()
        val emails = (if (email.isNotBlank()) listOf(ContactFieldDto(value = email)) else emptyList()) + extraEmails
        val phones = (if (phone.isNotBlank()) listOf(ContactFieldDto(value = phone)) else emptyList()) + extraPhones
```

- [ ] **Step 2: Add `RepeatableFieldList`-backed emails/phones**

Add properties:

```kotlin
    private lateinit var emailList: RepeatableFieldList<ContactFieldDto>
    private lateinit var phoneList: RepeatableFieldList<ContactFieldDto>
```

In `onCreate`, alongside the other section wiring:

```kotlin
        findViewById<ExpandableSectionView>(R.id.sectionContact).setTitle(getString(R.string.contacts_section_contact))
        emailList = RepeatableFieldList(
            container = findViewById(R.id.emailRowsContainer),
            addButton = findViewById(R.id.btnAddEmail),
            rowLayoutRes = R.layout.row_contact_two_field,
            removeButtonId = R.id.rowFieldRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowFieldA)
                val valueField = rowView.findViewById<EditText>(R.id.rowFieldB)
                labelField.hint = getString(R.string.contacts_email_row_label_hint)
                valueField.hint = getString(R.string.contacts_email_row_value_hint)
                valueField.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                labelField.setText(item.label.orEmpty())
                valueField.setText(item.value)
                // Both fields must read from each other's *live* text, not the bind-time item
                // snapshot — two separate listeners each doing item.copy(singleField = ...) would
                // silently drop whichever field was edited first the next time the other field
                // fires (each closes over the same stale item).
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim().ifBlank { null },
                            value = valueField.text.toString().trim(),
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                valueField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.label.isNullOrBlank() && it.value.isBlank() },
            default = { ContactFieldDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionContact).setItemCount(emailList.items().size + phoneList.items().size) },
        )
        phoneList = RepeatableFieldList(
            container = findViewById(R.id.phoneRowsContainer),
            addButton = findViewById(R.id.btnAddPhone),
            rowLayoutRes = R.layout.row_contact_two_field,
            removeButtonId = R.id.rowFieldRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowFieldA)
                val valueField = rowView.findViewById<EditText>(R.id.rowFieldB)
                labelField.hint = getString(R.string.contacts_phone_row_label_hint)
                valueField.hint = getString(R.string.contacts_phone_row_value_hint)
                valueField.inputType = android.text.InputType.TYPE_CLASS_PHONE
                labelField.setText(item.label.orEmpty())
                valueField.setText(item.value)
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim().ifBlank { null },
                            value = valueField.text.toString().trim(),
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                valueField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.label.isNullOrBlank() && it.value.isBlank() },
            default = { ContactFieldDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionContact).setItemCount(emailList.items().size + phoneList.items().size) },
        )
```

Add a small private helper (used above and reusable by every later section task) right before the `companion object` block at the bottom of the class:

```kotlin
    /** [android.text.TextWatcher] that only cares about the end state, matching every row-field
     *  use in this Activity (none need before/during-change info). */
    private class SimpleTextWatcher(private val onChanged: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChanged()
    }
```

In `loadExisting`, add:

```kotlin
            emailList.setItems(dto.emails)
            phoneList.setItems(dto.phones)
            findViewById<ExpandableSectionView>(R.id.sectionContact).setExpanded(dto.emails.isNotEmpty() || dto.phones.isNotEmpty())
            findViewById<ExpandableSectionView>(R.id.sectionContact).setItemCount(dto.emails.size + dto.phones.size)
```

In `save()`, replace the (now-missing, since Step 1 deleted the lines that built them) `emails`/`phones` arguments in the `mergedContactDto(...)` call — they currently read `emails = emails, phones = phones,` referencing the deleted local vals — with:

```kotlin
            emails = emailList.items(),
            phones = phoneList.items(),
```

- [ ] **Step 2: Build and run every prior task's tests to confirm the tree is green again**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` — this is the first fully-compiling checkpoint since Task 4's layout rewrite.

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, no failures (covers `ContactEditActivityTest` from Task 3).

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.urlxl.mail.contacts`
Expected: `BUILD SUCCESSFUL` — covers `RepeatableFieldListTest`/`ExpandableSectionViewTest` from Tasks 1–2 plus every other existing `contacts` package instrumented test (e.g. `ContactDaoOrderingTest`, `ContactSyncRaceRegressionTest`), confirming nothing here regressed them.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt
git commit -m "feat(contacts): wire Contact section with full emails/phones lists"
```

---

### Task 8: Wire Addresses section

**Files:**
- Create: `app/src/main/res/layout/row_contact_address.xml`
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt`

**Interfaces:**
- Consumes: `RepeatableFieldList` (Task 1), `sectionAddresses`, `addressRowsContainer`, `btnAddAddress` ids (Task 4).
- Produces: `row_contact_address.xml` with ids `rowAddressLabel`, `rowAddressStreet`, `rowAddressCity`, `rowAddressRegion`, `rowAddressPostalCode`, `rowAddressCountry`, `rowAddressRemove`.

- [ ] **Step 1: Add the address row layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- app/src/main/res/layout/row_contact_address.xml -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:layout_marginBottom="12dp">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <EditText
            android:id="@+id/rowAddressLabel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="4dp" />

        <EditText
            android:id="@+id/rowAddressStreet"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="4dp" />

        <EditText
            android:id="@+id/rowAddressCity"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="4dp" />

        <EditText
            android:id="@+id/rowAddressRegion"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="4dp" />

        <EditText
            android:id="@+id/rowAddressPostalCode"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="4dp" />

        <EditText
            android:id="@+id/rowAddressCountry"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

    </LinearLayout>

    <TextView
        android:id="@+id/rowAddressRemove"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:layout_marginStart="8dp"
        android:gravity="center"
        android:text="✕"
        android:textSize="18sp"
        android:clickable="true"
        android:focusable="true"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/contacts_field_remove_cd" />

</LinearLayout>
```

This row layout uses `rowAddressRemove` instead of `row_contact_two_field.xml`'s `rowFieldRemove` since it has its own distinct set of field ids — `RepeatableFieldList`'s `removeButtonId` constructor parameter (already in place since Task 1) is exactly for this: pass `removeButtonId = R.id.rowAddressRemove` below instead of `R.id.rowFieldRemove`.

- [ ] **Step 2: Wire the Addresses section**

Add property:

```kotlin
    private lateinit var addressList: RepeatableFieldList<ContactAddressDto>
```

In `onCreate`:

```kotlin
        findViewById<ExpandableSectionView>(R.id.sectionAddresses).setTitle(getString(R.string.contacts_section_addresses))
        addressList = RepeatableFieldList(
            container = findViewById(R.id.addressRowsContainer),
            addButton = findViewById(R.id.btnAddAddress),
            rowLayoutRes = R.layout.row_contact_address,
            removeButtonId = R.id.rowAddressRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowAddressLabel)
                val streetField = rowView.findViewById<EditText>(R.id.rowAddressStreet)
                val cityField = rowView.findViewById<EditText>(R.id.rowAddressCity)
                val regionField = rowView.findViewById<EditText>(R.id.rowAddressRegion)
                val postalField = rowView.findViewById<EditText>(R.id.rowAddressPostalCode)
                val countryField = rowView.findViewById<EditText>(R.id.rowAddressCountry)
                labelField.hint = getString(R.string.contacts_address_label_hint)
                streetField.hint = getString(R.string.contacts_address_street_hint)
                cityField.hint = getString(R.string.contacts_address_city_hint)
                regionField.hint = getString(R.string.contacts_address_region_hint)
                postalField.hint = getString(R.string.contacts_address_postal_code_hint)
                countryField.hint = getString(R.string.contacts_address_country_hint)
                labelField.setText(item.label.orEmpty())
                streetField.setText(item.street.orEmpty())
                cityField.setText(item.city.orEmpty())
                regionField.setText(item.region.orEmpty())
                postalField.setText(item.postalCode.orEmpty())
                countryField.setText(item.country.orEmpty())
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim().ifBlank { null },
                            street = streetField.text.toString().trim().ifBlank { null },
                            city = cityField.text.toString().trim().ifBlank { null },
                            region = regionField.text.toString().trim().ifBlank { null },
                            postalCode = postalField.text.toString().trim().ifBlank { null },
                            country = countryField.text.toString().trim().ifBlank { null },
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                streetField.addTextChangedListener(SimpleTextWatcher(emit))
                cityField.addTextChangedListener(SimpleTextWatcher(emit))
                regionField.addTextChangedListener(SimpleTextWatcher(emit))
                postalField.addTextChangedListener(SimpleTextWatcher(emit))
                countryField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.label.isNullOrBlank() && it.street.isNullOrBlank() && it.city.isNullOrBlank() && it.region.isNullOrBlank() && it.postalCode.isNullOrBlank() && it.country.isNullOrBlank() },
            default = { ContactAddressDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionAddresses).setItemCount(addressList.items().size) },
        )
```

In `loadExisting`:

```kotlin
            addressList.setItems(dto.addresses)
            findViewById<ExpandableSectionView>(R.id.sectionAddresses).setExpanded(dto.addresses.isNotEmpty())
            findViewById<ExpandableSectionView>(R.id.sectionAddresses).setItemCount(dto.addresses.size)
```

In `save()`, replace `addresses = emptyList(),` with:

```kotlin
            addresses = addressList.items(),
```

- [ ] **Step 3: Build, run all tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt \
        app/src/main/res/layout/row_contact_address.xml
git commit -m "feat(contacts): wire Addresses section"
```

---

### Task 9: Wire Online section (websites + IMs)

**Files:**
- Create: `app/src/main/res/layout/row_contact_im.xml`
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt`

**Interfaces:**
- Consumes: `RepeatableFieldList` (Task 1/8), `row_contact_two_field.xml` (Task 1) for websites, `sectionOnline`, `websiteRowsContainer`, `btnAddWebsite`, `imRowsContainer`, `btnAddIm` ids (Task 4).
- Produces: `row_contact_im.xml` with ids `rowImService`, `rowImLabel`, `rowImValue`, `rowImRemove`.

- [ ] **Step 1: Add the IM row layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- app/src/main/res/layout/row_contact_im.xml -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:layout_marginBottom="8dp">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <EditText
            android:id="@+id/rowImService"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="4dp" />

        <EditText
            android:id="@+id/rowImLabel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="4dp" />

        <EditText
            android:id="@+id/rowImValue"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

    </LinearLayout>

    <TextView
        android:id="@+id/rowImRemove"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:layout_marginStart="8dp"
        android:gravity="center"
        android:text="✕"
        android:textSize="18sp"
        android:clickable="true"
        android:focusable="true"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/contacts_field_remove_cd" />

</LinearLayout>
```

- [ ] **Step 2: Wire websites and IMs**

Add properties:

```kotlin
    private lateinit var websiteList: RepeatableFieldList<ContactUrlDto>
    private lateinit var imList: RepeatableFieldList<ContactImDto>
```

In `onCreate`:

```kotlin
        findViewById<ExpandableSectionView>(R.id.sectionOnline).setTitle(getString(R.string.contacts_section_online))
        websiteList = RepeatableFieldList(
            container = findViewById(R.id.websiteRowsContainer),
            addButton = findViewById(R.id.btnAddWebsite),
            rowLayoutRes = R.layout.row_contact_two_field,
            removeButtonId = R.id.rowFieldRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowFieldA)
                val valueField = rowView.findViewById<EditText>(R.id.rowFieldB)
                labelField.hint = getString(R.string.contacts_website_row_label_hint)
                valueField.hint = getString(R.string.contacts_website_row_value_hint)
                valueField.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                labelField.setText(item.label.orEmpty())
                valueField.setText(item.value)
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim().ifBlank { null },
                            value = valueField.text.toString().trim(),
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                valueField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.label.isNullOrBlank() && it.value.isBlank() },
            default = { ContactUrlDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionOnline).setItemCount(websiteList.items().size + imList.items().size) },
        )
        imList = RepeatableFieldList(
            container = findViewById(R.id.imRowsContainer),
            addButton = findViewById(R.id.btnAddIm),
            rowLayoutRes = R.layout.row_contact_im,
            removeButtonId = R.id.rowImRemove,
            bind = { rowView, item, onItemChanged ->
                val serviceField = rowView.findViewById<EditText>(R.id.rowImService)
                val labelField = rowView.findViewById<EditText>(R.id.rowImLabel)
                val valueField = rowView.findViewById<EditText>(R.id.rowImValue)
                serviceField.hint = getString(R.string.contacts_im_service_hint)
                labelField.hint = getString(R.string.contacts_im_label_hint)
                valueField.hint = getString(R.string.contacts_im_value_hint)
                serviceField.setText(item.service.orEmpty())
                labelField.setText(item.label.orEmpty())
                valueField.setText(item.value)
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            service = serviceField.text.toString().trim().ifBlank { null },
                            label = labelField.text.toString().trim().ifBlank { null },
                            value = valueField.text.toString().trim(),
                        ),
                    )
                }
                serviceField.addTextChangedListener(SimpleTextWatcher(emit))
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                valueField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.service.isNullOrBlank() && it.label.isNullOrBlank() && it.value.isBlank() },
            default = { ContactImDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionOnline).setItemCount(websiteList.items().size + imList.items().size) },
        )
```

In `loadExisting`:

```kotlin
            websiteList.setItems(dto.websites)
            imList.setItems(dto.ims)
            findViewById<ExpandableSectionView>(R.id.sectionOnline).setExpanded(dto.websites.isNotEmpty() || dto.ims.isNotEmpty())
            findViewById<ExpandableSectionView>(R.id.sectionOnline).setItemCount(dto.websites.size + dto.ims.size)
```

In `save()`, replace `websites = emptyList(),` and `ims = emptyList(),` with:

```kotlin
            websites = websiteList.items(),
```

and

```kotlin
            ims = imList.items(),
```

- [ ] **Step 3: Build, run all tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt \
        app/src/main/res/layout/row_contact_im.xml
git commit -m "feat(contacts): wire Online section (websites, IMs)"
```

---

### Task 10: Wire Personal section (birthday, events, relations)

**Files:**
- Create: `app/src/main/res/layout/row_contact_event.xml`
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt`

**Interfaces:**
- Consumes: `RepeatableFieldList` (Task 1/8), `row_contact_two_field.xml` (Task 1) for relations, `sectionPersonal`, `editContactBirthday`, `eventRowsContainer`, `btnAddEvent`, `relationRowsContainer`, `btnAddRelation` ids (Task 4).
- Produces: `row_contact_event.xml` with ids `rowEventLabel`, `rowEventDate`, `rowEventRemove`.

- [ ] **Step 1: Add the event row layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- app/src/main/res/layout/row_contact_event.xml -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:layout_marginBottom="8dp">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <EditText
            android:id="@+id/rowEventLabel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="4dp" />

        <EditText
            android:id="@+id/rowEventDate"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:focusable="false"
            android:clickable="true" />

    </LinearLayout>

    <TextView
        android:id="@+id/rowEventRemove"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:layout_marginStart="8dp"
        android:gravity="center"
        android:text="✕"
        android:textSize="18sp"
        android:clickable="true"
        android:focusable="true"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/contacts_field_remove_cd" />

</LinearLayout>
```

- [ ] **Step 2: Add a shared date-picker helper**

Add this private function to `ContactEditActivity` (right before `SimpleTextWatcher`):

```kotlin
    /** Wires [field] to open a [android.app.DatePickerDialog] on tap, pre-filled from [field]'s
     *  current `yyyy-MM-dd` text if present (else today), writing the picked date back as
     *  `yyyy-MM-dd` and invoking [onPicked]. [field] must have `focusable="false"` (see the row/
     *  section layouts) so tapping it opens the picker instead of the soft keyboard. */
    private fun wireDatePicker(field: EditText, onPicked: (String) -> Unit) {
        field.setOnClickListener {
            val calendar = java.util.Calendar.getInstance()
            val existing = field.text.toString().trim()
            if (existing.isNotBlank()) {
                runCatching {
                    val parts = existing.split("-").map { it.toInt() }
                    calendar.set(parts[0], parts[1] - 1, parts[2])
                }
            }
            android.app.DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    val formatted = "%04d-%02d-%02d".format(year, month + 1, dayOfMonth)
                    field.setText(formatted)
                    onPicked(formatted)
                },
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
            ).show()
        }
    }
```

- [ ] **Step 3: Wire birthday, events, relations**

Add properties:

```kotlin
    private lateinit var birthdayField: EditText
    private var birthdayValue: String? = null
    private lateinit var eventList: RepeatableFieldList<ContactEventDto>
    private lateinit var relationList: RepeatableFieldList<ContactRelationDto>
```

In `onCreate`:

```kotlin
        findViewById<ExpandableSectionView>(R.id.sectionPersonal).setTitle(getString(R.string.contacts_section_personal))
        birthdayField = findViewById(R.id.editContactBirthday)
        wireDatePicker(birthdayField) { picked -> birthdayValue = picked }
        eventList = RepeatableFieldList(
            container = findViewById(R.id.eventRowsContainer),
            addButton = findViewById(R.id.btnAddEvent),
            rowLayoutRes = R.layout.row_contact_event,
            removeButtonId = R.id.rowEventRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowEventLabel)
                val dateField = rowView.findViewById<EditText>(R.id.rowEventDate)
                labelField.hint = getString(R.string.contacts_event_label_hint)
                dateField.hint = getString(R.string.contacts_event_date_hint)
                labelField.setText(item.label.orEmpty())
                dateField.setText(item.date)
                // wireDatePicker's callback fires after field.setText(formatted) already ran (see
                // wireDatePicker below), so dateField.text is current by the time emit() reads it —
                // same live-read approach as every other multi-field row, avoiding the stale-item
                // closure bug (editing the label then picking a date must not drop the label edit).
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim().ifBlank { null },
                            date = dateField.text.toString(),
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                wireDatePicker(dateField) { emit() }
            },
            isBlank = { it.label.isNullOrBlank() && it.date.isBlank() },
            default = { ContactEventDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionPersonal).setItemCount(eventList.items().size + relationList.items().size) },
        )
        relationList = RepeatableFieldList(
            container = findViewById(R.id.relationRowsContainer),
            addButton = findViewById(R.id.btnAddRelation),
            rowLayoutRes = R.layout.row_contact_two_field,
            removeButtonId = R.id.rowFieldRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowFieldA)
                val valueField = rowView.findViewById<EditText>(R.id.rowFieldB)
                labelField.hint = getString(R.string.contacts_relation_row_label_hint)
                valueField.hint = getString(R.string.contacts_relation_row_value_hint)
                labelField.setText(item.label.orEmpty())
                valueField.setText(item.name)
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim().ifBlank { null },
                            name = valueField.text.toString().trim(),
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                valueField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.label.isNullOrBlank() && it.name.isBlank() },
            default = { ContactRelationDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionPersonal).setItemCount(eventList.items().size + relationList.items().size) },
        )
```

In `loadExisting`:

```kotlin
            birthdayValue = dto.birthday
            birthdayField.setText(dto.birthday.orEmpty())
            eventList.setItems(dto.events)
            relationList.setItems(dto.relations)
            findViewById<ExpandableSectionView>(R.id.sectionPersonal).setExpanded(
                dto.birthday != null || dto.events.isNotEmpty() || dto.relations.isNotEmpty(),
            )
            findViewById<ExpandableSectionView>(R.id.sectionPersonal).setItemCount(dto.events.size + dto.relations.size)
```

In `save()`, replace `birthday = null,`, `relations = emptyList(),`, and `events = emptyList(),` with:

```kotlin
            birthday = birthdayValue,
```

and

```kotlin
            relations = relationList.items(),
```

and

```kotlin
            events = eventList.items(),
```

- [ ] **Step 4: Build, run all tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt \
        app/src/main/res/layout/row_contact_event.xml
git commit -m "feat(contacts): wire Personal section (birthday, events, relations)"
```

---

### Task 11: Wire Notes relocation + Other section (custom fields)

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt`

**Interfaces:**
- Consumes: `RepeatableFieldList` (Task 1/8), `row_contact_two_field.xml` (Task 1), `sectionNotes`, `sectionOther`, `customFieldRowsContainer`, `btnAddCustomField` ids (Task 4).

- [ ] **Step 1: Give Notes a section title/expansion rule**

`notesField` is already wired from the original code (unchanged id `editContactNotes`, just now inside `sectionNotes`). Add, in `onCreate`:

```kotlin
        findViewById<ExpandableSectionView>(R.id.sectionNotes).setTitle(getString(R.string.contacts_section_notes))
```

In `loadExisting`, add:

```kotlin
            findViewById<ExpandableSectionView>(R.id.sectionNotes).setExpanded(!dto.notes.isNullOrBlank())
```

- [ ] **Step 2: Wire custom fields**

Add property:

```kotlin
    private lateinit var customFieldList: RepeatableFieldList<ContactCustomFieldDto>
```

In `onCreate`:

```kotlin
        findViewById<ExpandableSectionView>(R.id.sectionOther).setTitle(getString(R.string.contacts_section_other))
        customFieldList = RepeatableFieldList(
            container = findViewById(R.id.customFieldRowsContainer),
            addButton = findViewById(R.id.btnAddCustomField),
            rowLayoutRes = R.layout.row_contact_two_field,
            removeButtonId = R.id.rowFieldRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowFieldA)
                val valueField = rowView.findViewById<EditText>(R.id.rowFieldB)
                labelField.hint = getString(R.string.contacts_customfield_row_label_hint)
                valueField.hint = getString(R.string.contacts_customfield_row_value_hint)
                labelField.setText(item.label)
                valueField.setText(item.value)
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim(),
                            value = valueField.text.toString().trim(),
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                valueField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.label.isBlank() && it.value.isBlank() },
            default = { ContactCustomFieldDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionOther).setItemCount(customFieldList.items().size) },
        )
```

In `loadExisting`:

```kotlin
            customFieldList.setItems(dto.customFields)
            findViewById<ExpandableSectionView>(R.id.sectionOther).setExpanded(dto.customFields.isNotEmpty())
            findViewById<ExpandableSectionView>(R.id.sectionOther).setItemCount(dto.customFields.size)
```

In `save()`, replace the last remaining `customFields = emptyList(),` with:

```kotlin
            customFields = customFieldList.items(),
```

At this point every `mergedContactDto(...)` argument in `save()` should read from real UI state — no `null`/`emptyList()` placeholders from Task 3 remain except ones that were never placeholders to begin with (there are none; every field in this plan's scope is now wired).

- [ ] **Step 3: Build, run every test**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.urlxl.mail.contacts`
Expected: `BUILD SUCCESSFUL`, no failures — full regression check across every contacts-package instrumented test (Tasks 1–2's new tests, plus every pre-existing one: `ContactDaoOrderingTest`, `ContactSyncRaceRegressionTest`, etc.).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactEditActivity.kt
git commit -m "feat(contacts): wire Notes section title and Other section (custom fields)"
```

---

### Task 12: Manual on-device verification

**Files:** none (verification only).

- [ ] **Step 1: Install the build**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Create a contact exercising every field**

Open the app → Contacts → **+** → fill in: full name, given/family/middle name, prefix, suffix, nickname, phonetic given/family name, pronouns, org, job title, department, two emails (with labels), two phones (with labels), an address (all six sub-fields), a website, an IM (service+label+value), a birthday, an event, a relation, notes, and two custom fields. Save.

- [ ] **Step 3: Reopen and verify round-trip**

Tap back into the same contact. Confirm: every section that has data starts expanded; every field shows exactly what was entered, including both emails/phones/etc. (not just the first of each); the birthday and event date show the picked dates.

- [ ] **Step 4: Verify a single-field edit doesn't wipe anything**

Change only the notes field and save. Reopen — confirm every other field (from Step 2) is still intact. This is the regression check for the data-loss bug the prior fix addressed, now exercised across the full field set instead of just the original 5.

- [ ] **Step 5: Verify removal**

Reopen the contact, tap "✕" on one email row and one address row, save, reopen — confirm exactly those two entries are gone and everything else survived.

- [ ] **Step 6: Verify read-only badges**

Open the contact flagged `isSelf` on your account (per the earlier `isSelf` fix in this session) — confirm the "This is you" badge shows and there's no way to edit/toggle it from this screen. If any contact has a PGP key attached (via the QR exchange flow), confirm its badge shows similarly.

- [ ] **Step 7: Report results**

If every check in Steps 3–6 passes, this plan is complete. If anything fails, note exactly which field/section and revisit the corresponding task above — do not patch around it ad hoc.
