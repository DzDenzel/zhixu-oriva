package com.planlock.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginJsContractTest {
    private fun manifest(extra: String = "", version: Int = 1) = """{"id":"demo","name":"Demo","version":"1.0","contractVersion":$version,"entryPoint":"main","sourceId":"official","sourceType":"OFFICIAL"$extra}"""

    @Test fun legacyPackageDefaultsToDex() {
        assertEquals("DEX", PluginManifestCodec.decode(manifest()).type.name)
    }
    @Test fun jsV2SelectsJsEntryWithDeclaredQueries() {
        val decoded = PluginManifestCodec.decode(manifest(""", "type":"js", "requiredCapabilities":["QUERY_TODAY"]""", 2))
        assertEquals("JS", decoded.type.name)
        assertEquals("QUERY_TODAY", decoded.requiredCapabilities.single().name)
    }
    @Test fun rejectsJsWithLegacyContract() {
        assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(manifest(""", "type":"js"""")) }
    }
    @Test fun rejectsUnknownType() {
        assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(manifest(""", "type":"native"""", 2)) }
    }
    @Test fun editorFormIsPluginDeclaredAndDisappearsWithLoadedPlugin() {
        val form = """, "contributions":[{"kind":"FormSection","id":"settings","slot":"plan.edit.configuration","title":"Advice","fields":[{"id":"enabled","kind":"switch","label":"Enabled","value":true,"onEvent":"changed"}]}]"""
        val decoded = PluginManifestCodec.decode(manifest(form, 2))
        val loaded = LoadedPlugin(decoded, object : PlanLockPlugin { override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) {} })
        assertEquals(1, projectContributions(listOf(loaded), "plan.edit.configuration").size)
        assertTrue(projectContributions(emptyList(), "plan.edit.configuration").isEmpty())
    }
    @Test fun invalidSwitchAndUndeclaredFormEventCannotDispatch() {
        val form = """, "contributions":[{"kind":"FormSection","id":"settings","slot":"plan.edit.configuration","title":"Advice","fields":[{"id":"enabled","kind":"switch","label":"Enabled","value":"yes","onEvent":"changed"}]}]"""
        assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(manifest(form, 2)) }
    }
    @Test fun duplicateFieldAcrossSectionsCannotAliasPersistedConfiguration() {
        val section = """{"kind":"FormSection","id":"first","slot":"plan.edit.configuration","title":"Advice","fields":[{"id":"enabled","kind":"switch","label":"Enabled","value":true,"onEvent":"changed"}]}"""
        val second = section.replace("\"first\"", "\"second\"")
        assertThrows(IllegalArgumentException::class.java) {
            PluginManifestCodec.decode(manifest(""", "contributions":[$section,$second]""", 2))
        }
    }
}
