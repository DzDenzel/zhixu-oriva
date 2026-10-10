package com.planlock.feature.plan

import com.planlock.app.api.AuthSession
import com.planlock.app.api.AuthState
import org.junit.Assert.*
import org.junit.Test

class ScheduleWorkspaceOwnerTest {
    @Test fun offlineNewSchedulesStayWithRetainedAccountAcrossExitAndOtherLogin() {
        assertEquals(42L, scheduleWorkspaceOwner(null, AuthState(signedOut = true, dataOwner = 42)))
        assertEquals(42L, scheduleWorkspaceOwner(null, AuthState(AuthSession("b", 99, 9), dataOwner = 42)))
        assertEquals(7L, scheduleWorkspaceOwner(null, AuthState(AuthSession("guest", 7, 8))))
        assertEquals(5L, scheduleWorkspaceOwner(5, AuthState(signedOut = true, dataOwner = 42)))
    }
}
