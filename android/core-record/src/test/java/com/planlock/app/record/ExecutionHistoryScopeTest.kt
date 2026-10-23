package com.planlock.app.record

import com.planlock.app.api.AuthSession
import com.planlock.app.api.AuthState
import org.junit.Assert.*
import org.junit.Test

class ExecutionHistoryScopeTest {
    @Test fun newLoginWritesOnlyToCurrentScopeAndRetainsOtherPartitions() {
        val state=AuthState(AuthSession(userId=22),dataOwner=11,instanceId="new",dataOwnerInstance="old")
        assertFalse(state.ownershipMismatch)
        assertEquals("new\n22",ownedHistoryScope(state,"new"))
        assertNotEquals(historyScopeKey("old",11),ownedHistoryScope(state,"new"))
    }
    @Test fun neverConnectedHistoryUsesUnownedLocalScopeUntilRegistration() {
        assertEquals("local\nlocal",ownedHistoryScope(AuthState(),null))
        assertEquals("local\nlocal",ownedHistoryScope(AuthState(instanceId="backend"),"backend"))
        assertEquals("local\nlocal",visibleHistoryScope(AuthState(instanceId="backend"),"backend"))
        assertEquals("backend\n1",ownedHistoryScope(AuthState(AuthSession(userId=1),dataOwner=1,dataOwnerInstance="backend"),"backend"))
    }
}
