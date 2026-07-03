# llama mail for Android

llama mail for Android is a native Android email client focused on:
- IMAP inbox read
- SMTP send
- Keyword-based inbox tabs driven by IMAP user flags
- Best-effort 90-second inbox refresh while the inbox UI is in the foreground

## Current app behavior

- App entry is `MainActivity`, which routes to `SettingsActivity` on first launch if required mail settings are missing.
- After valid setup, the app opens `InboxActivity`.
- Inbox supports folders from bottom navigation: Inbox, Spam, and Trash.
- Compose flow is available in `ComposeActivity`.
- Email detail view loads full HTML/plain content in `EmailDetailActivity`.
- Swipe actions in inbox:
	- swipe right: delete message in current folder
	- swipe left: move message to `[Gmail]/All Mail`
- Keyword tabs are generated from IMAP user flags and can be hidden/shown in `KeywordSettingsActivity`.
- Theme selection is available in `ThemesActivity`.

## Runtime account setup (current default)

The app currently stores account settings in SharedPreferences, configured through the in-app Settings screen.

Required fields:
- IMAP host
- SMTP host
- Username
- Password

Defaults:
- IMAP port: `993`
- SMTP port: `587`
- IMAP folder: `INBOX`

## Optional build-time defaults

The Gradle build also supports injecting mail defaults into `BuildConfig` via Gradle properties.
This is optional and mainly useful for local development.

Set these in `~/.gradle/gradle.properties` (or untracked project-local Gradle properties):

```properties
mail.imap.host=imap.example.com
mail.imap.port=993
mail.smtp.host=smtp.example.com
mail.smtp.port=587
mail.username=user@example.com
mail.password=app-password
mail.imap.folder=INBOX
```

Do not commit real credentials.

## Build and test

Run JVM unit tests:

```sh
./gradlew testDebugUnitTest
```

Build debug APK:

```sh
./gradlew assembleDebug
```

Install debug APK to connected device/emulator:

```sh
./gradlew installDebug
```

Run instrumentation tests (device/emulator required):

```sh
./gradlew connectedDebugAndroidTest
```

## Tech snapshot

- Module: `app`
- Namespace/Application ID: `com.urlxl.mail`
- Min SDK: `31`
- Target SDK: `36`
- Compile SDK: `36` (minor API level `1`)
- Mail library: `org.eclipse.angus:jakarta.mail`

