# Move Folder Picker to Inbox Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Inbox/Junk/Trash folder switching off the clickable header dropdown and onto the bottom Inbox tab, leaving the header as a plain (non-clickable) label.

**Architecture:** `InboxActivity.kt` currently builds its folder-switching `PopupMenu` inline inside `setupHeaderFolderDropdown()`, anchored to the header `TextView`. Extract that popup construction into a shared `showFolderPickerPopup(anchor: View)` helper, call it from the bottom nav's Inbox tab (both the "selected" and "reselected" listener branches — see Task 1 for why both are needed), then delete the header's click handling entirely.

**Tech Stack:** Kotlin, `androidx.appcompat`, `com.google.android.material.bottomnavigation.BottomNavigationView`, `android.widget.PopupMenu`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-17-inbox-folder-nav-design.md`.
- No new strings, drawables, or dimens — reuse `R.string.nav_inbox` / `nav_junk` / `nav_trash` and the existing popup construction verbatim.
- `KeywordSettings`, `KeywordTabs`, `ComposeActivity`, `ContactsListActivity` are out of scope — do not touch.
- No automated test coverage exists for this navigation flow (confirmed: no `androidTest`/`test` file references `InboxActivity`, `headerFolderTitle`, or `setupHeaderFolderDropdown`), so each task's test cycle is a manual build-and-tap-through verification instead of an automated test, matching the precedent in `docs/superpowers/specs/2026-07-15-compose-email-beauty-pass-design.md`.
- Build command: `./gradlew assembleDebug` from the repo root.

---

### Task 1: Add the folder-picker popup to the Inbox tab

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/InboxActivity.kt`

**Interfaces:**
- Produces: `private fun showFolderPickerPopup(anchor: View)` — builds and shows the Inbox/Junk/Trash `PopupMenu` anchored to the given view, applying the chosen folder exactly as today (`currentFolder`, `selectedTab = KeywordTabs.ALL`, `applyFolderTitle()`, `refreshInbox()`). Task 2 will call this too.

- [ ] **Step 1: Extract the popup builder out of `setupHeaderFolderDropdown()`**

  Replace the existing `setupHeaderFolderDropdown()` function (currently around `InboxActivity.kt:472-496`):

  ```kotlin
      private fun setupHeaderFolderDropdown() {
          val headerTitle = findViewById<View>(R.id.headerFolderTitle)
          headerTitle.setOnClickListener {
              val popupMenu = PopupMenu(this, headerTitle)
              popupMenu.menu.add(0, 0, 0, getString(R.string.nav_inbox))
              popupMenu.menu.add(0, 1, 1, getString(R.string.nav_junk))
              popupMenu.menu.add(0, 2, 2, getString(R.string.nav_trash))

              popupMenu.setOnMenuItemClickListener { menuItem ->
                  val folder = when (menuItem.itemId) {
                      0 -> "INBOX"
                      1 -> "Junk"
                      2 -> "Trash"
                      else -> return@setOnMenuItemClickListener false
                  }
                  currentFolder = folder
                  selectedTab = KeywordTabs.ALL
                  applyFolderTitle()
                  refreshInbox()
                  bottomNav.selectedItemId = R.id.nav_inbox
                  true
              }
              popupMenu.show()
          }
      }
  ```

  with this (same popup logic, now a reusable helper, still wired to the header for this task):

  ```kotlin
      private fun showFolderPickerPopup(anchor: View) {
          val popupMenu = PopupMenu(this, anchor)
          popupMenu.menu.add(0, 0, 0, getString(R.string.nav_inbox))
          popupMenu.menu.add(0, 1, 1, getString(R.string.nav_junk))
          popupMenu.menu.add(0, 2, 2, getString(R.string.nav_trash))

          popupMenu.setOnMenuItemClickListener { menuItem ->
              val folder = when (menuItem.itemId) {
                  0 -> "INBOX"
                  1 -> "Junk"
                  2 -> "Trash"
                  else -> return@setOnMenuItemClickListener false
              }
              currentFolder = folder
              selectedTab = KeywordTabs.ALL
              applyFolderTitle()
              refreshInbox()
              bottomNav.selectedItemId = R.id.nav_inbox
              true
          }
          popupMenu.show()
      }

      private fun setupHeaderFolderDropdown() {
          val headerTitle = findViewById<View>(R.id.headerFolderTitle)
          headerTitle.setOnClickListener { showFolderPickerPopup(headerTitle) }
      }
  ```

- [ ] **Step 2: Wire the Inbox tab to open the same popup, guarded against firing on cold start**

  Replace the existing `setupBottomNav()` function (currently around `InboxActivity.kt:498-532`):

  ```kotlin
      private fun setupBottomNav() {
          bottomNav.setOnItemSelectedListener { item ->
              when (item.itemId) {
                  R.id.nav_inbox -> {
                      currentFolder = "INBOX"
                      selectedTab = KeywordTabs.ALL
                      applyFolderTitle()
                      refreshInbox()
                      true
                  }
                  // Return false for items that launch a separate screen: the tap still fires the
                  // action, but the nav selection stays on Inbox. Returning true would mark the item
                  // selected, and BottomNavigationView never re-fires the selected listener for an
                  // already-selected item — making the button dead after returning via back.
                  R.id.nav_compose -> {
                      startActivity(Intent(this, ComposeActivity::class.java))
                      false
                  }
                  R.id.nav_contacts -> {
                      startActivity(Intent(this, com.urlxl.mail.contacts.ContactsListActivity::class.java))
                      false
                  }
                  else -> false
              }
          }
          bottomNav.setOnItemReselectedListener { item ->
              if (item.itemId == R.id.nav_inbox) {
                  currentFolder = "INBOX"
                  selectedTab = KeywordTabs.ALL
                  applyFolderTitle()
                  refreshInbox()
              }
          }
          bottomNav.selectedItemId = R.id.nav_inbox
      }
  ```

  with this. `nav_compose`/`nav_contacts` always return `false` from the selected listener (see the existing comment), which means the Inbox tab is the *only* item `BottomNavigationView` ever marks selected — so a real user tap on it always arrives through `onItemReselectedListener`, not `onItemSelectedListener`. Both branches must open the popup for that reason; `isInitialSelection` exists solely to suppress the one programmatic selection at the bottom of this function so the popup doesn't appear on cold start:

  ```kotlin
      private fun setupBottomNav() {
          var isInitialSelection = false

          fun openFolderPickerFromTab() {
              val anchor = bottomNav.findViewById<View>(R.id.nav_inbox) ?: bottomNav
              showFolderPickerPopup(anchor)
          }

          bottomNav.setOnItemSelectedListener { item ->
              when (item.itemId) {
                  R.id.nav_inbox -> {
                      if (!isInitialSelection) {
                          openFolderPickerFromTab()
                      }
                      true
                  }
                  // Return false for items that launch a separate screen: the tap still fires the
                  // action, but the nav selection stays on Inbox. Returning true would mark the item
                  // selected, and BottomNavigationView never re-fires the selected listener for an
                  // already-selected item — making the button dead after returning via back.
                  R.id.nav_compose -> {
                      startActivity(Intent(this, ComposeActivity::class.java))
                      false
                  }
                  R.id.nav_contacts -> {
                      startActivity(Intent(this, com.urlxl.mail.contacts.ContactsListActivity::class.java))
                      false
                  }
                  else -> false
              }
          }
          bottomNav.setOnItemReselectedListener { item ->
              if (item.itemId == R.id.nav_inbox) {
                  openFolderPickerFromTab()
              }
          }
          isInitialSelection = true
          bottomNav.selectedItemId = R.id.nav_inbox
          isInitialSelection = false
      }
  ```

- [ ] **Step 3: Build**

  Run: `./gradlew assembleDebug`
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manually verify on a device/emulator**

  Install and launch the app, then check:
  - Cold launch lands on the Inbox folder and **no popup appears**.
  - Tapping the header title ("KyPost - Inbox") still opens the Inbox/Junk/Trash popup and each choice switches folders correctly (unchanged from before this task).
  - Tapping the bottom **Inbox tab** now *also* opens the same popup (new in this task), and choosing a folder from it works identically.
  - No crash when repeatedly tapping the Inbox tab or backgrounding/foregrounding the app.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/java/com/urlxl/mail/InboxActivity.kt
  git commit -m "feat: open folder picker from Inbox tab, not just header"
  ```

---

### Task 2: Remove the header dropdown

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/InboxActivity.kt`
- Modify: `app/src/main/res/layout/activity_inbox.xml`

**Interfaces:**
- Consumes: `showFolderPickerPopup(anchor: View)` from Task 1 — the Inbox tab is now the sole caller.

- [ ] **Step 1: Delete `setupHeaderFolderDropdown()` and its call site**

  Delete this function (added in Task 1, `InboxActivity.kt`):

  ```kotlin
      private fun setupHeaderFolderDropdown() {
          val headerTitle = findViewById<View>(R.id.headerFolderTitle)
          headerTitle.setOnClickListener { showFolderPickerPopup(headerTitle) }
      }
  ```

  And remove its call from `onCreate` (`InboxActivity.kt:104`):

  ```kotlin
          setupTabs()
          setupHeaderFolderDropdown()
          setupBottomNav()
  ```

  becomes:

  ```kotlin
          setupTabs()
          setupBottomNav()
  ```

- [ ] **Step 2: Drop the now-redundant selection sync inside `showFolderPickerPopup`**

  The popup is now only ever opened from the already-selected Inbox tab, so re-asserting the selection is dead weight. In the `showFolderPickerPopup` function added in Task 1, remove the `bottomNav.selectedItemId = R.id.nav_inbox` line:

  ```kotlin
              currentFolder = folder
              selectedTab = KeywordTabs.ALL
              applyFolderTitle()
              refreshInbox()
              bottomNav.selectedItemId = R.id.nav_inbox
              true
  ```

  becomes:

  ```kotlin
              currentFolder = folder
              selectedTab = KeywordTabs.ALL
              applyFolderTitle()
              refreshInbox()
              true
  ```

- [ ] **Step 3: Strip the clickable/dropdown styling from the header in the layout**

  In `app/src/main/res/layout/activity_inbox.xml`, the `headerFolderTitle` `TextView` (lines 13-27):

  ```xml
          <TextView
              android:id="@+id/headerFolderTitle"
              android:layout_width="match_parent"
              android:layout_height="wrap_content"
              android:paddingStart="16dp"
              android:paddingEnd="40dp"
              android:paddingTop="12dp"
              android:paddingBottom="12dp"
              android:textSize="18sp"
              android:textStyle="bold"
              android:clickable="true"
              android:focusable="true"
              android:background="?attr/selectableItemBackground"
              android:drawableEnd="@drawable/ic_arrow_drop_down"
              android:drawablePadding="8dp" />
  ```

  becomes:

  ```xml
          <TextView
              android:id="@+id/headerFolderTitle"
              android:layout_width="match_parent"
              android:layout_height="wrap_content"
              android:paddingStart="16dp"
              android:paddingEnd="16dp"
              android:paddingTop="12dp"
              android:paddingBottom="12dp"
              android:textSize="18sp"
              android:textStyle="bold" />
  ```

  (`paddingEnd` drops from `40dp` to the layout's standard `16dp` now that there's no trailing dropdown arrow to leave room for.)

- [ ] **Step 4: Build**

  Run: `./gradlew assembleDebug`
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manually verify on a device/emulator**

  Install and launch the app, then check:
  - Cold launch lands on the Inbox folder, header reads "KyPost - Inbox", **no popup appears**.
  - Tapping the header does nothing now — no ripple, no popup, no dropdown arrow visible.
  - Tapping the bottom Inbox tab still opens the Inbox/Junk/Trash popup; selecting Junk updates the header to "KyPost - Junk" and refreshes the list; selecting Trash likewise; selecting Inbox returns to the inbox view.
  - Navigate to Compose, press back, then tap the Inbox tab again — the popup still opens (confirms the reselect-listener path still works after the header removal, not just the first-selection path).
  - Keyword tab row (the `Chip`s below the header) still filters correctly after switching folders — unaffected by this change, but worth a glance since `applyFolderTitle()`/`refreshInbox()` are shared code paths.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/com/urlxl/mail/InboxActivity.kt app/src/main/res/layout/activity_inbox.xml
  git commit -m "refactor: remove header folder dropdown, Inbox tab is now the only entry point"
  ```
