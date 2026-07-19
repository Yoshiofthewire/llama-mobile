# Contact delete confirmation

## Problem
Tapping the delete button on the contact edit screen (`ContactEditActivity`) deletes the
contact immediately — from both the device and the server — with no confirmation step.
An accidental tap is unrecoverable.

## Change
Add a confirmation dialog before the existing delete logic runs. No change to the
deletion logic itself (`delete()` in `ContactEditActivity.kt`), only to how it's triggered.

- `btnDeleteContact`'s click listener shows an `AlertDialog.Builder` instead of calling
  `delete()` directly, matching the existing confirmation pattern used in
  `PushPairingActivity.kt` (title / message / positive / negative buttons).
- Title: "Delete contact?"
- Message: "This will permanently delete this contact from this device and the server.
  This can't be undone."
- Positive button ("Delete") calls the existing `delete()` function.
- Negative button ("Cancel") dismisses the dialog with no action.
- New strings added to `strings.xml` following the existing `pairing_confirm_*` naming
  convention (e.g. `contact_delete_confirm_title`, `contact_delete_confirm_message`,
  `contact_delete_confirm_positive`).

## Out of scope
- No changes to what `delete()` does or where it's called from — this is the only
  delete entry point in the app (confirmed by code search; there is no separate
  contact-list swipe-to-delete).
- No new reusable "ConfirmDialog" component — the codebase doesn't have one and
  consistently inlines `AlertDialog.Builder` at each call site, so this follows suit.

## Testing
Update/add a test in `ContactEditActivityTest.kt` asserting the dialog appears on tap
and that the underlying delete flow only fires after confirming.
