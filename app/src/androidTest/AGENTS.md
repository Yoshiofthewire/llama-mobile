# Purpose

Owns Android instrumentation tests executed on emulator/device.

# Ownership

- Tests under `app/src/androidTest/java/`

# Local Contracts

- Use instrumentation tests for integration checks that require Android runtime.
- Keep assertions focused on user-visible behavior and Android context wiring.

# Work Guidance

- Add instrumentation coverage only when JVM tests cannot validate the behavior.
- Keep emulator/device setup assumptions minimal and explicit.

# Verification

- Run connected Android tests when instrumentation changes are made.

# Child DOX Index

- No child AGENTS.md files.

