package com.planlock.app.api

import org.junit.Assert.*
import org.junit.Test

class LoginAttemptGuardTest {
    @Test fun completedFailureReleasesBusyStateForRetry() {
        val guard = LoginAttemptGuard()
        val failed = guard.begin()
        assertTrue(guard.submitting.value)
        guard.complete(failed)
        assertFalse(guard.submitting.value)
        val retry = guard.begin()
        assertTrue(guard.submitting.value)
        guard.complete(retry)
        assertFalse(guard.submitting.value)
    }
    @Test fun obsoleteCompletionCannotReleaseNewAttemptBusyState() {
        val guard = LoginAttemptGuard()
        val old = guard.begin()
        guard.invalidate()
        assertFalse(guard.submitting.value)
        val newer = guard.begin()
        guard.complete(old)
        assertTrue(guard.submitting.value)
        guard.complete(newer)
        assertFalse(guard.submitting.value)
    }
    @Test fun workspaceOwnerSurvivesLogoutAndOtherAccountAuthentication() {
        assertEquals(42L, AuthState(signedOut = true, dataOwner = 42).localDataUserId)
        assertEquals(42L, AuthState(AuthSession("account-b", 99, 9, signedIn = true), dataOwner = 42).localDataUserId)
        assertEquals(7L, AuthState(AuthSession("guest", 7, 8)).localDataUserId)
    }
    @Test fun cancelExitAndNewAttemptInvalidateLateLoginResponse() {
        val guard = LoginAttemptGuard()
        val old = guard.begin()
        assertTrue(guard.isCurrent(old))
        guard.invalidate() // dialog cancel
        guard.invalidate() // successful logout intent
        assertFalse(guard.isCurrent(old))
        val newer = guard.begin()
        assertFalse(guard.isCurrent(old))
        assertTrue(guard.isCurrent(newer))
    }
}
