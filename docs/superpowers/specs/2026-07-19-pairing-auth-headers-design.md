# Move pairing auth (`sub`/`hash`) from query params to headers

Date: 2026-07-19
Status: Proposed — not scheduled. Reference doc for a coordinated server +
all-clients change; do not implement piecemeal (see Rollout below).

## Background

Every native-pairing-authenticated endpoint (mail relay, contact sync, group
sync, native push pull) is called with the subscriber's proof of pairing —
`subscriberId` + `subscriberHash` — attached as **URL query parameters**,
never headers or cookies. This was a deliberate, documented choice on the
Android client (see `RelayMailSource.kt`'s class doc: "Auth is `sub`/`hash`
query params only... never headers/cookies") and the server was built to
match.

This is not exploitable over the wire — TLS still protects the full request
including the query string. The exposure is server-side: full request URLs,
query string included, are what typically land in web server access logs,
any reverse proxy / load balancer / CDN logs sitting in front of the API,
and general request-logging middleware. A long-lived credential sitting in
plaintext log files is a softer target than one that only ever appears in a
header most log configurations don't capture by default. It's a medium-risk
hardening item, not an active vulnerability — hence "fix later, coordinated"
rather than "fix now."

Two endpoints already do this correctly and can serve as the reference
pattern: MFA response (`MfaResponseClient.kt`) and native registration
(`NativeRegistrationClient` / `NativeRegistration.kt`) both send their
subscriber credentials in the POST body, not the URL. The endpoints in scope
here are GET-heavy (inbox fetch, folder list, pull poll), so "use the POST
body" isn't available as a fix — headers are the right target.

## Scope

### Server (`kypost-server`, package `backend/internal/api`)

Three independent call sites read `sub`/`hash` from `r.URL.Query()` today —
they are not centralized behind one helper, so all three need the change:

1. **`server_userscope.go:247-248`**, inside `resolveMailAuthContext` — the
   shared auth resolver for mail (`mailFor`) and, per its doc comment,
   groups. This is the highest-leverage single change.
2. **`contacts_handlers.go:360-361`**, inside `handleContactsSync` — has its
   own inline copy of the same sub/hash-validation logic, not routed through
   `resolveMailAuthContext`.
3. **`server.go:1855-1856`**, inside `handleNotificationNativePull` — same
   pattern, also inline, for the App Pull path.

All three follow the identical shape (trim `sub`, lowercase+trim `hash`,
constant-time-compare against `pairingSubscriberHash(subscriberID)`) — worth
factoring into one shared helper as part of this change, rather than editing
three copies in lockstep forever.

**Out of scope:** the PGP QR key-exchange endpoints
(`pgp_qr_handlers.go`) use a single-use `t` token in the query string, not
`sub`/`hash` — different threat model (short-lived, single-purpose, not a
standing credential), not part of this finding.

### Clients

**kypost-android** (this repo) — five HTTP clients attach `sub`/`hash` as
query params:

- `RelayMailSource.kt` (`authed()`, ~line 234; also inlined in
  `fetchInbox`/`listFolders`)
- `ContactSyncClient.kt`
- `GroupsSyncClient.kt`
- `PullNotificationClient.kt`
- `PgpQrClient.kt` (`mintToken` only — `fetchKey` uses the unrelated `t`
  token and is out of scope, see above)

No other native client exists yet as of this writing. If one is built before
this is scheduled, it should follow whatever scheme this doc settles on from
day one rather than repeating the query-param pattern.

## Proposed fix

Add two request headers carrying the same values, and have the server
prefer them over the query params:

```
X-Kypost-Subscriber-Id: <subscriberId>
X-Kypost-Subscriber-Hash: <subscriberHash>
```

(Names are a starting proposal, not final — pick whatever fits this
project's existing header-naming convention if one exists by the time this
is picked up.) Two plain headers rather than a single `Authorization:
Bearer` value, to keep the server-side diff minimal — it's the same two
string values, just read from `r.Header.Get(...)` instead of
`r.URL.Query().Get(...)`, and the client-side diff is equally minimal
(`.addHeader(...)` instead of `.addQueryParameter(...)` on the same
`Request.Builder`).

## Rollout (why this can't be a single-PR change)

Server and client don't deploy atomically — the server updates immediately,
but the Android client rolls out gradually via app store update adoption,
and old installs can stay on an old version indefinitely if a user disables
auto-update. A hard cutover breaks every client that hasn't updated yet.

Sequencing:

1. **Server**: accept *either* the new headers or the legacy query params
   (headers take precedence if both present). No client-visible change yet.
2. **Client(s)**: switch to sending the headers instead of the query params.
   Ship, wait for adoption to climb (telemetry or a minimum-supported-version
   policy, whatever this project uses elsewhere).
3. **Server**: once query-param usage in access logs has dropped to zero (or
   an acceptable floor — e.g. only ancient unsupported client versions still
   sending them), remove query-param support and log a warning/metric on any
   request still using it, to catch stragglers before hard-removing that
   path entirely in a later pass.

Step 1 alone is safe to ship independently and de-risks everything after it
— worth doing first even if steps 2-3 slip.

## Out of scope

- No change to the underlying trust model — `subscriberHash` is still the
  same server-computed HMAC, compared the same way
  (`subtle.ConstantTimeCompare`). This is purely about *where* the values
  travel in the request, not what they prove.
- No OAuth/session-token redesign — headers carrying the same long-lived
  hash is still a bearer-credential model, same as today.
- No change to the PGP QR `t`-token flow.
- No change to `MfaResponseClient`/`NativeRegistrationClient` — already
  correct (POST body).

## Verification (for whoever implements this)

- Server: unit tests per handler asserting a request with headers-only (no
  query params) succeeds, and — during the transition window — a
  query-params-only request still succeeds too.
- Client: existing per-client unit tests (e.g. `RelayMailSource` tests using
  a fake `Call.Factory`) should assert the built `Request` carries the new
  headers; grep the test suite for `addQueryParameter` assertions on `sub`/
  `hash` that will need updating alongside the production code.
- Manual: confirm real server access logs (or equivalent request-logging
  middleware output) no longer contain `sub=`/`hash=` after full rollout.
