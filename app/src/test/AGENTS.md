# Purpose

Owns JVM unit tests for app logic that can run without device/emulator.

# Ownership

- Tests under `app/src/test/java/`

# Local Contracts

- Cover non-trivial logic changes with one focused regression test.
- Keep tests deterministic and fast.

# Work Guidance

- Prefer pure function tests for keyword tabbing/filtering behavior.
- Avoid network or Android framework dependencies in JVM unit tests.

# Verification

- Run `testDebugUnitTest` after unit test updates.

# Child DOX Index

- No child AGENTS.md files.

