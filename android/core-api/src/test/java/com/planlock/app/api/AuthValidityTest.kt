package com.planlock.app.api

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AuthValidityTest {
    private fun token(payload: String) = "header." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payload.toByteArray(Charsets.UTF_8)) + ".signature"

    @Test fun expiryBoundaryUsesEpochSecondsAndDoesNotRejectMissingOrMalformedClaims() {
        assertFalse(jwtExpiredLocally(token("{\"exp\":101}"), 100))
        assertTrue(jwtExpiredLocally(token("{\"exp\":100}"), 100))
        assertTrue(jwtExpiredLocally(token("{\"exp\":99}"), 100))
        listOf(null, "broken", "a.%%%invalid.c", token("{}"), token("{\"exp\":\"bad\"}"),
            token("{\"exp\":100.5}")).forEach { assertFalse(jwtExpiredLocally(it, 100)) }
    }
}
