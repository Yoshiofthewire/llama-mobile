package com.urlxl.mail.security

import android.content.Context
import com.urlxl.mail.data.DataRuntime
import com.urlxl.mail.push.SecurePairingStore

/**
 * Full destructive reset: runs when [LockoutPolicy.WIPE_THRESHOLD] wrong PIN attempts
 * accumulate, and when the user explicitly turns "Require Unlock to Open" off (which also
 * clears the PIN, since a stale PIN with lock disabled would be confusing state). Closes and
 * deletes the Room database, clears pairing credentials (forcing re-pairing), and clears the
 * app-lock PIN/flags — the app ends up in exactly its first-run state.
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
        DataRuntime.graph(context).database.close()
        context.deleteDatabase("kypost_mail.db")
        SecurePairingStore(context).clearPairing()
        AppLockStore(context).reset()
    }
}
