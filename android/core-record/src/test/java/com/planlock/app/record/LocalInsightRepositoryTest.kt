package com.planlock.app.record

import com.planlock.app.api.AuthSession
import com.planlock.app.api.AuthState
import com.planlock.app.api.InsightFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalInsightRepositoryTest {
    @Test fun emptyImplementationNeverCreatesContentFromFeedback() = runBlocking {
        val repository = LocalInsightRepository(AuthState().dataScope)
        assertTrue(repository.observe().first().isEmpty())
        repository.setFeedback("future-conversation-item", InsightFeedback.LIKE_ME)
        repository.setFeedback("future-conversation-item", InsightFeedback.UNLIKE_ME)
        assertTrue(repository.observe().first().isEmpty())
        assertTrue(LocalInsightRepository(repository.dataScope).observe().first().isEmpty())
    }

    @Test fun repositoriesBindToFullVisibleIdentityIncludingInstanceAndLocalWorkspace() = runBlocking {
        val a = AuthState(session = AuthSession(userId = 42), instanceId = "https://a.invalid/api/v1/")
        val b = a.copy(instanceId = "https://b.invalid/api/v1/")
        val otherAccount = a.copy(session = AuthSession(userId = 43))
        val local = a.copy(signedOut = true)
        val repositories = listOf(a, b, otherAccount, local).map { LocalInsightRepository(it.dataScope) }
        assertEquals(4, repositories.map { it.dataScope }.toSet().size)
        assertEquals(AuthState().dataScope, repositories.last().dataScope)
        repositories.forEach { assertTrue(it.observe().first().isEmpty()) }
        assertEquals(a.dataScope, repositories.first().dataScope)
    }
}
