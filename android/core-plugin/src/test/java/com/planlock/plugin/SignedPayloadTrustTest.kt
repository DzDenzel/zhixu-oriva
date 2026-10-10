package com.planlock.plugin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SignedPayloadTrustTest {
    private val verifier = SignedJarPluginPackageVerifier(mapOf("official" to setOf(
        "C5:9E:81:65:8F:31:08:29:B4:23:BE:24:2D:C3:28:B3:76:CB:CE:1B:C6:1C:1F:F2:31:31:61:12:41:2B:8C:44")))
    private fun fixture(name: String, check: (File) -> Unit) {
        val file = File.createTempFile("signed-payload-", ".pljs")
        try {
            javaClass.getResourceAsStream("/signing/$name.pljs")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            check(file)
        } finally { file.delete() }
    }
    @Test fun completeOfficialJsPackageVerifiesEveryPayload() = fixture("official") { file ->
        assertEquals(PluginType.JS, verifier.verify(file, "official").manifest.type)
    }
    @Test fun trustedManifestCannotAuthorizeCodeSignedOnlyByADifferentSource() = fixture("mixed") { file ->
        // Both entries are cryptographically signed. Only plugin.json has an official signer.
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(file, "official") }
    }
}
