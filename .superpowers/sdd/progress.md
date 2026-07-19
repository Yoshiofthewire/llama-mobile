# Contact Fields Editor Progress Ledger

**Plan:** docs/superpowers/plans/2026-07-18-contact-fields-editor.md
**Spec:** docs/superpowers/specs/2026-07-18-contact-fields-editor-design.md
**Base commit:** b0db7a1
**Start date:** 2026-07-18

## Tasks

- [x] Task 1: `RepeatableFieldList<T>` generic component
- [x] Task 2: `ExpandableSectionView` collapsible container
- [x] Task 3: Extend `mergedContactDto` for every newly-editable field
- [x] Task 4: Rewrite `activity_contact_edit.xml` with all sections
- [x] Task 5: Wire Name section + read-only `isSelf`/`pgpKey` badges
- [x] Task 6: Wire Work section
- [x] Task 7: Wire Contact section (full emails/phones lists)
- [x] Task 8: Wire Addresses section
- [ ] Task 9: Wire Online section (websites + IMs)
- [ ] Task 10: Wire Personal section (birthday, events, relations)
- [ ] Task 11: Wire Notes relocation + Other section (custom fields)
- [ ] Task 12: Manual on-device verification

## Notes

- Prior ledger content (contact-self-flag plan) removed here — that plan is
  complete and merged to `main` (see `git log` for `worktree-contact-self-flag`
  if that history is needed again).
- This worktree's branch was initially created from stale `origin/main` (the
  `EnterWorktree` default `baseRef`), then hard-reset to local `main` after
  discovering it was missing same-session work. Before that reset, three
  bug fixes made earlier in this session (contact-sync mutex, pairing-store
  crash recovery, ContactEditActivity data-loss fix) were still sitting
  *uncommitted* in the primary checkout — committed there as
  `e8bc116`/`e496af3`/`b0db7a1` before this worktree was re-synced. Recorded
  here so a future resume doesn't mistake those for part of this plan's task
  commits.
- The first Task 1 implementer subagent committed to `main` in the primary
  checkout instead of this worktree (same class of mistake the old,
  superseded ledger already warned about once). Caught immediately: reset
  `main` back to `b0db7a1` (safe — the commit `d0cb70c` stayed reachable via
  this worktree's branch, so nothing was lost), no re-implementation needed.
  Flagging again here since it's now happened on two separate plans in this
  repo — worth a harder fix (e.g. an explicit cwd check in the implementer
  prompt) if it recurs a third time.
- Task 1's reviewer found a real stale-closure data-loss bug in the plan's
  own example `bind` wiring (verbatim in the brief, not an implementer
  deviation) that would have propagated into Tasks 7/9/10/11 unchanged.
  Fixed in the plan directly (commit `e7d5e68`) before dispatching any of
  those tasks — see plan diff for the shared-`emit`-lambda pattern now used
  everywhere a row has more than one editable field.

## Completed

- Task 1: complete (commits b0db7a1..e60a065 — `d0cb70c` impl, `e60a065` fix
  for a themed-context test bug the implementer's own device run caught;
  `092a4da` interspersed is controller housekeeping, not part of this task).
  Review: spec ✅, quality Approved. One Important finding (the stale-closure
  bug above) — explicitly scoped by the reviewer as not blocking this task
  (lives in the test's example code, not in `RepeatableFieldList` itself) but
  actioned at the plan level regardless, see Notes above. Minor findings not
  fixed (all explicitly non-blocking, "nice to have"): two unused placeholder
  string resources (`contacts_row_a_hint`/`contacts_row_b_hint`, dead by
  design — real hints are set per-section in later tasks), one test name
  overstating its own coverage (`everyMutation_firesOnChanged`), one
  fully-qualified-type-instead-of-import style nit. Carry these three Minor
  items to the final whole-branch review for triage.

- Task 2: complete (commits cadba8d..782782b — `a486dd3` impl, `782782b` fix
  for the count-badge default-visibility gap the reviewer caught). Review:
  spec ✅, quality Approved, no Critical/Important issues. Reviewer explicitly
  checked for a Task-1-style stale-closure bug and found none (component has
  no text-field bind logic at all). One Minor→acted-on finding: `sectionHeaderCount`
  had no default visibility, so any section that never calls `setItemCount`
  (Name, Work, Notes — none have list fields) would show an empty visible
  badge; fixed via `android:visibility="gone"` default (`782782b`) rather than
  left as debt, since it would have visibly affected every remaining task.
  Other Minor findings not fixed (non-blocking): `setTitle`/`setItemCount`
  have no dedicated test coverage, one redundant cast in the test file, the
  multi-child-order-preservation path in `onFinishInflate` is only exercised
  with a single child. Carry to final whole-branch review for triage.

- Task 3: complete (commit `8aca186`). Review: spec ✅, quality Approved, no
  Critical/Important issues. `mergedContactDto` grew from 8 to 26 params,
  every one correctly threaded signature→`.copy()` with matching names;
  `save()`'s call site correctly left the brief-blessed `null`/`emptyList()`
  placeholders for the 18 fields Tasks 5-11 wire up one at a time. Minor,
  not fixed: `mergedContactDto`'s KDoc is now half-stale (still describes
  itself as pure field-preservation, doesn't mention it now applies real
  edits too) — worth a touch-up once Tasks 5-11 land, not before.
- Incident (milder than the ledger's earlier one): Task 3's implementer left
  a stray, uncommitted *duplicate* of its own change sitting in the primary
  checkout (`/home/yoshi/git/kypost-android`, not this worktree) on
  `ContactEditActivity.kt`/`ContactEditActivityTest.kt` — content-identical
  to what it correctly committed here as `8aca186`, so nothing was at risk
  of being lost, but it had to be discarded (`git checkout --` in the
  primary checkout) to avoid confusing a future session. No new commit
  landed on `main` this time (unlike the earlier incident), so this is
  narrower, but it's the third time in one plan a subagent has touched the
  wrong checkout in some way — the harder fix noted after the first incident
  (an explicit cwd assertion early in the dispatch prompt) is already in use
  and didn't fully prevent this one; worth raising with the user if a fourth
  instance occurs.

- Task 4: complete (commit `262db49`). Review: spec ✅, quality Approved, no
  Critical/Important issues. Reviewer independently re-ran
  `processDebugResources` (not just trusting the report) and cross-checked
  every id/string this layout produces against every reference Tasks 5-11's
  plan text makes — all present, none misspelled. One real bug in *this
  plan's own text* surfaced and was correctly fixed by the implementer: the
  brief's XML declared `xmlns:app` only on the first of two sibling `Chip`
  elements, but XML namespace declarations don't inherit across siblings —
  the second Chip's `app:chipMinHeight`/`app:ensureMinTouchTargetSize`
  wouldn't have resolved. Implementer added the declaration to both Chips;
  reviewer confirmed this was necessary and correct, not scope creep. Minor,
  not fixed: implementer's own report miscounted the string total (58
  claimed vs. 51 actual) — narrative-only, doesn't affect shipped code.
- This task's own build check (`processDebugResources`) intentionally
  doesn't compile Kotlin — `ContactEditActivity.kt` now references two ids
  this layout rewrite removed (`editContactEmail`/`editContactPhone`) and
  won't compile again until Task 7 rewires that section. Expected, per the
  plan's own design (each of Tasks 5-7 progressively fixes more of the
  compile break; full green isn't expected again until Task 7's checkpoint).
  Noting here so a resume doesn't mistake the current red Kotlin build for a
  regression.

- Task 5: complete (commit `48cb05c`). Review: spec ✅, quality Approved, no
  Critical/Important issues. Reviewer independently re-ran
  `compileDebugKotlin` and confirmed exactly the two expected dangling
  errors, nothing else. Read-only enforcement on `isSelf`/`pgpKey` verified
  both locally (no listeners on the badges) and structurally
  (`mergedContactDto` has no parameters for either field at all — not just
  "unused this task," architecturally unsettable from this screen). Minor,
  not fixed (plan-mandated, i.e. my own plan text's choice, not implementer
  error): the pgp badge uses a new string `contacts_pgp_badge_visible`
  ("PGP key on file") instead of reusing the existing
  `contact_status_secure_key` ("Secure key") `ContactAdapter.kt` already
  uses for the identical concept in the contacts list — two different
  labels for the same thing across two screens. Cosmetic only. Carry to
  final whole-branch review for triage.

- Task 6: complete (commit `727d699`). Review: spec ✅, quality Approved,
  zero findings of any severity — cleanest review yet. Reviewer independently
  re-ran `compileDebugKotlin`, confirmed exactly the two expected dangling
  errors.
- Incident (third occurrence, same class as before): Task 6's implementer
  again left a stray uncommitted duplicate of its own change in the primary
  checkout (`ContactEditActivity.kt`, 16 lines, based off the pre-Task-3
  baseline there). Content-consistent with what it correctly committed here
  as `727d699`; discarded via `git checkout --` in the primary checkout,
  `main` unaffected. This is the third incident in this plan of a subagent
  touching the wrong checkout in some way (1st: committed to main directly;
  2nd and 3rd: stray uncommitted duplicates, no bad commit). Per this
  ledger's own earlier note, flagging to the user is warranted at a fourth
  occurrence — noting here that we're now at three.

- Task 7: complete (commit `c69ee5d`). Review: spec ✅, quality Approved, no
  Critical/Important issues — no wrong-checkout incident this time either.
  This is the first task where `RepeatableFieldList` is actually used in
  production code and the first full-green compile checkpoint since Task
  4's layout rewrite; reviewer independently re-ran `compileDebugKotlin`
  (zero errors) and specifically re-verified the shared-`emit`-lambda
  closure fix was correctly applied to both `emailList` and `phoneList` (not
  reverted to the earlier-caught buggy two-separate-closures pattern) —
  quoted the exact lines for both. `isBlank` (AND across both sub-fields,
  not OR) confirmed correct for both lists. Implementer independently ran
  the full contacts-package instrumented suite: 9/9 passing. Minor, not
  fixed: class-level KDoc above `ContactEditActivity` is now stale (still
  describes the old single-value-with-overflow-preservation behavior this
  task replaced). Carry to final whole-branch review.

- Task 8: complete (commit `be46077`). Review: spec ✅, quality Approved,
  zero findings of any severity. No wrong-checkout incident. This row has
  six fields (vs. two for emails/phones), so the reviewer specifically
  checked the shared-`emit` closure spans all six, not just some —
  confirmed correct, all six `TextWatcher`s reference the same closure
  instance. `isBlank` correctly ANDs all six `isNullOrBlank()` checks.
  Independent `compileDebugKotlin` re-run: `BUILD SUCCESSFUL`.
