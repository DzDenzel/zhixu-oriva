package com.planlock.plugin

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryCompatibilityTest {

    private fun findRegistryRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val candidate = File(dir, "deploy/plugin-registry/registry.json")
            if (candidate.isFile) return candidate.parentFile
            dir = dir.parentFile
        }
        error("Cannot locate deploy/plugin-registry/registry.json from ${File(".").canonicalPath}")
    }

    private fun findPythonCommand(): String {
        for (candidate in listOf("python3", "python")) {
            val ok = runCatching {
                val proc = ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0
            }.getOrDefault(false)
            if (ok) return candidate
        }
        error("Python 3 runtime is required to execute registry_tool.py compatibility build")
    }

    @Test
    fun generatedMorphologyBCatalogAndSignedArtifactsPassAndroidCodecAndInstaller() {
        val registryRoot = findRegistryRoot()
        val registryJsonFile = File(registryRoot, "registry.json")
        val rawRegistry = Json.parseToJsonElement(registryJsonFile.readText(Charsets.UTF_8)).jsonObject
        val sourceId = rawRegistry.getValue("sourceId").jsonPrimitive.content
        val fingerprints = rawRegistry.getValue("trustedFingerprints").jsonArray
            .map { PluginSourcePolicy.normalizeFingerprint(it.jsonPrimitive.content) }
            .toSet()

        val tempDir = Files.createTempDirectory("oriva-reg-compat-").toFile()
        try {
            val python = findPythonCommand()
            val toolScript = File(registryRoot, "registry_tool.py")
            val process = ProcessBuilder(
                python,
                toolScript.absolutePath,
                "build",
                "--registry",
                registryJsonFile.absolutePath,
                "--output",
                tempDir.absolutePath,
                "--clean",
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertTrue("registry_tool.py timed out", process.waitFor(60, TimeUnit.SECONDS))
            assertEquals("registry_tool.py build failed: $output", 0, process.exitValue())

            val catalogFile = File(tempDir, "catalog.json")
            assertTrue("Missing generated catalog.json", catalogFile.isFile)

            val document = PluginCatalogCodec.decode(catalogFile.readText(Charsets.UTF_8))
            assertEquals(1, document.catalogFormatVersion)
            assertEquals(sourceId, document.sourceId)
            assertEquals(PluginSourceType.THIRD_PARTY, document.sourceType)
            assertEquals(listOf("ink-focus-cue", "quiet-rhythm-tip"), document.plugins.map { it.id })

            val catalogUrl = "https://community.example.org/oriva-plugins/catalog.json"
            val customSource = CustomPluginSource(
                id = sourceId,
                name = "ORIVA Community Examples",
                catalogUrl = catalogUrl,
                trustedFingerprints = fingerprints,
            )
            PluginSourcePolicy.validateCatalogForCustomSource(customSource, document)

            val mergedAnchors = PluginSourcePolicy.mergeTrustAnchors(
                PluginTrustAnchors.fingerprintsBySource,
                listOf(customSource),
            )
            assertEquals(PluginTrustAnchors.fingerprintsBySource["official"], mergedAnchors["official"])
            assertEquals(PluginTrustAnchors.fingerprintsBySource["third-party"], mergedAnchors["third-party"])

            val verifier = SignedJarPluginPackageVerifier { mergedAnchors }
            val installOutDir = File(tempDir, "installed").apply { mkdirs() }
            val installer = PluginInstaller(
                downloader = PluginDownloader { url, target ->
                    val resolved = PluginSourcePolicy.validateResolvedDownloadUrl(catalogUrl, url, lanAllowed = false)
                    val prefix = "https://community.example.org/oriva-plugins/"
                    assertTrue("Unexpected downloadUrl $resolved", resolved.startsWith(prefix))
                    val relPath = resolved.removePrefix(prefix)
                    val artifact = File(tempDir, relPath)
                    assertTrue("Missing generated artifact $relPath", artifact.isFile)
                    artifact.copyTo(target, overwrite = true)
                    target.length()
                },
                verifier = verifier,
                artifactStore = PluginArtifactStore { source, manifest ->
                    File(installOutDir, "${manifest.id}-${manifest.version}.pljs").also {
                        source.copyTo(it, overwrite = true)
                    }
                },
                tempFileFactory = { File.createTempFile("compat-dl-", ".pljs", tempDir) },
            )

            for (item in document.plugins) {
                val resolvedDownload = java.net.URL(java.net.URL(catalogUrl), item.downloadUrl).toString()
                val entry = item.copy(downloadUrl = resolvedDownload).toEntry(document)
                val result = installer.install(entry, safeMode = false, customSource = customSource)
                assertTrue("Expected install success for ${item.id}, got $result", result is PluginInstallResult.Installed)
                val installed = result as PluginInstallResult.Installed
                assertEquals(item.sha256, installed.sha256)
                assertTrue(installed.signerFingerprint in fingerprints)
            }

            // Negative check: wrong trust anchor must reject the generated artifact.
            val untrustedVerifier = SignedJarPluginPackageVerifier(
                mapOf(sourceId to setOf("00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"))
            )
            val sampleArtifact = File(tempDir, "artifacts/ink-focus-cue-0.1.0.pljs")
            assertThrows(IllegalArgumentException::class.java) {
                untrustedVerifier.verify(sampleArtifact, sourceId)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
