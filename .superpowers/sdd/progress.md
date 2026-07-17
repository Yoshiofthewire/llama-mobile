# Inbox Folder Nav Progress Ledger

**Plan:** docs/superpowers/plans/2026-07-17-inbox-folder-nav.md
**Spec:** docs/superpowers/specs/2026-07-17-inbox-folder-nav-design.md
**Base commit:** b1c8758
**Start date:** 2026-07-17

## Tasks

- [x] Task 1: Add the folder-picker popup to the Inbox tab
- [x] Task 2: Remove the header dropdown

## Completed

- Task 1: complete (commits b1c8758..97f6ef3, spec ✅ quality ✅). First review found a
  Critical bug not caused by the implementer: the brief's `isInitialSelection` guard only
  covered `OnItemSelectedListener`, but `BottomNavigationView` routes `setSelectedItemId()`
  through `OnItemReselectedListener` whenever the target is already selected — and
  `nav_inbox` is *always* selected in this app (Compose/Contacts return `false`). This made
  the popup fire on cold launch and reopen after every folder pick. Fixed by replacing the
  local flag with a class-level `suppressFolderPickerReentry` that guards both listener
  branches and both programmatic `selectedItemId` assignments (in `showFolderPickerPopup`
  and `setupBottomNav`). Re-review verified the fix against decompiled Material bytecode:
  Approved. Minor findings logged for final review: no try/finally around the flag window
  (`InboxActivity.kt` in `showFolderPickerPopup` and `setupBottomNav`), and the 3-line
  suppress-flag pattern is duplicated at both call sites — a small helper could dedupe it.

- Task 2: complete (commits 97f6ef3..1a01503, spec ✅ quality ✅). Deleted
  `setupHeaderFolderDropdown()` + call site, deleted the now-dead 3-line reentry-guard
  block inside `showFolderPickerPopup` (correcting the plan's stale Step 2 snippet, which
  predated Task 1's fix), stripped clickable/dropdown styling from `headerFolderTitle` in
  `activity_inbox.xml`. `setupBottomNav()` and the `suppressFolderPickerReentry` field
  verified untouched. This also resolves Task 1's "duplicated suppress pattern" Minor
  finding — only one call site remains now. Remaining open Minor from Task 1 (no
  try/finally around the flag window in `setupBottomNav`'s init lines) and a newly-noted
  one (orphaned `ic_arrow_drop_down` drawable, now unreferenced repo-wide) carried to the
  final whole-branch review.

## Final Whole-Branch Review

Ready to merge: With fixes → fixes applied (commit eb8278e). Reviewer (opus) confirmed
plan alignment, verified the Task 1 reentry fix architecturally sound, no Critical/Important
issues beyond one Important (missing WHY comment on `suppressFolderPickerReentry` — fixed)
and two Minor (try/finally around the flag window — fixed; dead `ic_arrow_drop_down.xml`
drawable — deleted). Build green after fixes. No automated test coverage for this flow,
confirmed as an intentional, spec-documented choice, not a gap.

## All Tasks Complete - Ready to Finish

## Prior plan (complete, superseded by this ledger)

Panel Radius Token (docs/superpowers/plans/2026-07-15-panel-radius-token.md) — all 4
tasks complete as of 2026-07-15, ready-to-merge per its final review. See git log for
commits 07c484a..2df7535. This ledger file is project-local git-ignored scratch shared
across plans, not a record of this branch's history.
