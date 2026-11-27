package com.planlock.plugin

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun destroyProcessTree(proc: Process) {
        val phClass = Class.forName("java.lang.ProcessHandle")
        val destroyForciblyMethod = phClass.getMethod("destroyForcibly")
        val onExitMethod = phClass.getMethod("onExit")
        val isAliveMethod = phClass.getMethod("isAlive")
        val descendantsMethod = Process::class.java.getMethod("descendants")

        val failures = mutableListOf<Throwable>()
        val descendants = mutableListOf<Any>()
        try {
            @Suppress("UNCHECKED_CAST")
            val stream = descendantsMethod.invoke(proc) as java.util.stream.Stream<Any>
            stream.use { s -> s.forEach { descendants.add(it) } }
        } catch (t: Throwable) {
            failures += t
        }

        for (handle in descendants.asReversed()) {
            try {
                destroyForciblyMethod.invoke(handle)
            } catch (t: Throwable) {
                failures += t
            }
        }

        try {
            proc.destroyForcibly()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                failures += AssertionError("Root process did not terminate within 5s")
            }
        } catch (t: Throwable) {
            failures += t
        }

        for (handle in descendants) {
            try {
                val future = onExitMethod.invoke(handle) as CompletableFuture<*>
                future.get(5, TimeUnit.SECONDS)
                if (isAliveMethod.invoke(handle) as Boolean) {
                    failures += AssertionError("Descendant process remained alive after termination")
                }
            } catch (t: Throwable) {
                failures += t
            }
        }

        if (failures.isNotEmpty()) {
            val error = AssertionError("Process tree teardown failed (${failures.size} error(s))")
            failures.forEach { error.addSuppressed(it) }
            throw error
        }
    }

    private fun findPythonCommand(): String {
        for (candidate in listOf("python3", "python")) {
            val proc = try {
                ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: IOException) {
                continue
            }
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                destroyProcessTree(proc)
                continue
            }
            if (proc.exitValue() == 0) return candidate
        }
        error("Python 3 runtime is required to execute registry_tool.py compatibility build")
    }

    @Test
    fun destroyProcessTreeTerminatesRealProcessAndDescendantWithoutIllegalAccessException() {
        val python = findPythonCommand()
        val script =
            "import os, subprocess, sys, time; " +
                "g = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)']); " +
                "print('READY:' + str(os.getpid()) + ':' + str(g.pid), flush=True); " +
                "time.sleep(60)"
        val proc = ProcessBuilder(python, "-c", script)
            .redirectErrorStream(true)
            .start()
        var grandchildPid: Long = -1L
        try {
            val outputLines = mutableListOf<String>()
            val reader = proc.inputStream.bufferedReader()
            var line: String? = null
            while (true) {
                val next = reader.readLine() ?: break
                outputLines += next
                if (next.startsWith("READY:")) {
                    line = next
                    break
                }
            }
            val readyLine = line
                ?: error("Subprocess exited without READY marker. Output:\n${outputLines.joinToString("\n")}")
            grandchildPid = readyLine.substringAfterLast(':').toLong()
            destroyProcessTree(proc)
            assertFalse("Root process should be terminated", proc.isAlive)

            val phClass = Class.forName("java.lang.ProcessHandle")
            val ofMethod = phClass.getMethod("of", Long::class.javaPrimitiveType)
            val isAliveMethod = phClass.getMethod("isAlive")
            @Suppress("UNCHECKED_CAST")
            val opt = ofMethod.invoke(null, grandchildPid) as java.util.Optional<Any>
            if (opt.isPresent) {
                assertFalse("Grandchild process $grandchildPid should be terminated", isAliveMethod.invoke(opt.get()) as Boolean)
            }
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
            if (grandchildPid > 0L) {
                runCatching {
                    val phClass = Class.forName("java.lang.ProcessHandle")
                    @Suppress("UNCHECKED_CAST")
                    val opt = phClass.getMethod("of", Long::class.javaPrimitiveType).invoke(null, grandchildPid) as java.util.Optional<Any>
                    if (opt.isPresent) {
                        phClass.getMethod("destroyForcibly").invoke(opt.get())
                    }
                }
            }
        }
    }

    private fun findJdkTool(toolName: String): String {
        val exeSuffix = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) ".exe" else ""
        val candidates = buildList {
            System.getProperty("java.home")?.let { add(File(it, "bin/$toolName$exeSuffix")) }
            System.getenv("JAVA_HOME")?.let { add(File(it, "bin/$toolName$exeSuffix")) }
        }
        candidates.firstOrNull { it.isFile }?.let { return it.absolutePath }
        return toolName
    }

    private fun runToolChecked(tempDir: File, envOverrides: Map<String, String>, vararg command: String): String {
        val logFile = File.createTempFile("jdk-tool-", ".log", tempDir)
        val pb = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
        pb.environment().putAll(envOverrides)
        val proc = pb.start()
        val finished = proc.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            destroyProcessTree(proc)
            error("Command timed out: ${command.joinToString(" ")}")
        }
        val output = logFile.readText(Charsets.UTF_8)
        assertEquals("Command failed (${command.joinToString(" ")}): $output", 0, proc.exitValue())
        return output
    }

    private fun createEphemeralSigner(tempDir: File, alias: String, cn: String): Triple<File, Map<String, String>, String> {
        val keytool = findJdkTool("keytool")
        val ksFile = File(tempDir, "$alias.p12")
        val envVar = "ORIVA_KT_${alias.uppercase()}"
        val pass = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val env = mapOf(envVar to pass)
        runToolChecked(
            tempDir, env,
            keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
            "-storetype", "PKCS12", "-keystore", ksFile.absolutePath,
            "-storepass:env", envVar, "-keypass:env", envVar,
            "-dname", "CN=$cn, O=ORIVA Test, C=CN",
        )
        val listOut = runToolChecked(
            tempDir, env,
            keytool, "-J-Duser.language=en", "-J-Duser.country=US", "-list", "-v",
            "-keystore", ksFile.absolutePath, "-storepass:env", envVar, "-alias", alias,
        )
        val match = Regex("""SHA256:\s*([0-9A-Fa-f:]{95})""").find(listOut)
            ?: error("Missing SHA256 fingerprint in keytool output: $listOut")
        return Triple(ksFile, env, match.groupValues[1].uppercase())
    }

    private fun signPackage(
        tempDir: File,
        pkg: File,
        ksFile: File,
        env: Map<String, String>,
        alias: String,
        sigFile: String? = null,
    ) {
        val jarsigner = findJdkTool("jarsigner")
        val envVar = env.keys.first()
        val args = mutableListOf(
            jarsigner, "-keystore", ksFile.absolutePath, "-storetype", "PKCS12",
            "-storepass:env", envVar, "-keypass:env", envVar,
        )
        if (sigFile != null) {
            args.add("-sigfile")
            args.add(sigFile)
        }
        args.add(pkg.absolutePath)
        args.add(alias)
        runToolChecked(tempDir, env, *args.toTypedArray())
    }

    private fun writePljs(target: File, manifestJson: String, extraEntries: Map<String, ByteArray> = emptyMap()) {
        ZipOutputStream(target.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("plugin.json"))
            z.write(manifestJson.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("index.js"))
            z.write("function entryPoint() { return { message: 'ok' }; }\n".toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for ((name, bytes) in extraEntries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }

    private fun runRegistryTool(
        python: String,
        toolScript: File,
        tempDir: File,
        vararg args: String,
    ): Pair<Int, String> {
        val logFile = File.createTempFile("reg-tool-", ".log", tempDir)
        val process = ProcessBuilder(python, "-I", toolScript.absolutePath, *args)
            .directory(tempDir)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            destroyProcessTree(process)
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
                    val segPattern = Regex("[A-Za-z0-9._-]+")
                    require(manifest.id != "." && manifest.id != ".." && segPattern.matches(manifest.id))
                    require(manifest.version != "." && manifest.version != ".." && segPattern.matches(manifest.version))
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
    fun validatorAndAndroidCodecsShareExactAcceptRejectSemanticsOnSameVerifiablePackages() {
        val repoRoot = findRepoRoot()
        val registryRoot = File(repoRoot, "deploy/plugin-registry")
        val toolScript = File(registryRoot, "registry_tool.py")
        val python = findPythonCommand()

        val tempDir = Files.createTempDirectory("oriva-reg-parity-matrix-").toFile()
        try {
            val (ksA, envA, fpA) = createEphemeralSigner(tempDir, "alpha", "Alpha Dev")
            val (ksB, envB, fpB) = createEphemeralSigner(tempDir, "beta", "Beta Dev")
            val samplePljs = File(registryRoot, "artifacts/ink-focus-cue-0.1.0.pljs")
            val sampleFp = "92:70:60:2B:17:33:20:CE:46:AD:93:06:EE:F9:63:CB:C0:47:32:6C:77:9D:8D:95:93:36:FE:BB:A6:D3:2E:C1"

            fun writeReg(dirName: String, sourceId: String, fp: String, artifact: String, entryJson: String): File {
                val dir = File(tempDir, dirName).apply { File(this, "artifacts").mkdirs() }
                val regFile = File(dir, "registry.json")
                regFile.writeText(
                    """{"catalogFormatVersion":1,"sourceId":"$sourceId","sourceType":"THIRD_PARTY","trustedFingerprints":["$fp"],"plugins":[$entryJson]}""",
                    Charsets.UTF_8,
                )
                return dir
            }

            fun baseEntry(artifact: String, id: String = "parity-js", version: String = "1.0.0", contractVersion: Int = 2, type: String = "js", entryPoint: String = "entryPoint"): String =
                """{"artifact":"$artifact","id":"$id","name":"Parity","version":"$version","contractVersion":$contractVersion,"type":"$type","entryPoint":"$entryPoint","requiredCapabilities":["QUERY_TODAY"],"description":"desc"}"""

            fun baseManifest(sourceId: String = "community-x", id: String = "parity-js", version: String = "1.0.0", contractVersion: Int = 2, extraJson: String = ""): String =
                """{"id":"$id","name":"Parity","version":"$version","contractVersion":$contractVersion,"type":"js","entryPoint":"entryPoint","sourceId":"$sourceId","sourceType":"THIRD_PARTY","requiredCapabilities":["QUERY_TODAY"]$extraJson}"""

            val androidVerifierA = SignedJarPluginPackageVerifier(mapOf("community-x" to setOf(fpA)))

            // 1. Same unsigned .pljs rejected by both Android verifier and Python validate.
            val unsignedDir = writeReg("unsigned", "community-x", fpA, "demo.pljs", baseEntry("demo.pljs"))
            val unsignedPkg = File(unsignedDir, "artifacts/demo.pljs")
            writePljs(unsignedPkg, baseManifest())
            assertThrows(IllegalArgumentException::class.java) { androidVerifierA.verify(unsignedPkg, "community-x") }
            val (unsCode, unsOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(unsignedDir, "registry.json").absolutePath)
            assertNotEquals(0, unsCode)
            assertTrue("Expected missing MANIFEST/signature reason: $unsOut", unsOut.contains("MANIFEST.MF") || unsOut.contains("Unsigned"))

            // 2. Signed package + unsigned case-variant meta-inf/extra.txt rejected by both Android and Python.
            val caseMetaDir = writeReg("casemeta", "community-x", fpA, "demo.pljs", baseEntry("demo.pljs"))
            val caseMetaPkg = File(caseMetaDir, "artifacts/demo.pljs")
            writePljs(caseMetaPkg, baseManifest())
            signPackage(tempDir, caseMetaPkg, ksA, envA, "alpha")
            val appendedCaseMeta = File(caseMetaDir, "artifacts/demo-case.pljs")
            ZipFile(caseMetaPkg).use { zin ->
                ZipOutputStream(appendedCaseMeta.outputStream()).use { zout ->
                    val entries = zin.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        zout.putNextEntry(ZipEntry(e.name))
                        zin.getInputStream(e).use { it.copyTo(zout) }
                        zout.closeEntry()
                    }
                    zout.putNextEntry(ZipEntry("meta-inf/extra.txt"))
                    zout.write("evil".toByteArray())
                    zout.closeEntry()
                }
            }
            appendedCaseMeta.copyTo(caseMetaPkg, overwrite = true)
            appendedCaseMeta.delete()
            assertThrows(IllegalArgumentException::class.java) { androidVerifierA.verify(caseMetaPkg, "community-x") }
            val (caseCode, caseOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(caseMetaDir, "registry.json").absolutePath)
            assertNotEquals(0, caseCode)
            assertTrue("Expected case-variant META-INF rejection: $caseOut", caseOut.contains("Case-variant META-INF"))

            // 3. Explicit "contributions": null in the same signed package rejected by both Android and Python.
            val nullContribDir = writeReg("nullcontrib", "community-x", fpA, "demo.pljs", baseEntry("demo.pljs"))
            val nullContribPkg = File(nullContribDir, "artifacts/demo.pljs")
            writePljs(nullContribPkg, baseManifest(extraJson = ""","contributions":null"""))
            signPackage(tempDir, nullContribPkg, ksA, envA, "alpha")
            val ncErr = assertThrows(IllegalArgumentException::class.java) { androidVerifierA.verify(nullContribPkg, "community-x") }
            assertTrue("Expected Android contributions null rejection: ${ncErr.message}", ncErr.message.orEmpty().contains("contributions", ignoreCase = true))
            val (ncCode, ncOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(nullContribDir, "registry.json").absolutePath)
            assertNotEquals(0, ncCode)
            assertTrue("Expected contributions array rejection: $ncOut", ncOut.contains("contributions must be a JSON array"))

            // 3b. S4 v1.3 parity: options=null on switch field and unavailableText=123 without requiresCapability rejected by both Android and Python.
            val nullOptsDir = writeReg("nullopts", "community-x", fpA, "demo.pljs", baseEntry("demo.pljs"))
            val nullOptsPkg = File(nullOptsDir, "artifacts/demo.pljs")
            writePljs(
                nullOptsPkg,
                baseManifest(extraJson = ""","contributions":[{"kind":"FormSection","id":"f","slot":"plan.edit.configuration","title":"T","fields":[{"id":"sw","kind":"switch","label":"S","value":true,"options":null,"onEvent":"e"}]}]"""),
            )
            signPackage(tempDir, nullOptsPkg, ksA, envA, "alpha")
            val optErr = assertThrows(IllegalArgumentException::class.java) { androidVerifierA.verify(nullOptsPkg, "community-x") }
            assertTrue("Expected Android options null rejection: ${optErr.message}", optErr.message.orEmpty().contains("options", ignoreCase = true))
            val (optCode, optOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(nullOptsDir, "registry.json").absolutePath)
            assertNotEquals(0, optCode)
            assertTrue("Expected Python options list rejection: $optOut", optOut.contains("Invalid options list"))

            val unavailDir = writeReg("unavailnum", "community-x", fpA, "demo.pljs", baseEntry("demo.pljs"))
            val unavailPkg = File(unavailDir, "artifacts/demo.pljs")
            writePljs(
                unavailPkg,
                baseManifest(extraJson = ""","contributions":[{"kind":"StatusLine","id":"sl","slot":"plan.detail.protection","text":"ok","unavailableText":123}]"""),
            )
            signPackage(tempDir, unavailPkg, ksA, envA, "alpha")
            val uaErr = assertThrows(IllegalArgumentException::class.java) { androidVerifierA.verify(unavailPkg, "community-x") }
            assertTrue("Expected Android unavailableText rejection: ${uaErr.message}", uaErr.message.orEmpty().contains("unavailableText", ignoreCase = true) || uaErr.message.orEmpty().contains("string", ignoreCase = true))
            val (uaCode, uaOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(unavailDir, "registry.json").absolutePath)
            assertNotEquals(0, uaCode)
            assertTrue("Expected Python unavailableText rejection: $uaOut", uaOut.contains("Invalid StatusLine unavailableText"))

            // 4. Invalid FormSection on contractVersion=1 in the same signed package rejected by both Android and Python.
            val badFormDir = writeReg("badform", "community-x", fpA, "demo.pljs", baseEntry("demo.pljs", contractVersion = 2))
            val badFormPkg = File(badFormDir, "artifacts/demo.pljs")
            val badFormManifest = baseManifest(
                contractVersion = 1,
                extraJson = ""","contributions":[{"kind":"FormSection","id":"f","slot":"plan.edit.configuration","title":"T","fields":[{"id":"sw","kind":"switch","label":"S","value":true,"onEvent":"e"}]}]""",
            ).replace("\"type\":\"js\"", "\"type\":\"dex\"").replace("\"entryPoint\":\"entryPoint\"", "\"entryPoint\":\"a.B\"")
            writePljs(badFormPkg, badFormManifest, extraEntries = mapOf("classes.dex" to ("dex\n037\u0000".toByteArray() + ByteArray(32))))
            signPackage(tempDir, badFormPkg, ksA, envA, "alpha")
            val bfErr = assertThrows(IllegalArgumentException::class.java) { androidVerifierA.verify(badFormPkg, "community-x") }
            assertFalse("Expected non-blank Android rejection message", bfErr.message.isNullOrBlank())
            val (bfCode, bfOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(badFormDir, "registry.json").absolutePath)
            assertNotEquals(0, bfCode)
            assertTrue("Expected FormSection rejection: $bfOut", bfOut.contains("FormSection"))

            // 5. UTF-16 code unit boundary on the same signed package: 2049 emojis (4098 UTF-16 units) rejected by both; 2048 emojis (4096 UTF-16 units) accepted by both.
            val tooLongText = "\uD83D\uDE00".repeat(2049)
            val utf16BadDir = writeReg("utf16bad", "community-x", fpA, "demo.pljs", baseEntry("demo.pljs"))
            val utf16BadPkg = File(utf16BadDir, "artifacts/demo.pljs")
            writePljs(
                utf16BadPkg,
                baseManifest(extraJson = ""","contributions":[{"kind":"FormSection","id":"f","slot":"plan.edit.configuration","title":"T","fields":[{"id":"n","kind":"text","label":"N","value":"$tooLongText","onEvent":"e"}]}]"""),
            )
            signPackage(tempDir, utf16BadPkg, ksA, envA, "alpha")
            val u16Err = assertThrows(IllegalArgumentException::class.java) { androidVerifierA.verify(utf16BadPkg, "community-x") }
            assertTrue("Expected Android UTF-16 text limit rejection: ${u16Err.message}", u16Err.message.orEmpty().contains("Invalid form field"))
            val (uBadCode, uBadOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(utf16BadDir, "registry.json").absolutePath)
            assertNotEquals(0, uBadCode)
            assertTrue("Expected UTF-16 text limit rejection: $uBadOut", uBadOut.contains("Invalid text value"))

            val exactLimitText = "\uD83D\uDE00".repeat(2048)
            val utf16OkDir = writeReg("utf16ok", "community-x", fpA, "demo.pljs", baseEntry("demo.pljs"))
            val utf16OkPkg = File(utf16OkDir, "artifacts/demo.pljs")
            writePljs(
                utf16OkPkg,
                baseManifest(extraJson = ""","contributions":[{"kind":"FormSection","id":"f","slot":"plan.edit.configuration","title":"T","fields":[{"id":"n","kind":"text","label":"N","value":"$exactLimitText","onEvent":"e"}]}]"""),
            )
            signPackage(tempDir, utf16OkPkg, ksA, envA, "alpha")
            val verifiedOk = androidVerifierA.verify(utf16OkPkg, "community-x")
            assertEquals("parity-js", verifiedOk.manifest.id)
            val (uOkCode, uOkOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(utf16OkDir, "registry.json").absolutePath)
            assertEquals("Expected UTF-16 4096 boundary acceptance: $uOkOut", 0, uOkCode)

            // 6. Unstripped whitespace in id (" parity-js ") rejected by both FilePluginArtifactStore.safeSegment rule and Python.
            val spaceDir = writeReg("spaceid", "community-x", fpA, "demo.pljs", baseEntry("demo.pljs", id = " parity-js "))
            val spacePkg = File(spaceDir, "artifacts/demo.pljs")
            writePljs(spacePkg, baseManifest(id = " parity-js "))
            signPackage(tempDir, spacePkg, ksA, envA, "alpha")
            val verifiedSpace = androidVerifierA.verify(spacePkg, "community-x")
            assertThrows(IllegalArgumentException::class.java) {
                val segPattern = Regex("[A-Za-z0-9._-]+")
                require(segPattern.matches(verifiedSpace.manifest.id)) { "Unsafe id segment" }
            }
            val (spCode, spOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(spaceDir, "registry.json").absolutePath)
            assertNotEquals(0, spCode)
            assertTrue("Expected unsafe path characters rejection: $spOut", spOut.contains("unsafe path characters"))

            // 7. Case-sensitivity parity: reserved "OFFICIAL" and sourceId case mismatch ("Community-X" vs "community-x").
            assertThrows(IllegalArgumentException::class.java) { PluginSourcePolicy.validateSourceId("OFFICIAL") }
            val resDir = File(tempDir, "reserved").apply { mkdirs() }
            File(resDir, "registry.json").writeText(
                """{"catalogFormatVersion":1,"sourceId":"OFFICIAL","sourceType":"THIRD_PARTY","trustedFingerprints":["$fpA"],"plugins":[]}""",
                Charsets.UTF_8,
            )
            val (resCode, resOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(resDir, "registry.json").absolutePath)
            assertNotEquals(0, resCode)
            assertTrue("Expected reserved sourceId rejection: $resOut", resOut.contains("Reserved sourceId"))

            assertThrows(IllegalArgumentException::class.java) {
                SignedJarPluginPackageVerifier(mapOf("Community-X" to setOf(fpA))).verify(utf16OkPkg, "Community-X")
            }
            val mismatchCaseDir = writeReg("case_src", "Community-X", fpA, "demo.pljs", baseEntry("demo.pljs"))
            utf16OkPkg.copyTo(File(mismatchCaseDir, "artifacts/demo.pljs"), overwrite = true)
            val (mcCode, mcOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(mismatchCaseDir, "registry.json").absolutePath)
            assertNotEquals(0, mcCode)
            assertTrue("Expected sourceId mismatch rejection: $mcOut", mcOut.contains("does not match registry sourceId"))

            // 8. Same community mixed-signer package: plugin.json signed by A+B, index.js signed ONLY by B.
            val partA = File(tempDir, "part_a.pljs")
            ZipOutputStream(partA.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("plugin.json"))
                z.write(baseManifest(sourceId = "community-mixed").toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            signPackage(tempDir, partA, ksA, envA, "alpha", sigFile = "ALPHA")

            val fullB = File(tempDir, "full_b.pljs")
            writePljs(fullB, baseManifest(sourceId = "community-mixed"))
            signPackage(tempDir, fullB, ksB, envB, "beta", sigFile = "BETA")

            val mixedCommunityPkg = File(tempDir, "mixed-community.pljs")
            ZipFile(partA).use { za ->
                ZipFile(fullB).use { zb ->
                    ZipOutputStream(mixedCommunityPkg.outputStream()).use { zout ->
                        val keepFromB = listOf("META-INF/MANIFEST.MF", "META-INF/BETA.SF", "META-INF/BETA.RSA", "plugin.json", "index.js")
                        for (name in keepFromB) {
                            zout.putNextEntry(ZipEntry(name))
                            zb.getInputStream(zb.getEntry(name)).use { it.copyTo(zout) }
                            zout.closeEntry()
                        }
                        for (name in listOf("META-INF/ALPHA.SF", "META-INF/ALPHA.RSA")) {
                            zout.putNextEntry(ZipEntry(name))
                            za.getInputStream(za.getEntry(name)).use { it.copyTo(zout) }
                            zout.closeEntry()
                        }
                    }
                }
            }

            // Anchor = {fpA} -> BOTH Android and Python reject for signer intersection failure!
            val mixErr = assertThrows(IllegalArgumentException::class.java) {
                SignedJarPluginPackageVerifier(mapOf("community-mixed" to setOf(fpA))).verify(mixedCommunityPkg, "community-mixed")
            }
            assertTrue("Expected Android signer intersection rejection: ${mixErr.message}", mixErr.message.orEmpty().contains("not trusted for every payload"))
            val mixRegA = writeReg("mixA", "community-mixed", fpA, "mixed.pljs", baseEntry("mixed.pljs"))
            mixedCommunityPkg.copyTo(File(mixRegA, "artifacts/mixed.pljs"), overwrite = true)
            val (mixACode, mixAOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(mixRegA, "registry.json").absolutePath)
            assertNotEquals(0, mixACode)
            assertTrue("Expected signer intersection rejection: $mixAOut", mixAOut.contains("do not match trustedFingerprints"))

            // Anchor = {fpB} -> BOTH Android and Python accept the exact same mixed package!
            val verifiedMixB = SignedJarPluginPackageVerifier(mapOf("community-mixed" to setOf(fpB))).verify(mixedCommunityPkg, "community-mixed")
            assertEquals(fpB, verifiedMixB.signerFingerprint)
            val mixRegB = writeReg("mixB", "community-mixed", fpB, "mixed.pljs", baseEntry("mixed.pljs"))
            mixedCommunityPkg.copyTo(File(mixRegB, "artifacts/mixed.pljs"), overwrite = true)
            val (mixBCode, mixBOut) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(mixRegB, "registry.json").absolutePath)
            assertEquals("Expected acceptance for full-payload signer B: $mixBOut", 0, mixBCode)

            // 9. Int.MAX_VALUE overflow & Dangerous URL parity
            assertThrows(IllegalArgumentException::class.java) {
                PluginCatalogCodec.decode("""{"catalogFormatVersion":2147483648,"sourceId":"community-x","sourceType":"THIRD_PARTY","plugins":[]}""")
            }
            val overflowDir = File(tempDir, "overflow").apply { mkdirs() }
            File(overflowDir, "registry.json").writeText(
                """{"catalogFormatVersion":2147483648,"sourceId":"community-x","sourceType":"THIRD_PARTY","trustedFingerprints":["$fpA"],"plugins":[]}""",
                Charsets.UTF_8,
            )
            val (ovCode, _) = runRegistryTool(python, toolScript, tempDir, "validate", "--registry", File(overflowDir, "registry.json").absolutePath)
            assertNotEquals(0, ovCode)

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
                  "trustedFingerprints":["$sampleFp"],
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
