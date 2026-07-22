package com.urlxl.mail.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun matches_returnsTrue_forCorrectPin() {
        val salt = PinHasher.randomSalt()
        val hash = PinHasher.hash("123456", salt)
        assertTrue(PinHasher.matches("123456", salt, hash.hash))
    }

    @Test
    fun matches_returnsFalse_forWrongPin() {
        val salt = PinHasher.randomSalt()
        val hash = PinHasher.hash("123456", salt)
        assertFalse(PinHasher.matches("654321", salt, hash.hash))
    }

    @Test
    fun hash_isDeterministic_forSameSalt() {
        val salt = PinHasher.randomSalt()
        val first = PinHasher.hash("123456", salt)
        val second = PinHasher.hash("123456", salt)
        assertTrue(first.hash.contentEquals(second.hash))
    }

    @Test
    fun randomSalt_producesDifferentValues() {
        val a = PinHasher.randomSalt()
        val b = PinHasher.randomSalt()
        assertFalse(a.contentEquals(b))
    }
}
