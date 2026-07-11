# Llama Mail for iOS & MacOS — Unified Native Build Specification

**Version:** 1.0  
**Date:** 2026-07-10  
**Platform:** iOS 15+ / MacOS 12+  
**Language:** Swift (SwiftUI recommended for cohesive iOS/MacOS parity)  
**Build System:** Xcode / Swift Package Manager  

---

## Overview

This document specifies the iOS and MacOS unified native port of **Llama Mail**, an email client with keyword-based inbox tabs, backend-relay support, contact sync, and native push notifications (MFA 2FA). The build targets feature parity with the Android reference implementation while adhering to native iOS/MacOS conventions.

Both iOS and MacOS share:
- Core business logic (network, data, auth)
- Identical data schemas and encryption
- Identical UI patterns (adapted to each platform's guidelines)
- Same 13-theme palette system

---

## Architecture Overview

### Core Layers

```
┌─────────────────────────────────────┐
│   Presentation (SwiftUI)            │
│  - iOS views + MacOS views          │
│  - View models                      │
│  - Navigation/routing               │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│   Application / Coordination        │
│  - SingletonGraph (DI)              │
│  - Lifecycle management             │
│  - Notification handling            │
│  - Deep linking                     │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│   Domain / Repository Layer         │
│  - MailRepository (IMAP/Relay mode) │
│  - PushRepository (notification log)│
│  - ContactSyncRepository            │
│  - KeywordRepository                │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│   Network & Data Sources            │
│  - MailSource (protocol)            │
│    - ImapMailSource                 │
│    - RelayMailSource                │
│  - PushNotificationClient           │
│  - NativeRegistrationClient         │
│  - ContactSyncClient                │
│  - MfaResponseClient                │
│  - HTTP / WebSocket transport       │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│   Local Storage (SQLite/Core Data)  │
│  - AppDatabase (GRDB or Core Data)  │
│  - SecureStorage (Keychain)         │
│  - UserDefaults (non-sensitive)     │
│  - FileManager (cached files)       │
└─────────────────────────────────────┘
```

### Package Structure

```
Sources/LlamaMail/
├── App/
│   ├── LlamaApp.swift           # Main app delegate, window scenes
│   ├── AppDelegate.swift         # UIKit + lifecycle for notifications
│   ├── SingletonGraph.swift      # Dependency injection container
│   └── Config.swift
│
├── Presentation/
│   ├── iOS/
│   │   ├── Screens/
│   │   │   ├── MainTabView.swift
│   │   │   ├── InboxView.swift
│   │   │   ├── EmailDetailView.swift
│   │   │   ├── ComposeView.swift
│   │   │   ├── SettingsView.swift
│   │   │   ├── ThemesView.swift
│   │   │   ├── KeywordSettingsView.swift
│   │   │   ├── ContactsListView.swift
│   │   │   ├── ContactDetailView.swift
│   │   │   ├── PushPairingView.swift
│   │   │   └── MfaApprovalView.swift
│   │   ├── Components/
│   │   │   ├── EmailListRow.swift
│   │   │   ├── KeywordTabView.swift
│   │   │   ├── AvatarView.swift
│   │   │   ├── PrimaryButton.swift
│   │   │   ├── SecondaryButton.swift
│   │   │   ├── EmptyStateView.swift
│   │   │   └── StatusBadgeView.swift
│   │   └── Style/
│   │       └── Colors.swift (theme palette)
│   │
│   ├── MacOS/
│   │   ├── Windows/
│   │   │   ├── MainWindow.swift
│   │   │   └── PreferencesWindow.swift
│   │   ├── Views/
│   │   │   ├── SidebarView.swift
│   │   │   ├── InboxContentView.swift
│   │   │   └── DetailPanelView.swift
│   │   └── Components/
│   │       └── (shared from iOS where possible)
│   │
│   └── Shared/
│       ├── ViewModels/
│       │   ├── InboxViewModel.swift
│       │   ├── EmailDetailViewModel.swift
│       │   ├── ComposeViewModel.swift
│       │   ├── SettingsViewModel.swift
│       │   ├── ThemesViewModel.swift
│       │   ├── ContactsViewModel.swift
│       │   ├── PushPairingViewModel.swift
│       │   └── MfaApprovalViewModel.swift
│       └── Navigation/
│           ├── NavigationRouter.swift
│           └── DeepLinkHandler.swift
│
├── Domain/
│   ├── Repositories/
│   │   ├── MailRepository.swift
│   │   ├── PushRepository.swift
│   │   ├── ContactSyncRepository.swift
│   │   ├── KeywordRepository.swift
│   │   └── SettingsRepository.swift
│   │
│   ├── UseCases/
│   │   ├── FetchInboxUseCase.swift
│   │   ├── SendEmailUseCase.swift
│   │   ├── SyncContactsUseCase.swift
│   │   ├── HandlePushNotificationUseCase.swift
│   │   └── ApproveMfaChallengeUseCase.swift
│   │
│   └── Models/
│       ├── Email.swift
│       ├── Contact.swift
│       ├── PushNotification.swift
│       ├── MfaChallenge.swift
│       ├── MailSettings.swift
│       └── KeywordSettings.swift
│
├── Data/
│   ├── Networking/
│   │   ├── HTTPClient.swift         # URLSession wrapper + OkHttp equivalent
│   │   ├── NativeRegistrationClient.swift
│   │   ├── PushNotificationClient.swift
│   │   ├── ContactSyncClient.swift
│   │   ├── MfaResponseClient.swift
│   │   └── Request+Response models
│   │
│   ├── Mail/
│   │   ├── MailSource.swift         # Protocol
│   │   ├── ImapMailSource.swift     # IMAP/SMTP via lightweight library
│   │   ├── RelayMailSource.swift    # Backend relay endpoints
│   │   └── MailGateway.swift        # IMAP/SMTP transport
│   │
│   ├── Database/
│   │   ├── AppDatabase.swift        # GRDB or Core Data
│   │   ├── Models/
│   │   │   ├── EmailEntity.swift
│   │   │   ├── ContactEntity.swift
│   │   │   ├── PushNotificationEntity.swift
│   │   │   ├── KeywordEntity.swift
│   │   │   └── (DAO equivalents)
│   │   └── Migrations/
│   │       └── (schema versions)
│   │
│   ├── Storage/
│   │   ├── KeychainStorage.swift    # SecureEnclaveOnly-backed pairing
│   │   ├── UserDefaultsStorage.swift
│   │   ├── FileSystemStorage.swift
│   │   └── SecurePairingStore.swift # Keychain wrapper
│   │
│   └── Mappers/
│       ├── EmailMapper.swift
│       ├── ContactMapper.swift
│       ├── PayloadMapper.swift
│       └── (Android equiv: *.Mapper classes)
│
├── Utilities/
│   ├── Logger.swift
│   ├── DateFormatter+Extensions.swift
│   ├── String+Extensions.swift
│   ├── Color+Extensions.swift
│   └── URLSession+Extensions.swift
│
└── Resources/
    ├── Localizable.strings        # i18n (same keys as Android)
    ├── Info.plist                 # Bundle config
    ├── Fonts/
    │   ├── SpaceGrotesk-*.ttf     # Downloadable from Google Fonts
    │   └── IBMPlexMono-*.ttf
    └── Theme/
        └── ThemePalettes.json      # Shared with web: theme.ts colors
```

---

## Feature Specifications

### 1. Authentication & Pairing

#### Deep Link Pairing
- **Scheme:** `llamalabels://native-pair`
- **Query Parameters:** `sub`, `hash`, `srv`, `pt` (required), `reg` (optional)
- **Handler:** `DeepLinkHandler` → `PushPairingViewModel`
- **Validation:** All required params must be present and non-empty

**Flow:**
```
Deep link tap / QR scan
  ↓
DeepLinkHandler.handleURL()
  ↓
PushPairingViewModel.parsePairingLink()
  ↓
Validate params → Show error if invalid
  ↓
Register FCM token (iOS: APNs) via NativeRegistrationClient
  ↓
SecurePairingStore.savePairing() if registration succeeds
  ↓
Navigate to inbox (or show error toast)
```

#### Secure Storage
- **Keychain Storage:** 
  - `SecurePairingStore` wraps Keychain (CryptoKit, Secure Enclave when available)
  - Keys: `sub`, `hash`, `srv`, `registrationUrl`, `pairingToken`, `lastDeviceId`, `pairedAtTimestamp`
  - Item access control: `.thisDeviceOnly` + `.biometryCurrentSet`
  - Encryption: Keychain handles automatically (no manual encryption needed)

- **UserDefaults** (plaintext, no sensitive data):
  - Mail settings (IMAP host/port, SMTP host/port, username — **NOT password**)
  - Keyword visibility toggle state
  - Selected theme name
  - Last-read notification cursor
  - Notification history (non-sensitive summary)

#### Connection Modes
- **Manual IMAP:** Credentials entered in SettingsView, stored in Keychain
  - IMAP host, IMAP port (default 993), SMTP host, SMTP port (default 587)
  - Username, password (Keychain)
  - IMAP folder (default "INBOX")

- **Relay Mode:** Requires prior pairing via QR
  - No separate login: authenticated via `sub`/`hash` from pairing
  - Server URL sourced from pairing (`srv`), never edited by user
  - Mail fetched via relay endpoints (not direct IMAP)

#### Mail Selection State
- Stored in UserDefaults: `mailConnectionMode` → `"manual_imap"` | `"relay"`
- `MailSettings.getConnectionMode()` / `.setConnectionMode()`
- Default: `manual_imap`

---

### 2. Mail Capabilities

#### IMAP/SMTP (Manual Mode)
- **Library:** Use lightweight, pure-Swift IMAP/SMTP lib (e.g. Pantomime fork, or roll minimal stack)
  - No heavy dependencies (Alamofire, RxSwift unnecessary here)
  - Must support: IMAP SEARCH with KEYWORD, IMAP FLAGS, SMTP AUTH, TLS
  
- **MailGateway.swift** wraps the transport:
  ```swift
  protocol MailGateway {
    func connect(host: String, port: Int, username: String, password: String) async throws
    func listFolders() async throws -> [MailFolder]
    func fetch(folder: String, from: Int, to: Int) async throws -> [Email]
    func search(folder: String, query: String) async throws -> [EmailId]
    func setFlags(folder: String, messageId: Int, flags: Set<String>) async throws
    func send(to: [String], cc: [String], bcc: [String], subject: String, body: String) async throws
    func disconnect() async throws
  }
  ```

- **Thread Safety:** No sync operations on main thread; use `async/await`

#### Relay Mode
- **Endpoints** (all use `sub`/`hash` query-param auth):
  ```
  GET  /api/relay/folders?sub=<sub>&hash=<hash>
  GET  /api/relay/folder?sub=<sub>&hash=<hash>&folder=<folder>&from=<from>&to=<to>
  GET  /api/relay/search?sub=<sub>&hash=<hash>&folder=<folder>&query=<query>
  POST /api/relay/send?sub=<sub>&hash=<hash>
       body: { to: [string], cc: [string], bcc: [string], subject: string, body: string }
  ```

- **RelayMailSource** implements `MailSource` protocol:
  ```swift
  protocol MailSource {
    func listFolders() async throws -> [MailFolder]
    func fetchEmails(folder: String, from: Int, to: Int) async throws -> [Email]
    func search(folder: String, query: String) async throws -> [Int]
    func setKeywords(folder: String, messageId: Int, keywords: [String]) async throws
    func send(email: Email) async throws
  }
  ```

- **Response Mapping:** Backend relay responses include `tab`/`label` fields (differ from keyword-based tabs in IMAP mode)

#### Inbox Tabs
- **Manual IMAP Mode:** Derive tabs from IMAP user flags (KEYWORD tokens on messages)
  - Computed from `Email.keywords: Set<String>`
  - Cached in `KeywordRepository`
  - Best-effort 90-second refresh while inbox UI is in foreground
  - Background staleness accepted (catch-up on resume)

- **Relay Mode:** Tabs sourced from server response `tab` / `label` fields
  - Not keyword-based; direct server-pushed tabs
  - Refresh on same 90-second cadence

#### Keyword Settings
- `KeywordSettingsView` allows show/hide toggling per keyword
- Persisted in `KeywordRepository` (UserDefaults backing)
- Filtered list shown in `InboxView` tab bar

---

### 3. Native Push Notifications

#### Registration & Pairing Flow
1. App calls `NativeRegistrationClient.register(fcmToken: String)` on:
   - First launch (after asking notification permission)
   - FCM token refresh (APNs token change listener)
   - After pairing QR scan success
   - On app foreground (re-registration for safety)

2. Backend response:
   ```json
   {
     "ok": true,
     "synced": true,
     "deviceId": "...",
     "deliveryMode": "push|pull",
     "pullEndpoint": "https://..."  // optional; derived as {srv}/api/notifications/native/pull if absent
   }
   ```

3. On success: `SecurePairingStore.savePairing()` + device marked paired
4. On 401/403: Show error, prompt re-scan
5. On 503: Show persistent error (backend config issue, cannot retry)

#### APNs Configuration (iOS)
- **Certificate/Key:** Uploaded to Apple Developer + Firebase project
- **App ID:** Must have Push Notifications entitlement
- **Provisioning Profile:** Must include Push Notifications capability
- **Payload Handling:** Same format as Android FCM data
  ```json
  {
    "aps": {
      "alert": { "title": "...", "body": "..." },
      "badge": 1,
      "sound": "default"
    },
    "messageId": "...",
    "senderName": "...",
    "emailSubject": "...",
    "Keywords": ["Important", "Work"]
  }
  ```

#### Push Notification Dispatcher
- **Normal Notifications:** Show system notification + add to in-app history
  - Tap → navigate to inbox with `messageId` parameter
  - History stored in `PushNotificationEntity` (Room equivalent)

- **MFA Challenges:** High-priority channel (UNNotificationPresentationOptions with `.banner` + `.sound`)
  - Payload: `{ type: "mfa_challenge", challengeId: "...", ... }`
  - Show "Approve" / "Deny" action buttons
  - Actions → `MfaResponseClient.respond(challengeId, approved: Bool)`
  - Fallback: Tap notification → `MfaApprovalView` (in-app approval UI)

- **Notification Channels / Categories:**
  ```swift
  // Normal mail notifications
  let mailCategory = UNNotificationCategory(
    identifier: "MAIL_NOTIFICATION",
    actions: [],  // no direct actions; tap opens inbox
    intentIdentifiers: [],
    options: [.customDismissAction, .foreground]
  )
  
  // MFA challenges
  let mfaCategory = UNNotificationCategory(
    identifier: "MFA_CHALLENGE",
    actions: [
      UNNotificationAction(identifier: "APPROVE", title: "Approve", options: []),
      UNNotificationAction(identifier: "DENY", title: "Deny", options: [.destructive])
    ],
    intentIdentifiers: [],
    options: [.customDismissAction]
  )
  ```

#### Notification Permission
- Request `UNUserNotificationCenter.requestAuthorization` at first app launch
- If denied: payload still parsed/saved to in-app history, but no system notification badge/alert
- Prompt user to enable in Settings if needed

#### Delivery Modes

**Push Mode (default):**
- Server sends to APNs; app wakes and processes notification
- System notification shown (if permission granted)
- In-app history updated

**Pull Mode:**
- Server does NOT send to APNs
- App polls `GET /api/notifications/native/pull?sub=<sub>&hash=<hash>&after=<cursor>`
- Poll cadence:
  - Foreground: Every 90 seconds (via Timer, same as keyword refresh)
  - Background: `URLSessionConfiguration.backgroundSessionConfiguration` + `DispatchSourceTimer` or `ProcessInfo.performExpiringActivity` every 15 min (platform minimum)
  - On app foreground: Immediate pull
  - On pairing success: Immediate pull

- Poll response:
  ```json
  {
    "notifications": [
      { "seq": 1, "messageId": "...", "senderName": "...", ... }
    ],
    "cursor": 2  // updated cursor position
  }
  ```
  De-duplication: by strictly-increasing `seq`  
  Cursor persistence: Advance to `max(lastCursor, response.cursor)` only after notifications are handed off

---

### 4. Contact Sync

#### Sync Client
- `ContactSyncClient` (HTTP OkHttp equiv: URLSession)
- Endpoint: `POST /api/contacts/sync?sub=<sub>&hash=<hash>`
- Auth: same `sub`/`hash` query-param as native register/pull
- Request body: `{ "delta": [...], "cursor": 123 }`
- Response: `{ "delta": [...], "cursor": 456 }`

#### Repository & Reconciliation
- `ContactSyncRepository` applies deltas into `AppDatabase`
- `ContactSyncReconciliation` reconciles locally-created contacts' server-assigned `uid`
  - No correlation ID in v1: matched by content/order
  - See Android: `ContactSyncReconciliation.kt`
- Cursor persisted in `ContactCursorStore` (UserDefaults-backed)

#### Entry Point
- Inbox overflow menu → "Contacts" → `ContactsListView`
- Bottom nav (if UI refactor) unchanged; keep 4 fixed items

#### UI
- `ContactsListView`: List of contacts with avatars (gradient + initials)
- `ContactDetailView`: Full contact editing
- Empty state: dashed border + muted text (follows style guide §4)

---

### 5. MFA 2FA (Push Approval)

#### Challenge Flow
1. Incoming push payload: `{ type: "mfa_challenge", challengeId: "...", ... }`
2. `PushNotificationDispatcher.showMfaChallenge()`
3. Show high-priority notification with "Approve" / "Deny" buttons
4. On button tap: `MfaResponseClient.respond(challengeId, approved: Bool)`
   - POST to `{serverUrl}/api/mfa/push/respond`
   - Auth: `sub`/`hash` query params
5. Handle response:
   - `200`: Success; close notification
   - `403`/`409`: Backend rejection (show toast explaining why)
   - Network error: Show retry option

#### Fallback UI
- If notification action broadcast fails (OEM restrictions), tap notification body → `MfaApprovalView`
- Same approve/deny buttons in app
- Both paths call `MfaResponseClient.respond()`

#### Storage
- Challenge history (optional): Persist in `PushNotificationEntity` for audit
- Sensitive data: Do NOT store challenge secret/token longer than needed

---

### 6. Themes & Styling

#### Theme System
- **13 Named Themes:** `Dark Matter`, `Light Matter`, `Tropics`, `Tropic Night`, `Ocean`, `Coffee`, `White Cliffs`, `Cyber Punk`, `Neon Purple`, `Space`, `Sky`, `Forest`, `Sun`
- **Default:** `Dark Matter`
- **Palette Fields:** `bg`, `panel`, `ink`, `inkStrong`, `accent`, `line` (minimum); extend to `accentSoft` only if avatar gradient needs it
- **Source of Truth:** Identical values across web (`theme.ts`) and mobile (iOS: `AppTheme.swift` / `ThemePalettes.json`, Android: `AppTheme.kt`)
- **Selection:** `ThemesView` allows user to pick theme; persisted in UserDefaults as theme name

#### Typography
- **UI Text (buttons, labels, toolbar):** Space Grotesk (via Google Fonts downloadable)
- **Code/Email Body:** IBM Plex Mono
- **System Font Scale:** Always `sp` equivalent; respect system accessibility text size
- **Downloadable Fonts:** Configured via `Info.plist` with Google Fonts provider; zero APK/app size added

#### Shape Language
| Element | Radius |
|---------|--------|
| Input field | 14dp (panel-like) |
| Primary/Secondary button | 10dp (not pill/stadium) |
| Card/Panel | 14dp |
| Modal / bottom sheet | 14dp on top corners |
| Tab/Toggle/Badge | stadium (50% of height, or `cornerRadius = height/2`) |
| Avatar | circle (`OVAL` / `.circular()` in SwiftUI) |

#### Color Semantics (Fixed, theme-invariant)
| Role | Value |
|------|-------|
| Danger / delete | `#ff5f5f` swipe, `rgba(255,180,171,.4)` border/fill |
| Warning / archive | `#ffd64d` swipe |
| Success / active status | `#7bbf7b` border, `#a5dca5` text |
| Inactive status | Use `line`/`panel`/`ink` from active palette |

#### Component Patterns
- **Primary Button:** Solid accent fill, 10dp radius, readable text
- **Ghost/Secondary Button:** Transparent, 1dp line stroke, strong ink text
- **Danger Button:** 1dp stroke + 12% fill (danger red), not theme accent
- **Pill Filter Tabs:** Stadium shape, inactive=transparent+line stroke, active=accent fill
- **Status Badge:** Pill outline + leading circular dot (success/inactive colors)
- **Avatar:** 34dp (list) / 52dp (detail), two-stop accent gradient, 1dp border, initials
- **Empty State:** Dashed 1dp border, accent-tinted line, centered muted text, 10dp radius

#### Motion
- **Transitions:** 120–240ms, use `spring()` or `easeInOut` (native curves, not web bezier)
- **Ripple on Touch:** Native iOS feedback (haptics + opacity), not web's `translateY`

#### Platform Adaptation
- **iOS:** Sheet modal from bottom, standard AlertDialog-equiv
- **MacOS:** Floating window, standard dialog (not left sidebar copy from web)
- **Navigation:** Toolbar + overflow menu (iOS), menu bar + sidebar (MacOS)
- **Status/Nav Bar:** Edge-to-edge theming via `ignoresSafeArea()` in SwiftUI or `setStatusBarStyle()` + `navigationBar.tintColor` in UIKit

---

### 7. Compose & Email Editing

#### SendEmailUseCase
```swift
struct Email {
  var to: [String]
  var cc: [String]
  var bcc: [String]
  var subject: String
  var body: String
  var attachments: [AttachmentRef]
  // in Relay mode: also includes `tab`/`label` for categorization
}
```

#### UI Flow (iOS)
- `ComposeView` (SwiftUI sheet modal)
- Text fields: To, Cc, Bcc, Subject, Body
- Send button calls `SendEmailUseCase`
- Error handling: Show toast on failure, keep draft in memory (not auto-save to DB)

#### Relay Mode Notes
- Request body to `/api/relay/send` uses comma-string format:
  ```json
  { "to": "a@x.com, b@x.com", "cc": "...", "bcc": "...", "subject": "...", "body": "..." }
  ```
  (Not array; backend API contract)
- Response: `{ "ok": true }` or error
- No attachment support in v1 (no UI for it yet)

---

### 8. Database (Local Cache)

#### Schema & Entities
Use **GRDB** (lightweight, type-safe SQLite) or **Core Data**:
- **Recommended:** GRDB (simpler, more explicit than Core Data, no ORM magic)
- **Fallback:** Core Data (if team prefers, more UIKit-idiomatic)

#### Tables
```sql
-- Emails
CREATE TABLE emails (
  id INTEGER PRIMARY KEY,
  server_id TEXT UNIQUE,  -- IMAP UID or Relay ID
  folder TEXT NOT NULL,
  sender_name TEXT,
  sender_email TEXT,
  subject TEXT,
  body TEXT,
  keywords TEXT,  -- JSON array or comma-separated
  received_at TIMESTAMP,
  read BOOLEAN,
  starred BOOLEAN,
  created_at TIMESTAMP
);

-- Contacts
CREATE TABLE contacts (
  id INTEGER PRIMARY KEY,
  uid TEXT UNIQUE,  -- Server-assigned UID from sync
  name TEXT,
  email TEXT,
  phone TEXT,
  avatar_url TEXT,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);

-- Push Notifications (history)
CREATE TABLE push_notifications (
  id INTEGER PRIMARY KEY,
  seq INTEGER UNIQUE,
  message_id TEXT,
  sender_name TEXT,
  email_subject TEXT,
  keywords TEXT,
  received_at TIMESTAMP,
  read BOOLEAN
);

-- Keywords
CREATE TABLE keywords (
  id INTEGER PRIMARY KEY,
  name TEXT UNIQUE,
  visible BOOLEAN DEFAULT 1,
  created_at TIMESTAMP
);
```

#### Access Patterns (DAOs)
```swift
protocol EmailDAO {
  func replaceFolderSnapshot(folder: String, emails: [Email]) async throws
  func getFolder(folder: String, limit: Int, offset: Int) async throws -> [Email]
  func getEmail(id: Int) async throws -> Email?
  func updateEmail(id: Int, read: Bool, starred: Bool) async throws
  func search(folder: String, query: String) async throws -> [Email]
}

protocol ContactDAO {
  func upsert(contacts: [Contact]) async throws
  func delete(uid: String) async throws
  func listAll() async throws -> [Contact]
  func getContact(uid: String) async throws -> Contact?
}

protocol PushNotificationDAO {
  func insert(notification: PushNotification) async throws
  func listHistory(limit: Int) async throws -> [PushNotification]
  func markRead(id: Int) async throws
}
```

---

### 9. Settings & Configuration

#### SettingsView Layout (iOS)
```
─────────────────────────────────
 Mail Connection Mode
   ○ Manual IMAP   
   ○ Relay (Paired)

 [If Manual IMAP selected:]
   IMAP Configuration
   - Host: [text field]
   - Port: [93|text field]
   - Username: [text field]
   - Password: [secure field]
   
   SMTP Configuration
   - Host: [text field]
   - Port: [587|text field]
   
   [Save] [Test Connection]

 [If Relay selected & paired:]
   Connected via: example.com
   Device ID: ...
   [Re-pair QR]

─────────────────────────────────
 Theme Selection
   > Dark Matter (current)

 Keyword Settings
   > [Show/hide keywords per tab]

 Notifications
   ☑ Enable system notifications
   > Delivery Mode: Push / Pull

─────────────────────────────────
 About
   Version 1.0
```

#### KeywordSettingsView
- Toggle visibility per keyword
- Persisted in `KeywordRepository`
- Shown in inbox tab bar if visible

---

### 10. Deep Linking & Navigation

#### Deep Link Patterns
- `llamalabels://native-pair?sub=...&hash=...&srv=...&pt=...&reg=...` (pairing)
- From push notification: Open inbox with `messageId` parameter
- From MFA notification: Open `MfaApprovalView`

#### NavigationRouter
```swift
protocol DeepLinkHandler {
  func handle(_ url: URL) async -> NavigationAction?
}

enum NavigationAction {
  case openPairingFlow(PairingParams)
  case openEmail(messageId: String)
  case openMfaApproval(challengeId: String)
}
```

#### iOS Navigation Stack
```
TabView {
  NavigationStack {
    InboxView
      .navigationDestination(for: Email.self) { email in
        EmailDetailView(email)
      }
  }
  .tabItem { Label("Inbox", ...) }
  
  NavigationStack {
    ContactsListView
      .navigationDestination(for: Contact.self) { contact in
        ContactDetailView(contact)
      }
  }
  .tabItem { Label("Contacts", ...) }
  
  SettingsView
    .tabItem { Label("Settings", ...) }
}
```

---

### 11. Testing Strategy

#### Unit Tests (90% of tests, no device required)
- **Deep Link Parser:** Validate URL parsing, required param presence
- **Pairing Validator:** Check `sub`, `hash`, `srv`, `pt` rules
- **Registration Endpoint Resolution:** `reg` override vs. derived URL
- **Payload Mappers:** FCM/APNs payload → `Email` / `MfaChallenge` models
- **Email Mappers:** IMAP response → `Email` model
- **Contact Sync Reconciliation:** Delta merge, UID matching
- **Keyword Tab Computation:** Derive tabs from IMAP keywords
- **Relay Response Mapping:** HTTP status → `MailOutcome` enum

#### Integration Tests (Device/Simulator required)
- **Keychain Storage:** Round-trip encryption/decryption (SecurePairingStore)
- **Database:** GRDB/Core Data DAO operations (AppDatabase)
- **APNs Mock:** Simulate incoming push payloads (via launch arguments)
- **Notification Categories:** Verify MFA action buttons appear

#### Manual E2E Testing (Before Release)
1. Scan pairing QR → Device marked paired, FCM token registered
2. Inbox tab filtering by keyword
3. Manual IMAP: Connect with real account → Fetch emails → Compose & send
4. Relay mode: Verify relay endpoints are called with correct auth
5. Push notification: Receive test email → System notification appears
6. MFA push: Receive approval challenge → Approve/Deny buttons work
7. Contact sync: Add/edit contact → Sync to server
8. Theme switching: All colors apply correctly
9. Pull mode: Toggle to pull → Polling works in background
10. Deep linking: Tap pairing QR → App opens and pairs

---

## Development Workflow

### Build & Run
```bash
# Using Xcode
xcode-select --install
open LlamaMail.xcodeproj

# Or Swift Package Manager
swift build

# Run on simulator
xcode-build -scheme LlamaMail -destination 'platform=iOS Simulator,name=iPhone 15' build run

# Run on device
xcode-build -scheme LlamaMail -destination 'generic/platform=iOS' build run
```

### Testing
```bash
# Unit tests
swift test

# UI tests (requires simulator)
xcode-build -scheme LlamaMail -enableCodeCoverage YES test
```

### Deployment
- **iOS:** Archive → export for Ad Hoc / App Store
- **MacOS:** Archive → export for Developer ID signing
- **Firebase/APNs:** Upload p8/p12 certificates to Firebase console

---

## Ponytail Principles (Lazy Senior Dev)

Apply the same constraints as Android:

1. **Reuse what already exists** — inherit as much from the data layer as possible
2. **Prefer stdlib and native platform APIs** — avoid dependency sprawl
3. **Add dependencies only when they reduce meaningful code** — GRDB is justified (Room equiv); SwiftyJSON is not
4. **Fix shared root causes, not one caller** — centralize network error handling
5. **If a shortcut has a limit, mark it with `ponytail:` and name the upgrade path** — e.g., "ponytail: no attachment support in v1, add file picker + multipart/form-data in v2"

All non-trivial logic must include one runnable check (unit test or self-contained validation).

---

## DOX Framework (Documentation)

### Binding Contracts
- This file (`iOS_llama_mail.md`) is the root AGENTS.md equiv
- Payload contracts: exact keys (`messageId`, `senderName`, `emailSubject`, `Keywords`) must match Android
- Deep-link scheme: exactly `llamalabels://native-pair`
- Theme palette fields: identical to web (`theme.ts`) and Android (`AppTheme.kt`)
- Keychain + UserDefaults split: pairing in Keychain, non-sensitive in UserDefaults

### Update After Editing
- On meaningful changes (new endpoint, new permission, theme change, new tab type):
  - Update this file
  - Sync with Android AGENTS.md and web theme.ts
  - Update test checklist if behavior changes

---

## Appendix: Checklist for Agent

### Phase 1: Setup & Core Layer
- [ ] Xcode project + Swift Package Manager structure
- [ ] SingletonGraph dependency injection container
- [ ] AppDelegate + SceneDelegate (lifecycle, notification handling)
- [ ] GRDB schema + migrations (emails, contacts, notifications, keywords)
- [ ] Keychain wrapper (`SecurePairingStore`)
- [ ] UserDefaults wrapper (`MailSettingsStore`, `KeywordSettingsStore`)

### Phase 2: Networking & Auth
- [ ] HTTPClient (URLSession wrapper, OkHttp equiv)
- [ ] NativeRegistrationClient (register, token refresh)
- [ ] PushNotificationClient (pull endpoint polling)
- [ ] MfaResponseClient (approve/deny)
- [ ] ContactSyncClient
- [ ] Deep-link parser + validator
- [ ] Unit tests for all clients

### Phase 3: Mail (IMAP & Relay)
- [ ] MailGateway (protocol + IMAP impl, TLS, FLAGS, KEYWORD)
- [ ] ImapMailSource
- [ ] RelayMailSource
- [ ] MailRepository
- [ ] KeywordRepository + tab computation
- [ ] EmailDAO + queries
- [ ] Compose flow (SendEmailUseCase)
- [ ] Unit tests (payload mapping, keyword tabs)

### Phase 4: Contacts
- [ ] ContactSyncRepository
- [ ] ContactSyncReconciliation
- [ ] ContactCursorStore
- [ ] ContactDAO + upsert/delete
- [ ] ContactsListView + ContactDetailView
- [ ] Unit tests (delta merge, UID matching)

### Phase 5: Push & Notifications
- [ ] APNs registration flow
- [ ] PushNotificationDispatcher (normal + MFA)
- [ ] Notification category setup (mail + MFA actions)
- [ ] MFA approval flow (buttons + fallback UI)
- [ ] PushRepository + history
- [ ] Pull mode polling (foreground + background)
- [ ] Integration test (mock payloads)

### Phase 6: UI (iOS)
- [ ] MainTabView + tab navigation
- [ ] InboxView (email list, keyword tabs, refresh)
- [ ] EmailDetailView (read, reply/forward stub)
- [ ] ComposeView
- [ ] SettingsView (connection mode, IMAP config, theme)
- [ ] ThemesView
- [ ] KeywordSettingsView
- [ ] ContactsListView + ContactDetailView
- [ ] PushPairingView (QR scanner, status)
- [ ] MfaApprovalView
- [ ] Theme system (AppTheme, color bindings)
- [ ] Style guide adherence (shapes, typography)

### Phase 7: UI (MacOS)
- [ ] Adapt iOS views to MacOS (sidebar, multi-pane layout)
- [ ] Menu bar integration
- [ ] Preferences window
- [ ] Notification center integration (MacOS native)
- [ ] Theme color sync

### Phase 8: Testing & Polish
- [ ] Unit test suite (90% coverage on logic)
- [ ] Integration tests (Keychain, database, notifications)
- [ ] E2E manual test checklist (see §Testing Strategy)
- [ ] Localization keys (copy from Android strings.xml)
- [ ] App icon + launch screen
- [ ] APNs certificate upload to Firebase
- [ ] Release build + archive

### Phase 9: Launch & Maintenance
- [ ] Testflight submission (iOS)
- [ ] Mac App Store submission (MacOS)
- [ ] Monitoring: crash logs, network errors
- [ ] Update cycle sync with Android + web releases

---

## FAQ & Troubleshooting

**Q: Why SwiftUI instead of UIKit?**  
A: SwiftUI enables code reuse between iOS and MacOS via `#if os(iOS)` / `#if os(macOS)` conditionals. UIKit would require two separate view hierarchies. SwiftUI's preview system also speeds iteration.

**Q: How do I handle the IMAP/SMTP library?**  
A: No battle-tested pure-Swift IMAP library exists (SwiftNIO-based stacks are overkill). Fork or wrap an Objective-C library (e.g. Pantomime, libetpan) with Swift bridging, or implement a minimal RFC 3501 client. Keep it separate in a `MailTransport` module so it can be swapped.

**Q: What about background sync on MacOS?**  
A: MacOS has no equivalent to iOS background app refresh. Use `NSWorkspaceNotification.didWakeNotification` to resume polling, and let the user run it full-time for near-real-time sync.

**Q: Can I use Core Data instead of GRDB?**  
A: Yes. Core Data has more boilerplate but is more familiar to UIKit developers. This spec uses GRDB's terminology (DAO, repositories) but both are valid. Pick one upfront and stick with it.

**Q: How do I share code between iOS and MacOS?**  
A: Use **shared frameworks** (`Sources/Shared/` in SPM), and keep platform-specific code in `Sources/iOS/` and `Sources/MacOS/`. Logical layer (network, database, domain) is 100% shared; presentation is ~70% shared (same ViewModels, ~30% view adaptation).

**Q: Push notifications in simulator?**  
A: APNs doesn't work in simulator. Use launch arguments to inject mock payloads:
```bash
xcrun simctl push booted com.urlxl.mail '{...payload...}'
```
Or add a debug menu for manual payload injection.

---

**Document Version:** 1.0  
**Last Updated:** 2026-07-10  
**Maintainer:** Yoshiofthewire / yoshi@urlxl.com  
**Status:** Ready for agent implementation
