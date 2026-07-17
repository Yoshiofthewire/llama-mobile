# Archive subfolders in the Inbox tab's folder picker

Date: 2026-07-17
Status: Approved

## Goal

The Inbox tab's folder-picker popup (`InboxActivity.showFolderPickerPopup`,
added in `2026-07-17-inbox-folder-nav-design.md`) offers Inbox/Junk/Trash.
Add a 4th item, "Archive", whose per-user archive subfolders live on the
mail server under the parent path `"Archive"`. Tapping "Archive" doesn't
switch folders directly — it opens a second popup listing the actual
subfolders (e.g. a path like `Archive/JohnDoe`), and picking one of those
switches to it.

The server-side capability already exists and is unused:
`MailSource.listFolders(parent: String?): MailOutcome<FolderListResult>`
(`MailSource.kt:87`), implemented by `RelayMailSource.listFolders` against
`GET /api/inbox/folders?parent=...` (`RelayMailSource.kt:78-93`), returning
`FolderListResult(parent, folders: List<FolderInfo(path, deletable)>)`.
Nothing on the server or in `RelayMailSource` needs to change.

## Add: `MailRepository.listFolders` passthrough

`MailRepository` (`MailRepository.kt`) doesn't expose `listFolders` yet —
add a one-line delegation matching the existing style of `send`/
`listAttachments`/`downloadAttachment`:

```kotlin
fun listFolders(parent: String?): MailOutcome<FolderListResult> = relaySource.listFolders(parent)
```

No dedicated unit test for this — none of `MailRepository`'s other
trivial passthroughs (`send`, `archive`, `spam`, `delete`, `markRead`,
`move`, `listAttachments`, `downloadAttachment`) have one either
(`MailRepositoryTest.kt` only covers `reconcileFetchResult`). Matches
existing convention; adding one here would be new, not restored, rigor.

## Change: `showFolderPickerPopup` gains a 4th item

Extract the repeated `currentFolder = ...; selectedTab = ALL;
applyFolderTitle(); refreshInbox()` block (currently inlined 3x across
`showFolderPickerPopup` in the prior spec's shipped code) into a
`switchFolder(folder: String)` helper, then add the Archive branch:

```kotlin
private fun switchFolder(folder: String) {
    currentFolder = folder
    selectedTab = KeywordTabs.ALL
    applyFolderTitle()
    refreshInbox()
}

private fun showFolderPickerPopup(anchor: View) {
    val popupMenu = PopupMenu(this, anchor)
    popupMenu.menu.add(0, 0, 0, getString(R.string.nav_inbox))
    popupMenu.menu.add(0, 1, 1, getString(R.string.nav_junk))
    popupMenu.menu.add(0, 2, 2, getString(R.string.nav_trash))
    popupMenu.menu.add(0, 3, 3, getString(R.string.nav_archive))

    popupMenu.setOnMenuItemClickListener { menuItem ->
        val folder = when (menuItem.itemId) {
            0 -> "INBOX"
            1 -> "Junk"
            2 -> "Trash"
            3 -> {
                fetchAndShowArchiveSubfolders(anchor)
                return@setOnMenuItemClickListener true
            }
            else -> return@setOnMenuItemClickListener false
        }
        switchFolder(folder)
        true
    }
    popupMenu.show()
}
```

## Add: fetch + second popup

```kotlin
private fun fetchAndShowArchiveSubfolders(anchor: View) {
    ioExecutor.execute {
        val outcome = mailRepository.listFolders(ARCHIVE_PARENT_FOLDER)
        runOnUiThread {
            if (outcome is MailOutcome.Success) {
                showArchiveSubfoldersPopup(anchor, outcome.value.folders)
            } else {
                val errorMessage = outcome.userFacingMessage()
                if (errorMessage != null) {
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

private fun showArchiveSubfoldersPopup(anchor: View, folders: List<FolderInfo>) {
    if (folders.isEmpty()) {
        Toast.makeText(this, R.string.no_archive_folders, Toast.LENGTH_SHORT).show()
        return
    }
    val popupMenu = PopupMenu(this, anchor)
    folders.forEachIndexed { index, folder ->
        popupMenu.menu.add(0, index, index, folder.path.substringAfterLast('/'))
    }
    popupMenu.setOnMenuItemClickListener { menuItem ->
        val folder = folders.getOrNull(menuItem.itemId) ?: return@setOnMenuItemClickListener false
        switchFolder(folder.path)
        true
    }
    popupMenu.show()
}
```

- `ioExecutor`/`runOnUiThread` mirrors `refreshInbox()`'s existing
  background-fetch-then-update pattern (`InboxActivity.kt:341-372`) —
  no new threading primitive introduced.
- Menu item ids are the folder's index in the returned list (no folder
  path needs encoding into an int id); the click handler looks the path
  back up by index via `folders.getOrNull(menuItem.itemId)`.
- Labels show the leaf segment (`path.substringAfterLast('/')`) so
  `Archive/JohnDoe` reads as "JohnDoe" in the popup, not the full path.
- `ARCHIVE_PARENT_FOLDER = "Archive"` — a new `private const val` in
  `InboxActivity`'s companion object (`InboxActivity.kt:644-652`),
  alongside `REFRESH_INTERVAL_MS` etc.
- Error handling reuses `userFacingMessage()` (`MailSource.kt:26`) with
  the exact same null-check-then-Toast shape `refreshInbox()` already
  uses (`InboxActivity.kt:368-370`) — no new error-presentation pattern.
- Empty-result case (`folders.isEmpty()`) shows a short Toast and does
  not open an empty popup.

## Change: `applyFolderTitle` recognizes Archive subfolder paths

```kotlin
private fun applyFolderTitle() {
    val folderLabel = when {
        currentFolder == "Junk" -> getString(R.string.nav_junk)
        currentFolder == "Trash" -> getString(R.string.nav_trash)
        currentFolder.startsWith("$ARCHIVE_PARENT_FOLDER/") -> currentFolder.substringAfterLast('/')
        else -> getString(R.string.nav_inbox)
    }
    val title = getString(R.string.inbox_heading, folderLabel)
    applyThemedTitle(this, title)
    headerFolderTitle.text = title
}
```

Switching to an Archive subfolder named `JohnDoe` shows "KyPost - JohnDoe"
in the header, consistent with how Junk/Trash show their bare name.

## New strings

`app/src/main/res/values/strings.xml`, alongside the existing `nav_*`
group and `loading_emails`/`finding_email` respectively:

```xml
<string name="nav_archive">Archive</string>
...
<string name="no_archive_folders">No archive folders found</string>
```

(Not reusing `action_archive` — that string is scoped to the per-email
swipe/detail archive action, a different concept from this folder-picker
label, even though both currently render as "Archive".)

## New import

`InboxActivity.kt` gains `import com.urlxl.mail.mail.FolderInfo`
(`MailOutcome` is already imported).

## Out of scope

- No caching/prefetching of the subfolder list — fetched fresh on every
  tap of "Archive". Simplest option; folder lists change rarely and the
  relay call is fast.
- No `createFolder`/`renameFolder`/`deleteFolder` UI, and no nesting
  beyond one level under Archive.
- `RelayMailSource`, the server, and `FolderEntity`/`FolderDao` (currently
  unused Room-side folder cache) are untouched — this feature reads
  straight from the relay each time, bypassing that dormant cache layer.

## Verification

No automated test covers this navigation flow (manual-only, matching the
prior Inbox-tab-nav spec's precedent — `InboxActivity` has no androidTest
coverage). Build and check by hand:

1. Tap the Inbox tab → popup shows Inbox / Junk / Trash / Archive.
2. Tap Archive → popup closes, a second popup opens listing the server's
   Archive subfolders by leaf name.
3. Pick a subfolder → header shows "KyPost - <name>", list refreshes to
   that folder's emails, keyword tabs reset to "All".
4. Tap Inbox tab again → back to the top-level Inbox/Junk/Trash/Archive
   popup (not stuck on the subfolder list).
5. Simulate a failure (e.g. temporarily break pairing) → tapping Archive
   shows a Toast with the relay's error message, no popup opens.
6. If the server returns zero Archive subfolders → short "No archive
   folders found" Toast, no empty popup.
