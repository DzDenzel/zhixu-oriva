package com.planlock.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPasswordVerifierTest {
    private val hasher = Pbkdf2PasswordHasher(iterations = 1_000)
    private val verifier = BackupPasswordVerifier(hasher)

    @Test
    fun `PBKDF2 uses random salt and never stores plaintext`() {
        val first = hasher.create("2468")
        val second = hasher.create("2468")

        assertNotEquals(first.saltEncoded, second.saltEncoded)
        assertNotEquals(first.hashEncoded, second.hashEncoded)
        assertFalse(first.hashEncoded.contains("2468"))
        assertTrue(hasher.matches("2468", first))
        assertFalse(hasher.matches("0000", first))
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
