# Purpose

Owns the Android app module build, manifest, source sets, resources, and test execution.

# Ownership

- Module: `app/`
- Build contract: `app/build.gradle.kts`
- Runtime package root: `app/src/main/java/com/urlxl/mail/`

# Local Contracts

- Keep app behavior aligned with project goal: IMAP inbox read, SMTP send, keyword-based tab filtering.
- Prefer one existing dependency for both IMAP and SMTP.
- Avoid hardcoded secrets in committed files.
- For user-visible behavior changes, update this file or a closer child AGENTS.md.

# Work Guidance

- Choose the smallest diff that fixes root cause.
- Reuse existing classes and Android components before adding new abstractions.
- Keep background behavior explicit; document Android lifecycle limits.
- Mark intentional ceilings with `ponytail:` comments and upgrade path.

# Verification

- Run unit tests for logic changes under `app/src/test/`.
- Run Android instrumentation tests when UI/manifest behavior changes under `app/src/androidTest/`.

# Child DOX Index

- `app/src/main/` — Production Android code and resources. See [app/src/main/AGENTS.md](src/main/AGENTS.md).
- `app/src/test/` — JVM unit tests for deterministic app logic. See [app/src/test/AGENTS.md](src/test/AGENTS.md).
- `app/src/androidTest/` — Instrumented device/emulator tests. See [app/src/androidTest/AGENTS.md](src/androidTest/AGENTS.md).

