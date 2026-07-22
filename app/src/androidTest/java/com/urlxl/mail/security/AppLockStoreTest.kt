package com.urlxl.mail.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun resetState() {
        AppLockStore(context).reset()
    }

    @Test
    fun setPin_thenVerifyPin_succeedsWithCorrectPin() {
        val store = AppLockStore(context)
        store.setPin("123456")
        assertTrue(store.verifyPin("123456"))
    }

    @Test
    fun verifyPin_fails_withWrongPin() {
        val store = AppLockStore(context)
        store.setPin("123456")
        assertFalse(store.verifyPin("000000"))
    }

    @Test
    fun lockEnabled_defaultsFalse_andPersistsWhenSet() {
        val store = AppLockStore(context)
        assertFalse(store.isLockEnabled())
        store.setLockEnabled(true)
        assertTrue(AppLockStore(context).isLockEnabled())
    }

    @Test
    fun failedAttempts_incrementAndReset() {
        val store = AppLockStore(context)
        assertEquals(1, store.incrementFailedAttempts())
        assertEquals(2, store.incrementFailedAttempts())
        store.resetFailedAttempts()
        assertEquals(1, store.incrementFailedAttempts())
    }

    @Test
    fun reset_clearsPinAndLockState() {
        val store = AppLockStore(context)
        store.setPin("123456")
        store.setLockEnabled(true)
        store.incrementFailedAttempts()

        store.reset()

        val fresh = AppLockStore(context)
        assertFalse(fresh.isLockEnabled())
        assertFalse(fresh.verifyPin("123456"))
        assertEquals(1, fresh.incrementFailedAttempts())
    }
}
