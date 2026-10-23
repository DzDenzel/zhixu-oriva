package com.planlock.app.api

import com.planlock.core.api.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class ConnectionConfigTest {
    private val valid = """{"version":1,"instanceLabel":"我的后端","apiBaseUrl":"https://example.org/api/v1/","pluginsCatalogUrl":"https://plugins.example.org/catalog/","lanAllowed":false}"""

    @Test fun roundTripAndIndependentCatalog() {
        val config = ConnectionConfig.decode(valid)
        assertEquals("https://example.org/api/v1/", config.instanceId)
        assertEquals("https://plugins.example.org/catalog/official", config.catalogUrl("official"))
        assertEquals(config, ConnectionConfig.decode(config.encode()))
        assertEquals(config.instanceId, config.copy(instanceLabel = "新名称").instanceId)
    }

    @Test fun rejectsSecretsUnknownVersionsAndUrlCredentialsWithoutEchoingInput() {
        listOf(
            valid.replace("\"version\":1", "\"version\":2"),
            valid.dropLast(1) + ",\"secret\":\"DO_NOT_ECHO\"}",
            valid.replace("https://example.org", "https://" + "user:DO_NOT_ECHO@example.org"),
            valid.replace("api/v1/", "api/v1/?token=DO_NOT_ECHO"),
            valid.replace("https://example.org", "ftp://example.org"),
            valid.replace("\"lanAllowed\":false", "\"lanAllowed\":\"false\""),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"instanceLabel\":\"我的后端\"", "\"instanceLabel\":\"\""),
            valid.replace("https://example.org", "http://example.org"),
        ).forEach { raw ->
            try { ConnectionConfig.decode(raw); fail("Invalid configuration accepted") }
            catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains("DO_NOT_ECHO")) }
        }
    }

    @Test fun privateAddressRequiresExplicitLanPermissionAndPublicHttpIsNeverAllowed() {
        val privateConfig = valid.replace("https://example.org", "http://192.168.1.5:8080")
        try { ConnectionConfig.decode(privateConfig); fail("LAN permission required") } catch (_: IllegalArgumentException) { }
        val lan = ConnectionConfig.decode(privateConfig.replace("\"lanAllowed\":false", "\"lanAllowed\":true"))
        assertTrue(lan.lanAllowed)
        try { ConnectionConfig.decode(valid.replace("https://example.org", "http://example.org").replace("false", "true")); fail("Public cleartext accepted") }
        catch (_: IllegalArgumentException) { }
        if (BuildConfig.BUILD_TYPE == "release") {
            assertEquals("", BuildConfig.API_BASE_URL)
            assertNull(ConnectionConfig.initial)
        } else {
            assertEquals(BuildConfig.API_BASE_URL, ConnectionConfig.initial?.apiBaseUrl)
        }
    }
}
