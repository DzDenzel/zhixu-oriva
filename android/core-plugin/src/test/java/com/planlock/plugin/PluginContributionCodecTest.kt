package com.planlock.plugin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginContributionCodecTest {
    @Test fun singleApplicationInputIsPreservedAndUnknownInputIsRejected() {
        val manifest = PluginManifestCodec.decode(raw("""[{"kind":"ActionRow","id":"choose","slot":"plan.detail.protection","label":"选应用","actionId":"choose","input":"APPLICATION_SINGLE"}]"""))
        assertEquals(ContributionActionInput.APPLICATION_SINGLE, (manifest.contributions.single() as PluginContribution.ActionRow).input)
        assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(raw("""[{"kind":"ActionRow","id":"choose","slot":"plan.detail.protection","label":"选应用","actionId":"choose","input":"ARBITRARY_FORM"}]""")) }
    }
    private fun raw(contributions: String) = """{"id":"sample","name":"Sample","version":"1.0.0","contractVersion":1,"entryPoint":"sample.Entry","sourceId":"official","sourceType":"OFFICIAL","contributions":$contributions}"""

    @Test fun declaredStatusAndActionSurviveDecodeForHostRendering() {
        val manifest = PluginManifestCodec.decode(raw("""[
            {"kind":"StatusLine","id":"state","slot":"plan.detail.protection","text":"准备就绪","tone":"active"},
            {"kind":"ActionRow","id":"play","slot":"plan.detail.protection","label":"开始音乐","actionId":"play"}
        ]"""))
        val encoded = Json.parseToJsonElement(Json.encodeToString(manifest)).jsonObject
        val declared = encoded["contributions"]?.jsonArray
        assertEquals("Declarations must not be silently discarded", 2, declared?.size)
        assertEquals("开始音乐", declared!![1].jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test fun unknownShapeIsRejectedInsteadOfSilentlyRenderingArbitraryUi() {
        assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(raw("""[{"kind":"PasswordForm","id":"x","slot":"plan.detail.protection"}]""")) }
    }

    @Test fun duplicateContributionIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(raw("""[
            {"kind":"StatusLine","id":"same","slot":"plan.detail.protection","text":"一"},
            {"kind":"ActionRow","id":"same","slot":"plan.detail.protection","label":"二","actionId":"two"}
        ]""")) }
    }

    @Test fun unknownSlotCannotClaimAnotherHostSurface() {
        assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(raw("""[{"kind":"StatusLine","id":"x","slot":"password.entry","text":"不允许"}]""")) }
    }

    @Test fun unsupportedShapeVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(raw("""[{"kind":"StatusLine","shapeVersion":9,"id":"x","slot":"plan.detail.protection","text":"未来"}]""")) }
    }

    @Test fun undeclaredSystemPermissionRequestIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(raw("""[{"kind":"CapabilityRequest","id":"grant","slot":"plan.detail.protection","reason":"用于插件功能","capabilityId":"ACCESSIBILITY","actionId":"grant"}]""")) }
    }
}
