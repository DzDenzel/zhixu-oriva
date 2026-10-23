package com.planlock.app.sync

import com.planlock.app.api.ConnectionConfig
import com.planlock.app.api.AuthState
import com.planlock.app.api.ConnectionState
import com.planlock.app.api.dto.ApiResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceRegistrationGateTest {
    @Test fun explicitSignOutWaitsForLoginOnEitherInstanceWithoutRegistering() = runBlocking {
        var calls = 0
        val coordinator = SyncCoordinator(this) {
            requireAutomaticRegistration(false, AuthState(signedOut = true))
            calls++
        }
        coordinator.foregrounded().join()
        assertEquals(0, calls)
        assertNull(coordinator.state.value.error)
        assertTrue(coordinator.state.value.loginRequired)
        val protected = SyncCoordinator(this) {
            requireAutomaticRegistration(false, AuthState(signedOut = true, dataOwnerInstance = "old", instanceId = "new"))
            calls++
        }
        protected.refresh().join()
        assertEquals(0, calls)
        assertNull(protected.state.value.error)
        assertTrue(protected.state.value.loginRequired)
    }
    @Test fun explicitOwnerLoginRefusalWaitsWithoutFailureOrRepeatedRegistration() = runBlocking {
        val gate = DeviceRegistrationGate()
        val connection = ConnectionState(ConnectionConfig.default, 1)
        var calls = 0
        val coordinator = SyncCoordinator(this) {
            gate.register<String>(connection) { calls++; ApiResponse(1001, "请使用账号密码登录自建实例") }
        }
        coordinator.refresh().join()
        assertNull(coordinator.state.value.error)
        assertTrue(coordinator.state.value.loginRequired)
        coordinator.foregrounded().join()
        assertEquals(1, calls)
        coordinator.clearError()
        assertFalse(coordinator.state.value.loginRequired)
        coordinator.refresh().join()
        assertEquals(1, calls)
        assertTrue(coordinator.state.value.loginRequired)
        assertEquals("session", gate.register(connection.copy(revision = 2)) {
            calls++; ApiResponse(0, "ok", "session")
        })
        assertEquals(2, calls)
    }

    @Test fun otherBusinessAndNetworkFailuresRemainRetryable() = runBlocking {
        for (response in listOf(ApiResponse<String>(1001, "未认证"), ApiResponse(1002, "请使用账号密码登录自建实例"))) {
            val gate = DeviceRegistrationGate()
            var calls = 0
            val coordinator = SyncCoordinator(this) {
                gate.register<String>(ConnectionState(ConnectionConfig.default)) { calls++; response }
            }
            repeat(2) { coordinator.refresh().join(); assertNotNull(coordinator.state.value.error) }
            assertEquals(2, calls)
            assertFalse(coordinator.state.value.loginRequired)
        }
    }
}
