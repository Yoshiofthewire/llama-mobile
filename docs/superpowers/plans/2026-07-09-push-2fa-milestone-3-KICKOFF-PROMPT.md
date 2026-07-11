# Kickoff prompt: Push 2FA Milestone 3 (Android Approve/Deny UI)

Paste everything below this line into a fresh Claude Code session running in `/home/yoshi/git/llama-mobile`.

---

Implement the plan at `docs/superpowers/plans/2026-07-09-push-2fa-milestone-3-mobile.md` in this repo.

Use the **superpowers:subagent-driven-development** skill to execute it: a fresh subagent per task, with a review pass between tasks. Use haiku for mechanical/leaf tasks where the plan already specifies complete code (e.g. Task 1's parser, Task 2's client, Task 3's wiring), and sonnet for tasks with cross-file/ordering subtlety or Android framework integration risk (Task 4 through Task 7, all of which touch the notification/receiver/activity/manifest wiring called out in the plan's "Self-Review Notes" section as circularly dependent). Run a final whole-branch review with opus before finishing.

Context you should know before starting:

- **This is purely additive on the mobile side.** The backend contract (in the sibling repo `/home/yoshi/git/llama labels`, a separate Go + React app) already shipped and is fixed: FCM data payload `{type: "mfa_challenge", challengeId: "..."}`, and `POST {serverUrl}/api/mfa/push/respond` with body `{challengeId, subscriberId, subscriberHash, deviceId, approve}` (200/401/403/409 responses as documented in the plan's Global Constraints). Do not modify anything in that other repo — if something in the plan seems to require a backend change, stop and flag it rather than editing the backend.
- This repo (`llama-mobile`) uses a DOX documentation framework — read `AGENTS.md` at the repo root and `app/src/main/AGENTS.md` before editing, per that framework's own rules. The plan's last task updates `app/src/main/AGENTS.md`'s "Local Contracts" section; don't skip it.
- Use this repo's worktree convention (`.claude/worktrees/<name>/`) if you isolate this work in a worktree — matches the sibling `llama labels` repo's convention, adopted here too.
- No new Gradle dependencies — everything in the plan is buildable with what's already declared in `app/build.gradle.kts` (OkHttp + kotlinx.serialization, no Retrofit; JUnit4 only for JVM tests, no Mockito/Robolectric).
- Verification command: `./gradlew testDebugUnitTest` for unit tests, `./gradlew assembleDebug` for a full compile check. The plan's Task 8 is a manual on-device/emulator verification checklist against a real paired device and a running backend with push 2FA enabled — it can't be automated, don't try to fake it with a unit test.
- When finished, offer the same finishing-a-development-branch options this project's other recent branches used (merge to main locally, PR, etc.) rather than assuming one.

If anything in the plan looks stale against the current code (file moved, function renamed, line numbers drifted), verify against the real file before proceeding — don't execute a step that no longer matches reality.
