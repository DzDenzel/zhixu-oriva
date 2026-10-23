package com.planlock.app.record

import com.planlock.app.api.AuthSession
import com.planlock.app.api.AuthState
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountVisibleScopeTest {
    @Test fun signedOutNeverUsesRetainedAccountOwner() {
        assertEquals("local\nlocal", visibleHistoryScope(AuthState(signedOut = true, dataOwner = 7, dataOwnerInstance = "a"), "a"))
    }
    @Test fun newAccountNeverUsesPreviousAccountOwner() {
        val state = AuthState(AuthSession("token", 2, 3, signedIn = true), dataOwner = 1, dataOwnerInstance = "a", instanceId = "a")
        assertEquals("a\n2", visibleHistoryScope(state, "a"))
    }
}
