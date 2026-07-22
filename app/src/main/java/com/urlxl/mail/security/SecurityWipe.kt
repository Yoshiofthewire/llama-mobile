package com.urlxl.mail.security

import android.content.Context
import com.urlxl.mail.data.DataRuntime
import com.urlxl.mail.push.SecurePairingStore

/**
 * Full destructive reset: runs when [LockoutPolicy.WIPE_THRESHOLD] wrong PIN attempts
 * accumulate, and when disabling "Require Unlock to Open" needs to recover a credential-gate
 * (Task 18) wrapped `deviceSecret` (see [SecuritySettingsActivity.promptDisableLock]; a plain
 * lock-disable with no credential gate active uses the lighter [AppLockState.reset] path
 * instead — see that method's doc comment for why). Closes and deletes the Room database,
 * clears pairing credentials (forcing re-pairing), and clears the app-lock PIN/flags — the app
 * ends up in exactly its first-run state.
 */
object SecurityWipe {
    /**
     * Performs the destructive reset described above.
     *
     * IMPORTANT — caller contract: this closes the [DataRuntime]-owned Room database, but the
     * process-lifetime [com.urlxl.mail.SingletonGraph] backing [DataRuntime] still holds that
     * same (now-closed) `DataGraph`/`AppDatabase` instance afterward — `DataRuntime` has no way
     * to invalidate or recreate it from inside this call. Callers MUST immediately restart the
     * process afterward via `AppRestart.relaunch(context)` (see Task 8) and MUST NOT continue
     * using `DataRuntime`/the database in the same process after calling this function — any
     * subsequent `DataRuntime.graph(context)` call before a restart returns the stale, closed
     * `DataGraph` and will throw when its database is used.
     */
    suspend fun wipeAndResetApp(context: Context) {
        closeAndDeleteDatabase(context)
        SecurePairingStore(context).clearPairing()
        AppLockStore(context).reset()
    }

    /**
     * Closes the current Room database instance and deletes `kypost_mail.db` (plus its
     * `-wal`/`-shm` journal files) from disk — shared by [wipeAndResetApp] above and by Hostile
     * Location Protection's toggle handler (see [SecuritySettingsActivity]'s
     * `hostileLocationSwitch` listener and the 2026-07-22 security-hardening spec's "Toggling
     * on"/"Toggling off" sections). Enabling the toggle must delete any on-disk cache written
     * before protection was turned on ("nothing from before the toggle survives"); disabling it
     * calls this too as a harmless safety net even though the in-memory database it's replacing
     * never actually wrote to this file. Same caller contract as [wipeAndResetApp]: the caller
     * MUST restart the process via `AppRestart.relaunch(context)` immediately afterward and
     * MUST NOT touch `DataRuntime`/the database again in this process first.
     */
    suspend fun closeAndDeleteDatabase(context: Context) {
        DataRuntime.graph(context).database.close()
        context.deleteDatabase("kypost_mail.db")
    }
}
