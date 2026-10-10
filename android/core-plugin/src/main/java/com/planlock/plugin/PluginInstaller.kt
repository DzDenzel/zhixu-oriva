package com.planlock.plugin

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.jar.JarFile

data class PluginCatalogEntry(
    val manifest: PluginManifest,
    val downloadUrl: String,
    val sha256: String,
)

fun interface PluginDownloader {
    fun download(url: String, target: File): Long
}

fun interface PluginPackageVerifier {
    fun verify(file: File, expectedSourceId: String): VerifiedPluginPackage
}

fun interface PluginArtifactStore {
    fun persist(source: File, manifest: PluginManifest): File
}

data class VerifiedPluginPackage(
    val manifest: PluginManifest,
    val signerFingerprint: String,
    val sha256: String,
)

sealed interface PluginInstallResult {
    data class Installed(
        val manifest: PluginManifest,
        val file: File,
        val bytes: Long,
        val sha256: String,
        val signerFingerprint: String,
    ) : PluginInstallResult

    data class Failed(val message: String) : PluginInstallResult
}

/** Installation has no local/bundled shortcut: every request begins with [PluginDownloader]. */
class PluginInstaller(
    private val downloader: PluginDownloader,
    private val verifier: PluginPackageVerifier,
    private val artifactStore: PluginArtifactStore,
    private val tempFileFactory: () -> File,
) {
    fun install(entry: PluginCatalogEntry): PluginInstallResult {
        val temp = tempFileFactory()
        return try {
            val bytes = downloader.download(entry.downloadUrl, temp)
            require(bytes > 0L) { "Empty plugin response" }
            val verified = verifier.verify(temp, entry.manifest.sourceId)
            require(verified.manifest.id == entry.manifest.id) { "Plugin id mismatch" }
            require(verified.manifest.sourceType == entry.manifest.sourceType) { "Plugin source type mismatch" }
            require(verified.sha256.equals(entry.sha256, ignoreCase = true)) { "Plugin checksum mismatch" }
            val installed = artifactStore.persist(temp, verified.manifest)
            PluginInstallResult.Installed(
                verified.manifest,
                installed,
                bytes,
                verified.sha256,
                verified.signerFingerprint,
            )
        } catch (failure: Throwable) {
            PluginInstallResult.Failed(failure.message ?: failure::class.java.simpleName)
        } finally {
            temp.delete()
        }
    }
}

class HttpPluginDownloader : PluginDownloader {
    override fun download(url: String, target: File): Long {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        try {
            require(connection.responseCode in 200..299) { "Download failed: HTTP ${connection.responseCode}" }
            FileOutputStream(target).use { output -> connection.inputStream.use { it.copyTo(output) } }
            return target.length()
        } finally {
            connection.disconnect()
        }
    }
}

/** Verifies signed JAR payload entries and matches the signer against the selected source anchor set. */
class SignedJarPluginPackageVerifier(
    private val trustedFingerprintsBySource: Map<String, Set<String>>,
) : PluginPackageVerifier {
    override fun verify(file: File, expectedSourceId: String): VerifiedPluginPackage {
        val trusted = trustedFingerprintsBySource[expectedSourceId]
            ?.map { normalizeFingerprint(it) }
            ?.toSet()
            .orEmpty()
        require(trusted.isNotEmpty()) { "No trust anchors registered for source $expectedSourceId" }

        val payloadSignerSets = mutableListOf<Set<String>>()
        val manifestRaw: String
        JarFile(file, true).use { jar ->
            val payloadEntries = jar.entries().asSequence()
                .filterNot { it.isDirectory || it.name.startsWith("META-INF/") }
                .toList()
            require(payloadEntries.any { it.name == PLUGIN_MANIFEST_ENTRY }) { "Plugin package has no manifest" }
            require(payloadEntries.map { it.name }.distinct().size == payloadEntries.size) { "Duplicate plugin payload" }
            payloadEntries.forEach { entry ->
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                require(bytes.isNotEmpty()) { "Empty signed payload entry ${entry.name}" }
                // Only the leaf certificate signs this entry; a certificate chain is not a signer list.
                val signers = entry.codeSigners.orEmpty().map { signer ->
                    certificateFingerprint(signer.signerCertPath.certificates.first())
                }.toSet()
                require(signers.isNotEmpty()) { "Unsigned plugin payload entry ${entry.name}" }
                payloadSignerSets += signers
            }
            manifestRaw = jar.getInputStream(jar.getJarEntry(PLUGIN_MANIFEST_ENTRY)).bufferedReader().use { it.readText() }
            val decoded = PluginManifestCodec.decode(manifestRaw)
            val codeEntry = if (decoded.type == PluginType.JS) "index.js" else "classes.dex"
            require(payloadEntries.any { it.name == codeEntry }) { "Plugin package has no $codeEntry" }
        }
        val manifest = PluginManifestCodec.decode(manifestRaw)
        require(manifest.sourceId == expectedSourceId) { "Plugin source mismatch" }
        // A trusted signer must cover every payload, including the code, rather than just the manifest.
        val commonTrustedSigners = payloadSignerSets.fold(trusted) { common, signers -> common intersect signers }
        val trustedSigner = requireNotNull(commonTrustedSigners.firstOrNull()) {
            "Plugin signature is not trusted for every payload for source $expectedSourceId"
        }
        return VerifiedPluginPackage(manifest, trustedSigner, sha256(file))
    }

    companion object {
        const val PLUGIN_MANIFEST_ENTRY = "plugin.json"

        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02X".format(it) }

        fun certificateFingerprint(certificate: Certificate): String = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

        private fun normalizeFingerprint(value: String): String = value.replace(" ", "").uppercase()
    }
}
