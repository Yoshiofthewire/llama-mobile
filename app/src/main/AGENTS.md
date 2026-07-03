# Purpose

Owns production Android app code and resources.

# Ownership

- Code: `app/src/main/java/com/urlxl/mail/`
- Resources: `app/src/main/res/`
- Manifest: `app/src/main/AndroidManifest.xml`

# Local Contracts

- Mail config (IMAP/SMTP host, port, credentials) is persisted in `SharedPreferences` and entered via `SettingsActivity`.
- On first run, app routes to `SettingsActivity` if settings are incomplete/missing.
- After configuration, app routes to `InboxActivity` by default; settings remain accessible via inbox menu.
- Required fields for mail config: IMAP host, SMTP host, username, password. Ports default to 993 (IMAP) and 587 (SMTP). IMAP folder defaults to "INBOX".
- Inbox tabs are derived from IMAP user flags (keywords) attached to messages.
- Keyword tuning is managed in `KeywordSettingsActivity` and persists hidden/visible keyword headings.
- Theme selection is managed in `ThemesActivity` and uses the shared theme name list based on `theme.ts` palettes.
- Keyword refresh is best-effort every 90 seconds while inbox UI is foregrounded.
- Background keyword staleness is accepted; app catches up on next foreground refresh.
- Use existing lightweight mail transport dependency for both IMAP and SMTP.

# Work Guidance

- Keep network off the main thread.
- Keep lifecycle-safe polling: start in foreground lifecycle, stop on background lifecycle.
- Prefer immutable model updates for inbox list and keyword tabs.
- Do not add new dependencies unless they reduce overall code size/complexity.

# Verification

- Add or update unit tests in `app/src/test/` for tab computation and filtering logic.
- Validate manifest registration when adding activities or permissions.

# Child DOX Index

- No child AGENTS.md files.
