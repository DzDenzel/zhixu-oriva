package com.planlock.plugin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginInstallerTest {
    @Test
    fun installAlwaysDownloadsVerifiesThenPersists() {
        val order = mutableListOf<String>()
        val manifest = sampleManifest()
        val installer = PluginInstaller(
            downloader = PluginDownloader { _, target -> order += "download"; target.writeText("bytes"); 5 },
            verifier = PluginPackageVerifier { _, sourceId -> order += "verify:$sourceId"; VerifiedPluginPackage(manifest, "fingerprint", "sha") },
            artifactStore = PluginArtifactStore { _, _ -> order += "store"; File("installed.plp") },
            tempFileFactory = { File.createTempFile("plugin", ".plp") },
        )

        val result = installer.install(PluginCatalogEntry(manifest, "https://repo/plugin.plp", "sha"))

        assertTrue(result is PluginInstallResult.Installed)
        assertEquals(listOf("download", "verify:official", "store"), order)
    }

    @Test
    fun verificationFailureIsVisibleAndNeverPersists() {
        var persisted = false
        val manifest = sampleManifest()
        val installer = PluginInstaller(
            downloader = PluginDownloader { _, target -> target.writeText("broken"); 6 },
            verifier = PluginPackageVerifier { _, _ -> error("signature mismatch") },
            artifactStore = PluginArtifactStore { _, _ -> persisted = true; File("unexpected") },
            tempFileFactory = { File.createTempFile("plugin", ".plp") },
        )

        val result = installer.install(PluginCatalogEntry(manifest, "https://repo/plugin.plp", "sha"))

        assertFalse(persisted)
        assertTrue(result is PluginInstallResult.Failed)
        assertTrue((result as PluginInstallResult.Failed).message.contains("signature"))
    }

    private fun sampleManifest() = PluginManifest(
        "sample", "Sample", "1", 1, "sample.Entry", "official", PluginSourceType.OFFICIAL, emptySet()
    )
}
