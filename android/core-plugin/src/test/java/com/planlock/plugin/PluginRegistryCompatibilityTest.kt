package com.planlock.plugin

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryCompatibilityTest {

    private fun findRepoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "deploy/plugin-registry/registry.json").isFile) return dir
            dir = dir.parentFile
        }
        error("Cannot locate repository root from ${File(".").canonicalPath}")
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

    private fun runRegistryTool(
        python: String,
        toolScript: File,
        tempDir: File,
        vararg args: String,
    ): Pair<Int, String> {
        val logFile = File.createTempFile("reg-tool-", ".log", tempDir)
        val process = ProcessBuilder(python, toolScript.absolutePath, *args)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("registry_tool.py timed out after 60s")
        }
        val output = logFile.readText(Charsets.UTF_8)
        return process.exitValue() to output
    }

    @Test
    fun generatedMorphologyBCatalogAndSignedArtifactsPassAndroidCodecAndInstaller() {
        val repoRoot = findRepoRoot()
        val registryRoot = File(repoRoot, "deploy/plugin-registry")
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
            val distDir = File(tempDir, "dist")
            val (exitCode, output) = runRegistryTool(
                python, toolScript, tempDir,
                "build", "--registry", registryJsonFile.absolutePath, "--output", distDir.absolutePath, "--clean",
            )
            assertEquals("registry_tool.py build failed: $output", 0, exitCode)

            val catalogFile = File(distDir, "catalog.json")
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
            val installRoot = File(tempDir, "installed").apply { mkdirs() }
            val installer = PluginInstaller(
                downloader = PluginDownloader { url, target ->
                    val resolved = PluginSourcePolicy.validateResolvedDownloadUrl(catalogUrl, url, lanAllowed = false)
                    val prefix = "https://community.example.org/oriva-plugins/"
                    assertTrue("Unexpected downloadUrl $resolved", resolved.startsWith(prefix))
                    val relPath = resolved.removePrefix(prefix)
                    val artifact = File(distDir, relPath)
                    assertTrue("Missing generated artifact $relPath", artifact.isFile)
                    artifact.copyTo(target, overwrite = true)
                    target.length()
                },
                verifier = verifier,
                artifactStore = PluginArtifactStore { source, manifest ->
                    // Mirrors FilePluginArtifactStore.safeSegment() + layout without requiring Android Context.
                    val segPattern = Regex("[A-Za-z0-9._-]+")
                    require(segPattern.matches(manifest.id) && segPattern.matches(manifest.version))
                    val dir = File(installRoot, "${manifest.id}/${manifest.version}").apply { mkdirs() }
                    File(dir, "plugin.plp").also { source.copyTo(it, overwrite = true) }
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

            // Negative parity: wrong trust anchor must reject the generated artifact on Android.
            val untrustedVerifier = SignedJarPluginPackageVerifier(
                mapOf(sourceId to setOf("00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"))
            )
            val sampleArtifact = File(distDir, "artifacts/ink-focus-cue-0.1.0.pljs")
            assertThrows(IllegalArgumentException::class.java) {
                untrustedVerifier.verify(sampleArtifact, sourceId)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun validatorAndAndroidCodecsRejectSameInvalidCatalogAndManifestMatrix() {
        val repoRoot = findRepoRoot()
        val registryRoot = File(repoRoot, "deploy/plugin-registry")
        val toolScript = File(registryRoot, "registry_tool.py")
        val python = findPythonCommand()

        val tempDir = Files.createTempDirectory("oriva-reg-neg-parity-").toFile()
        try {
            val samplePljs = File(registryRoot, "artifacts/ink-focus-cue-0.1.0.pljs")
            val validFp = "92:70:60:2B:17:33:20:CE:46:AD:93:06:EE:F9:63:CB:C0:47:32:6C:77:9D:8D:95:93:36:FE:BB:A6:D3:2E:C1"

            // 1. Int.MAX_VALUE overflow for catalogFormatVersion rejected by both Python and Kotlin.
            val overflowCatalogJson = """{"catalogFormatVersion":2147483648,"sourceId":"community-x","sourceType":"THIRD_PARTY","plugins":[]}"""
            assertThrows(IllegalArgumentException::class.java) {
                PluginCatalogCodec.decode(overflowCatalogJson)
            }
            val overflowDir = File(tempDir, "overflow").apply { mkdirs() }
            File(overflowDir, "registry.json").writeText(
                """{"catalogFormatVersion":2147483648,"sourceId":"community-x","sourceType":"THIRD_PARTY","trustedFingerprints":["$validFp"],"plugins":[]}""",
                Charsets.UTF_8,
            )
            val (ovCode, _) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(overflowDir, "registry.json").absolutePath)
            assertNotEquals(0, ovCode)

            // 2. Reserved sourceId rejected by both PluginSourcePolicy and Python validator.
            assertThrows(IllegalArgumentException::class.java) { PluginSourcePolicy.validateSourceId("official") }
            val resDir = File(tempDir, "reserved").apply { mkdirs() }
            File(resDir, "registry.json").writeText(
                """{"catalogFormatVersion":1,"sourceId":"official","sourceType":"THIRD_PARTY","trustedFingerprints":["$validFp"],"plugins":[]}""",
                Charsets.UTF_8,
            )
            val (resCode, _) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(resDir, "registry.json").absolutePath)
            assertNotEquals(0, resCode)

            // 3. Invalid FormSection contribution on contractVersion=1 rejected by both PluginManifestCodec and Python validator.
            val badContribManifest = """
                {
                  "id":"bad-contrib","name":"Bad","version":"1.0.0","contractVersion":1,"type":"dex",
                  "entryPoint":"a.B","sourceId":"community-x","sourceType":"THIRD_PARTY",
                  "contributions":[{"kind":"FormSection","id":"f","slot":"plan.edit.configuration","title":"T","fields":[{"id":"sw","kind":"switch","label":"S","value":true,"onEvent":"e"}]}]
                }
            """.trimIndent()
            assertThrows(IllegalArgumentException::class.java) { PluginManifestCodec.decode(badContribManifest) }

            // 4. Unsigned / evil META-INF entry rejected by both SignedJarPluginPackageVerifier and Python validator.
            val evilJar = File(tempDir, "evil-meta.pljs")
            ZipOutputStream(evilJar.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("META-INF/evil.txt"))
                z.write("bad".toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("plugin.json"))
                z.write(badContribManifest.toByteArray())
                z.closeEntry()
            }
            val verifier = SignedJarPluginPackageVerifier(mapOf("community-x" to setOf(validFp)))
            assertThrows(IllegalArgumentException::class.java) { verifier.verify(evilJar, "community-x") }
            val (inspectCode, _) = runRegistryTool(python, toolScript, tempDir, "inspect-cert", "--package", evilJar.absolutePath)
            assertNotEquals(0, inspectCode)

            // 5. Mixed-signer fixture rejected for official anchor by both Android and Python.
            val mixedFile = File(tempDir, "mixed.pljs")
            javaClass.getResourceAsStream("/signing/mixed.pljs")!!.use { input ->
                mixedFile.outputStream().use { input.copyTo(it) }
            }
            val officialVerifier = SignedJarPluginPackageVerifier(PluginTrustAnchors.fingerprintsBySource)
            assertThrows(IllegalArgumentException::class.java) { officialVerifier.verify(mixedFile, "official") }

            val mixedDir = File(tempDir, "mixed-reg").apply { File(this, "artifacts").mkdirs() }
            mixedFile.copyTo(File(mixedDir, "artifacts/mixed.pljs"), overwrite = true)
            File(mixedDir, "registry.json").writeText(
                """
                {
                  "catalogFormatVersion":1,"sourceId":"community-mixed","sourceType":"THIRD_PARTY",
                  "trustedFingerprints":["${PluginTrustAnchors.fingerprintsBySource.getValue("official").first()}"],
                  "plugins":[{
                    "artifact":"mixed.pljs","id":"focus-advice","name":"专注建议","version":"0.1.0",
                    "contractVersion":2,"type":"js","entryPoint":"entryPoint","requiredCapabilities":["QUERY_TODAY"],
                    "description":"mixed test"
                  }]
                }
                """.trimIndent(),
                Charsets.UTF_8,
            )
            val (mixCode, _) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(mixedDir, "registry.json").absolutePath)
            assertNotEquals(0, mixCode)

            // 6. Dangerous URL rejected by both PluginCatalogCodec and Python validator.
            assertThrows(IllegalArgumentException::class.java) {
                PluginCatalogCodec.decode(
                    """{"sourceId":"community-x","sourceType":"THIRD_PARTY","plugins":[{"id":"x","name":"X","version":"1.0","contractVersion":2,"type":"js","entryPoint":"run","downloadUrl":"./artifacts/x.pljs","sha256":"${"A".repeat(64)}","homepage":"javascript:alert(1)"}]}"""
                )
            }
            val urlDir = File(tempDir, "bad-url-reg").apply { File(this, "artifacts").mkdirs() }
            samplePljs.copyTo(File(urlDir, "artifacts/ink-focus-cue-0.1.0.pljs"), overwrite = true)
            File(urlDir, "registry.json").writeText(
                """
                {
                  "catalogFormatVersion":1,"sourceId":"oriva-community-examples","sourceType":"THIRD_PARTY",
                  "trustedFingerprints":["$validFp"],
                  "plugins":[{
                    "artifact":"ink-focus-cue-0.1.0.pljs","id":"ink-focus-cue","name":"墨序专注提示","version":"0.1.0",
                    "contractVersion":2,"type":"js","entryPoint":"entryPoint","requiredCapabilities":["QUERY_TODAY","FEEDBACK"],
                    "description":"desc","homepage":"javascript:alert(1)"
                  }]
                }
                """.trimIndent(),
                Charsets.UTF_8,
            )
            val (urlCode, _) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(urlDir, "registry.json").absolutePath)
            assertNotEquals(0, urlCode)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
