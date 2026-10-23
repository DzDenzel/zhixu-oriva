package com.planlock.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class BackupPasswordVerifierTest {
    private val hasher = Pbkdf2PasswordHasher(iterations = 1_000)
    private val verifier = BackupPasswordVerifier(hasher)

    @Test
    fun `PBKDF2 uses random salt and never stores plaintext`() {
        val first = hasher.create("2468")
        val second = hasher.create("2468")

        assertNotEquals(first.saltEncoded, second.saltEncoded)
        assertNotEquals(first.hashEncoded, second.hashEncoded)
        assertTrue(first.hashEncoded.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals("2468", first.hashEncoded)
        assertTrue(hasher.matches("2468", first))
        assertFalse(hasher.matches("0000", first))
    }

    @Test
    fun `valid PBKDF2 hash can coincidentally contain password digits`() {
        // Independently generated PBKDF2-HMAC-SHA256 vector: 1,000 rounds, 256 bits.
        val fixedRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.fill(0)
                bytes[14] = 0x03
                bytes[15] = 0xf9.toByte()
            }
        }
        val fixedHasher = Pbkdf2PasswordHasher(iterations = 1_000, random = fixedRandom)
        val credential = fixedHasher.create("2468")
        assertEquals("000000000000000000000000000003f9", credential.saltEncoded)
        assertEquals("52adca10b02b79fcb983a111329093bfe596dd2d62468fc0ad3e499e7d583507", credential.hashEncoded)
        assertTrue(fixedHasher.matches("2468", credential))
        assertFalse(fixedHasher.matches("0000", credential))
    }

    @Test
    fun `correct password succeeds and clears failures`() {
        val credential = hasher.create("2468")
        val attempt = verifier.verify("2468", BackupPasswordState(credential, failedAttempts = 2), nowMillis = 5_000)

        assertEquals(BackupPasswordResult.Verified, attempt.result)
        assertEquals(0, attempt.state.failedAttempts)
        assertEquals(0, attempt.state.cooldownUntilMillis)
    }

    @Test
    fun `three failures start thirty second cooldown and correct password is rejected during it`() {
        val credential = hasher.create("2468")
        var state = BackupPasswordState(credential)

        repeat(2) {
            val attempt = verifier.verify("0000", state, nowMillis = 10_000)
            assertTrue(attempt.result is BackupPasswordResult.Invalid)
            state = attempt.state
        }
        val third = verifier.verify("0000", state, nowMillis = 10_000)
        assertEquals(BackupPasswordResult.Cooldown(30_000), third.result)

        val correctDuringCooldown = verifier.verify("2468", third.state, nowMillis = 20_000)
        assertEquals(BackupPasswordResult.Cooldown(20_000), correctDuringCooldown.result)

        val afterCooldown = verifier.verify("2468", correctDuringCooldown.state, nowMillis = 40_000)
        assertEquals(BackupPasswordResult.Verified, afterCooldown.result)
    }

    @Test
    fun `missing password reports setup required`() {
        val attempt = verifier.verify("2468", BackupPasswordState(), nowMillis = 0)
        assertEquals(BackupPasswordResult.NotSet, attempt.result)
    }
}
