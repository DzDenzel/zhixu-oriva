package com.planlock.plugin

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogMetadataAndSourceTest {
    @After
    fun clearTransport() {
        PluginPublicTransport.get = null
    }

    @Test
    fun catalogFormatVersionDefaultsToOneAndRejectsNonPositive() {
        val legacy = PluginCatalogCodec.decode(
            """{"sourceId":"official","sourceType":"OFFICIAL","plugins":[]}"""
        )
        assertEquals(1, legacy.catalogFormatVersion)

        val v2 = PluginCatalogCodec.decode(
            """{"catalogFormatVersion":2,"sourceId":"official","sourceType":"OFFICIAL","plugins":[]}"""
        )
        assertEquals(2, v2.catalogFormatVersion)

        assertThrows(IllegalArgumentException::class.java) {
            PluginCatalogCodec.decode(
                """{"catalogFormatVersion":0,"sourceId":"official","sourceType":"OFFICIAL","plugins":[]}"""
            )
        }
    }

    @Test
    fun optionalMetadataDecodesCleanlyAndRejectsDangerousSchemes() {
        val manifest = PluginManifestCodec.decode(
            """
            {
              "id":"focus-advice",
              "name":"专注建议",
              "version":"0.1.0",
              "contractVersion":2,
              "type":"js",
              "entryPoint":"entryPoint",
              "sourceId":"official",
              "sourceType":"OFFICIAL",
              "author":"  ORIVA Team  ",
              "homepage":"https://oriva.example.org/plugins/focus",
              "repository":"http://git.example.lan/oriva/focus",
              "license":"MIT"
            }
            """.trimIndent()
        )
        assertEquals("ORIVA Team", manifest.author)
        assertEquals("https://oriva.example.org/plugins/focus", manifest.homepage)
        assertEquals("http://git.example.lan/oriva/focus", manifest.repository)
        assertEquals("MIT", manifest.license)

        val legacy = PluginManifestCodec.decode(
            """{"id":"force-blocker","name":"强制拦截","version":"1.0.0","contractVersion":1,"entryPoint":"a.B","sourceId":"official","sourceType":"OFFICIAL"}"""
        )
        assertNull(legacy.author)
        assertNull(legacy.homepage)
        assertNull(legacy.repository)
        assertNull(legacy.license)

        for (badUrl in listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "https://user:pass@example.org/repo",
            "not-a-url",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PluginManifestCodec.decode(
                    """{"id":"x","name":"X","version":"1","contractVersion":1,"entryPoint":"a.B","sourceId":"official","sourceType":"OFFICIAL","homepage":"$badUrl"}"""
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                PluginCatalogCodec.decode(
                    """{"sourceId":"official","sourceType":"OFFICIAL","plugins":[{"id":"x","name":"X","version":"1","contractVersion":1,"entryPoint":"a.B","downloadUrl":"x.plp","sha256":"abc","repository":"$badUrl"}]}"""
                )
            }
        }
    }

    @Test
    fun catalogItemPropagatesOptionalMetadataToCatalogEntry() {
        val doc = PluginCatalogCodec.decode(
            """
            {
              "catalogFormatVersion":1,
              "sourceId":"community-hub",
              "sourceType":"THIRD_PARTY",
              "plugins":[
                {
                  "id":"note-helper",
                  "name":"笔记助手",
                  "version":"1.0.0",
                  "contractVersion":2,
                  "type":"js",
                  "entryPoint":"run",
                  "downloadUrl":"note-helper-1.0.0.pljs",
                  "sha256":"0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                  "author":"Alice",
                  "homepage":"https://alice.example.org/note",
                  "repository":"https://github.com/alice/note-helper",
                  "license":"Apache-2.0"
                }
              ]
            }
            """.trimIndent()
        )
        val entry = doc.plugins.single().toEntry(doc)
        assertEquals("Alice", entry.manifest.author)
        assertEquals("https://alice.example.org/note", entry.manifest.homepage)
        assertEquals("https://github.com/alice/note-helper", entry.manifest.repository)
        assertEquals("Apache-2.0", entry.manifest.license)
    }

    @Test
    fun customSourceUrlValidationEnforcesHttpsAndExplicitLanGate() {
        assertEquals(
            "https://plugins.example.org/catalog.json",
            PluginSourcePolicy.validateCatalogUrl("https://plugins.example.org/catalog.json", lanAllowed = false),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginSourcePolicy.validateCatalogUrl("http://plugins.example.org/catalog.json", lanAllowed = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginSourcePolicy.validateCatalogUrl("http://192.168.1.20:8080/catalog.json", lanAllowed = false)
        }
        assertEquals(
            "http://192.168.1.20:8080/catalog.json",
            PluginSourcePolicy.validateCatalogUrl("http://192.168.1.20:8080/catalog.json", lanAllowed = true),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginSourcePolicy.validateCatalogUrl("https://user:pass@plugins.example.org/catalog.json", lanAllowed = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginSourcePolicy.validateCatalogUrl("https://plugins.example.org/catalog.json?token=1", lanAllowed = false)
        }
    }

    @Test
    fun customSourcesNeverOverwriteBuiltInTrustAnchors() {
        for (reserved in listOf("official", "OFFICIAL", "third-party", "THIRD-PARTY")) {
            assertThrows(IllegalArgumentException::class.java) {
                PluginSourcePolicy.validateSourceId(reserved)
            }
        }
        val forged = CustomPluginSource(
            id = "official",
            name = "Forged",
            catalogUrl = "https://evil.example.org/catalog.json",
            trustedFingerprints = setOf("AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA"),
        )
        val merged = PluginSourcePolicy.mergeTrustAnchors(PluginTrustAnchors.fingerprintsBySource, listOf(forged))
        assertEquals(PluginTrustAnchors.fingerprintsBySource["official"], merged["official"])
        assertEquals(PluginTrustAnchors.fingerprintsBySource["third-party"], merged["third-party"])
    }

    @Test
    fun safeModeBlocksAllThirdPartyDownloadsBeforeNetworkFetch() {
        var downloadCalls = 0
        val installer = PluginInstaller(
            downloader = PluginDownloader { _, target ->
                downloadCalls++
                target.writeText("bytes")
                5L
            },
            verifier = PluginPackageVerifier { _, _ -> error("should not verify") },
            artifactStore = PluginArtifactStore { _, _ -> File("unused.plp") },
            tempFileFactory = { File.createTempFile("safe-mode-", ".plp") },
        )
        val thirdPartyEntry = PluginCatalogEntry(
            manifest = PluginManifest(
                id = "custom-plugin",
                name = "Custom",
                version = "1.0.0",
                contractVersion = 1,
                entryPoint = "c.Entry",
                sourceId = "custom-repo",
                sourceType = PluginSourceType.THIRD_PARTY,
            ),
            downloadUrl = "https://custom.example.org/plugin.plp",
            sha256 = "ABC",
        )
        val blocked = installer.install(thirdPartyEntry, safeMode = true)
        assertTrue(blocked is PluginInstallResult.Failed)
        assertEquals(0, downloadCalls)
        assertFalse(PluginSourcePolicy.canDownloadFromSource(PluginSourceType.THIRD_PARTY, safeMode = true))
        assertTrue(PluginSourcePolicy.canDownloadFromSource(PluginSourceType.OFFICIAL, safeMode = true))
    }

    @Test
    fun unanchoredCustomSourceCannotSkipSignatureVerification() {
        val mergedAnchors = PluginSourcePolicy.mergeTrustAnchors(
            PluginTrustAnchors.fingerprintsBySource,
            listOf(
                CustomPluginSource(
                    id = "unanchored-community",
                    name = "Unanchored",
                    catalogUrl = "https://community.example.org/catalog.json",
                    trustedFingerprints = emptySet(),
                )
            ),
        )
        val verifier = SignedJarPluginPackageVerifier { mergedAnchors }
        val file = File.createTempFile("untrusted-", ".pljs")
        try {
            javaClass.getResourceAsStream("/signing/official.pljs")!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val error = assertThrows(IllegalArgumentException::class.java) {
                verifier.verify(file, "unanchored-community")
            }
            assertTrue(error.message.orEmpty().contains("No trust anchors registered"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun catalogClientRejectsHttpsToHttpDowngrade() {
        PluginPublicTransport.get = {
            """
            {
              "sourceId":"community",
              "sourceType":"THIRD_PARTY",
              "plugins":[
                {
                  "id":"bad",
                  "name":"Bad",
                  "version":"1.0.0",
                  "contractVersion":1,
                  "entryPoint":"bad.Entry",
                  "downloadUrl":"http://cdn.example.org/bad.plp",
                  "sha256":"0123"
                }
              ]
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginCatalogClient().fetch("https://community.example.org/catalog.json", lanAllowed = false)
        }
    }
}
