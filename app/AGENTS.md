# Purpose

Owns the Android app module build, manifest, source sets, resources, and test execution.

# Ownership

- Module: `app/`
- Build contract: `app/build.gradle.kts`
- Runtime package root: `app/src/main/java/com/urlxl/mail/`

# Local Contracts

- `sub`/`hash` pairing (Part 1 of `iOS_Mobile_notify.md`-style docs) is the single auth mechanism
  for every backend call the app makes: native push pull, contact sync (`/api/contacts/sync`), and
  mail relay (`/api/inbox`, `/api/inbox/folders`, `/api/inbox/actions`, `/api/mail/draft`,
  `/api/mail/send`). No bearer tokens, no cookies, no separate mobile login. (This corrects an
  earlier "no backend API calls from app runtime" claim here — native push registration/pull
  already called the backend before contact sync and mail relay were added.)
- Pairing proof material lives in a Keystore-backed `EncryptedSharedPreferences` file
  (`SecurePairingStore`), not plaintext DataStore — see `app/src/main/AGENTS.md` for the exact
  storage split. Non-secret sync state (cursors, delivery mode, history) is plaintext DataStore.
- Deep-link contract for pairing is `llamalabels://native-pair` with required `sub`, `hash`, `srv`,
  and `pt` params (`reg` optional). The legacy `novu-pair` scheme is removed entirely.
- Keep app behavior aligned with project goal: IMAP inbox read, SMTP send, keyword-based tab
  filtering, PLUS an alternate backend-relay connection mode (`MailConnectionMode.RELAY` in
  `MailSettings`, default `MANUAL_IMAP` so existing installs are unaffected) and two-way contact
  sync (`contacts/` package). A local Room database (`data/AppDatabase`) is the UI's read model for
  mail regardless of which connection mode supplied it, and the persistence layer for contacts.
- Prefer one existing dependency for both IMAP and SMTP.
- Avoid hardcoded secrets in committed files.
- For user-visible behavior changes, update this file or a closer child AGENTS.md.
- Contact autocomplete (ContactAutocomplete.md): `ComposeActivity`'s TO/CC/BCC fields are
  `RecipientInputView`s backed by `ContactDao.search` (name/email substring match, debounced
  150ms, top 5 shown). The address-book icon on the TO row opens `AddressBookSheet`
  (`contacts/` package), a `BottomSheetDialogFragment` offering TO/CC/BCC actions per contact.
  Both surfaces share `RecipientCandidate`/`RecipientField`/matching logic in
  `contacts/RecipientMatching.kt` — extend that file, don't duplicate matching logic in either UI
  layer.

# Work Guidance

- Choose the smallest diff that fixes root cause.
- Reuse existing classes and Android components before adding new abstractions.
- Keep background behavior explicit; document Android lifecycle limits.
- Mark intentional ceilings with `ponytail:` comments and upgrade path.

# Verification

- Run unit tests for logic changes under `app/src/test/`.
- Run unit tests for push parser/mapper changes under `app/src/test/`.
- Run Android instrumentation tests when UI/manifest behavior changes under `app/src/androidTest/`.

# Child DOX Index

- `app/src/main/` — Production Android code and resources. See [app/src/main/AGENTS.md](src/main/AGENTS.md).
- `app/src/test/` — JVM unit tests for deterministic app logic. See [app/src/test/AGENTS.md](src/test/AGENTS.md).
- `app/src/androidTest/` — Instrumented device/emulator tests. See [app/src/androidTest/AGENTS.md](src/androidTest/AGENTS.md).

