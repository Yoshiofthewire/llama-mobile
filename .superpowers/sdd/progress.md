# Contact isSelf Flag Progress Ledger

**Plan:** docs/superpowers/plans/2026-07-18-contact-self-flag.md
**Spec:** docs/superpowers/specs/2026-07-18-contact-self-flag-design.md
**Base commit:** 134fc80
**Start date:** 2026-07-18

## Tasks

- [x] Task 1: Add `isSelf` to `ContactDto`/`ContactEntity`/`ContactMappers`
- [ ] Task 2: Room migration 5→6 for the `isSelf` column
- [ ] Task 3: Sort the self-contact to the top of the contact list
- [ ] Task 4: Label the self-contact in the contact list UI
- [ ] Task 5: Add `contactCard` to the PGP QR key response model
- [ ] Task 6: Map a scanned `contactCard` to a `ContactDto`
- [ ] Task 7: Offer "Create New Contact" when a scan includes a contact card

## Completed

- Task 1: complete (commit f078a8a, spec ✅ quality ✅). Added `isSelf: Boolean = false` to `ContactDto`/`ContactEntity` (with `@ColumnInfo(defaultValue = "0")`), wired through `ContactMappers.kt`. Note: the implementer's subagent initially committed this to `main` in the original checkout instead of this worktree (same class of mistake as a prior plan) — recovered by cherry-picking the commit onto this branch and hard-resetting `main` back to `ef60e76`. Also discarded a stray `app/schemas/.../5.json` diff caused by KSP re-exporting the v5 schema mid-task (harmless — Task 2's version bump to 6 supersedes it).

## Final Whole-Branch Review

(pending)

## Prior plans (complete, superseded by this ledger)

### Security Fixes (WebView + Pairing)

Plan: docs/superpowers/plans/2026-07-17-security-fixes-webview-pairing.md — complete and
merged to main as of 2026-07-17. Task 1 (commit 33cba94): disabled JavaScript in the
email-reading WebView. Task 2 (commit 447b3bd): added an `AlertDialog` confirmation before
applying a deep-link pairing. Final review: ready to merge, no Critical/Important issues;
two Minor items noted and explicitly deferred (remote-image tracking-pixel loading,
deep-link intent not consumed after handling — both pre-existing/out-of-scope).

### Archive Subfolder Menu

Plan: docs/superpowers/plans/2026-07-17-archive-subfolder-menu.md — complete and merged to
main as of 2026-07-17 (commit 24200b9, landed directly on main rather than through its
worktree due to an implementer cwd mistake — see git history).
