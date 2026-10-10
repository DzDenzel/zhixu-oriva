package com.planlock.feature.settings

import com.planlock.app.api.AuthSession
import com.planlock.app.api.AuthState
import com.planlock.app.api.AuthValidity
import org.junit.Assert.*
import org.junit.Test

class AccountPresentationTest {
    @Test fun anonymousExpiredCredentialsKeepExistingGuestLoginInsteadOfAccountLogout() {
        listOf(AuthValidity.EXPIRED_LOCAL, AuthValidity.REJECTED).forEach {
            val ui = accountPresentation(AuthState(AuthSession("guest", 7, 8), it))
            assertEquals("本机游客模式", ui.title)
            assertEquals("登录", ui.loginLabel)
            assertFalse(ui.showSignOut)
        }
    }
    @Test fun accountAlwaysHasAReachableRecoveryAndNeverCallsSignedOutDataGuest() {
        val signed = AuthState(AuthSession("token", 42, 8, signedIn = true))
        assertEquals("已登录账号", accountPresentation(signed.copy(validity = AuthValidity.VALID)).title)
        assertTrue(accountPresentation(signed).showSignOut)
        listOf(AuthValidity.EXPIRED_LOCAL, AuthValidity.REJECTED).forEach {
            val ui = accountPresentation(signed.copy(validity = it))
            assertEquals("登录已过期", ui.title)
            assertEquals("重新登录", ui.loginLabel)
            assertTrue(ui.showSignOut)
        }
        val out = accountPresentation(AuthState(signedOut = true, dataOwner = 42))
        assertEquals("已退出登录", out.title)
        assertEquals("登录", out.loginLabel)
        assertTrue(out.description.contains("保留"))
        val other = accountPresentation(signed.copy(dataOwner = 99))
        assertTrue(other.description.contains("暂停"))
        assertEquals("重新登录", other.loginLabel)
        assertEquals("本机游客模式", accountPresentation(AuthState()).title)
    }
}
