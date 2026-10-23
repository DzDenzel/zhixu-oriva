package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import java.io.IOException

class ConnectionPersistenceTest {
    @After fun reset() = runBlocking { val store = MemoryStore(); ConnectionStore.restore(store); TokenStore.restore(store); Unit }
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }

    @Test fun unconnectedStoreCannotAcceptCredentials() = runBlocking {
        val store = MemoryStore()
        ConnectionStore.restore(store); TokenStore.restore(store)
        TokenStore.changeConnection(store, null)
        try { TokenStore.save(store, AuthSession("must-not-save", 42, 8, signedIn = true)); fail("Unconnected login accepted") }
        catch (_: IllegalStateException) {
            assertNull(TokenStore.token)
            assertFalse(TokenStore.canSync)
            assertTrue(store.data.value.asMap().keys.none { it.name == "token" })
        }
    }

    @Test fun sameInstanceGuestCanBecomeAccountButLateLoginCannotCommitAfterSwitch() = runBlocking {
        val store = MemoryStore()
        ConnectionStore.restore(store); TokenStore.restore(store)
        importTestConnection(store)
        TokenStore.save(store, AuthSession("guest", 7, 8))
        TokenStore.save(store, AuthSession("account", 42, 8, guestUserId = 7, signedIn = true))
        assertTrue("Trusted same-instance guest merge must remain possible", TokenStore.canSync)
        assertEquals(42L, TokenStore.localDataUserId)
        val started = ConnectionStore.state.value
        TokenStore.changeConnection(store, started.config!!.copy(apiBaseUrl = "https://other.invalid/api/v1/"))
        TokenStore.changeConnection(store, started.config)
        try { TokenStore.save(store, AuthSession("late-login", 42, 8, signedIn = true), started); fail("Late login crossed a connection revision") }
        catch (_: IllegalStateException) { assertNull(TokenStore.token) }
    }

    @Test fun explicitLogoutAndAccountSwitchRequireLoginEvenAfterReturningToSameBackend() = runBlocking {
        val store = MemoryStore(); TokenStore.restore(store)
        importTestConnection(store)
        TokenStore.save(store, AuthSession("account", 42, 8, signedIn = true))
        val original = ConnectionStore.state.value.config
        TokenStore.signOut(store)
        TokenStore.changeConnection(store, null)
        TokenStore.changeConnection(store, original)
        assertFalse("Connection changes must not undo explicit logout", TokenStore.canRegisterDevice)
        TokenStore.save(store, AuthSession("account-again", 42, 8, signedIn = true))
        TokenStore.changeConnection(store, null)
        TokenStore.changeConnection(store, original)
        assertFalse("Previously signed-in account must explicitly authenticate after switching", TokenStore.canRegisterDevice)
    }

    @Test fun switchingClearsCredentialsAndNewInstanceUsesDistinctScopeAcrossRestore() = runBlocking {
        val store = MemoryStore()
        ConnectionStore.restore(store)
        TokenStore.restore(store)
        importTestConnection(store)
        TokenStore.save(store, AuthSession("old-token", 42, 8, signedIn = true))
        val original = ConnectionStore.state.value.config!!
        val other = original.copy(instanceLabel = "其他实例", apiBaseUrl = "https://other.invalid/api/v1/")
        TokenStore.changeConnection(store, other)
        assertNull(TokenStore.token)
        assertNull(TokenStore.localDataUserId)
        assertFalse(TokenStore.state.value.ownershipMismatch)
        assertFalse(TokenStore.canSync)
        assertFalse(TokenStore.canRegisterDevice)
        ConnectionStore.restore(store)
        TokenStore.restore(store)
        assertEquals(other, ConnectionStore.state.value.config)
        assertFalse(TokenStore.state.value.ownershipMismatch)
        TokenStore.save(store, AuthSession("same-id-new-instance", 42, 8, signedIn = true))
        assertEquals(other.instanceId + "\n42", TokenStore.state.value.dataScope)
        assertNotEquals(original.instanceId + "\n42", TokenStore.state.value.dataScope)
        TokenStore.changeConnection(store, original)
        TokenStore.save(store, AuthSession("restored", 42, 8, signedIn = true))
        assertTrue(TokenStore.canSync)
    }

    @Test fun localOnlyAndFailedPersistenceNeverPublishAFalseSwitch() = runBlocking {
        val store = MemoryStore()
        ConnectionStore.restore(store)
        TokenStore.restore(store)
        val original = ConnectionStore.state.value
        val failing = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("disk unavailable")
        }
        try { TokenStore.changeConnection(failing, null); fail("Failed persistence published") }
        catch (_: IOException) { assertEquals(original, ConnectionStore.state.value) }
        TokenStore.changeConnection(store, null)
        ConnectionStore.restore(store)
        assertNull(ConnectionStore.state.value.config)
        assertFalse(TokenStore.canRegisterDevice)
        assertFalse(TokenStore.canSync)
    }
}
