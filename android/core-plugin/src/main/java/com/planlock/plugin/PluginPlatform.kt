package com.planlock.plugin

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PluginTrustAnchors {
    val fingerprintsBySource: Map<String, Set<String>> = mapOf(
        "official" to setOf("C5:9E:81:65:8F:31:08:29:B4:23:BE:24:2D:C3:28:B3:76:CB:CE:1B:C6:1C:1F:F2:31:31:61:12:41:2B:8C:44"),
        "third-party" to setOf("71:DC:CF:31:C4:2D:EE:68:04:AA:18:20:0C:5B:E1:E3:7B:07:7C:59:9E:54:92:5F:DE:C9:21:F1:6E:4B:EF:67"),
    )
}

/** Process-wide façade shared by settings UI and the single host accessibility service. */
class PluginPlatform private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val stateStore = PluginStateStore(appContext)
    private val sourceStore = PluginSourceStore(appContext)
    private val currentSnapshot = MutableStateFlow(stateStore.snapshot())
    val snapshots = currentSnapshot.asStateFlow()
    private val currentCustomSources = MutableStateFlow(sourceStore.list())
    val customSources = currentCustomSources.asStateFlow()
    private val activeThirdPartySourceId = MutableStateFlow<String?>(null)
    val selectedThirdPartySourceId = activeThirdPartySourceId.asStateFlow()
    private val verifier = SignedJarPluginPackageVerifier {
        PluginSourcePolicy.mergeTrustAnchors(PluginTrustAnchors.fingerprintsBySource, sourceStore.list())
    }
    private val loader = ExternalPluginLoader(appContext, verifier, stateStore)
    private val reloadRequests = Channel<Unit>(Channel.CONFLATED)
    private val revision = java.util.concurrent.atomic.AtomicLong()
    private val completedLoad = MutableStateFlow(0L)
    internal val completedLoads = completedLoad.asStateFlow()
    private val reloadLock = Any()
    private val installer = PluginInstaller(
        downloader = HttpPluginDownloader(),
        verifier = verifier,
        artifactStore = FilePluginArtifactStore(appContext),
        tempFileFactory = { File.createTempFile("download-", ".plp", appContext.cacheDir) },
    )

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (request in reloadRequests) {
                val generation = revision.get()
                val errors = mutableListOf<Pair<InstalledPluginRecord, String?>>()
                val plugins = loader.loadEffective { record, error -> errors += record to error }
                synchronized(reloadLock) {
                    if (generation == revision.get()) {
                        errors.forEach { (record, error) -> stateStore.setError(record.manifest.id, error) }
                        PluginRuntimeRegistry.replace(plugins)
                        currentSnapshot.value = stateStore.snapshot()
                        completedLoad.value = generation
                    }
                    else plugins.forEach { (it.instance as? java.io.Closeable)?.close() }
                }
            }
        }
        reload()
    }

    fun snapshot(): PluginPlatformSnapshot = stateStore.snapshot()

    fun customSources(): List<CustomPluginSource> = sourceStore.list()

    fun addCustomSource(source: CustomPluginSource): List<CustomPluginSource> = synchronized(reloadLock) {
        sourceStore.add(source).also { updated ->
            currentCustomSources.value = updated
            reload()
        }
    }

    fun removeCustomSource(id: String): List<CustomPluginSource> = synchronized(reloadLock) {
        sourceStore.remove(id).also { updated ->
            if (activeThirdPartySourceId.value == id) activeThirdPartySourceId.value = null
            currentCustomSources.value = updated
            reload()
        }
    }

    fun selectThirdPartySource(id: String?) {
        activeThirdPartySourceId.value = id
    }

    fun setGlobalEnabled(enabled: Boolean) = synchronized(reloadLock) {
        stateStore.setGlobalEnabled(enabled)
        reload()
    }

    fun setSafeMode(enabled: Boolean) = synchronized(reloadLock) {
        stateStore.setSafeMode(enabled)
        reload()
    }

    fun install(entry: PluginCatalogEntry): PluginInstallResult {
        val result = installer.install(entry, safeMode = stateStore.snapshot().safeMode)
        if (result is PluginInstallResult.Installed) {
            synchronized(reloadLock) {
            stateStore.save(
                InstalledPluginRecord(
                    manifest = result.manifest,
                    path = result.file.absolutePath,
                    sha256 = result.sha256,
                    signerFingerprint = result.signerFingerprint,
                )
            )
            reload()
            }
        }
        return result
    }

    fun setPluginEnabled(id: String, enabled: Boolean): Boolean = synchronized(reloadLock) {
        stateStore.setEnabled(id, enabled).also { reload() }
    }

    fun uninstall(id: String): Boolean = synchronized(reloadLock) {
        stateStore.uninstall(id).also { reload() }
    }

    fun reload() {
        // Revoke stale callbacks immediately; validation and native initialization never run on UI.
        synchronized(reloadLock) {
            revision.incrementAndGet()
            PluginRuntimeRegistry.replace(emptyList())
            currentSnapshot.value = stateStore.snapshot()
            reloadRequests.trySend(Unit)
        }
    }

    companion object {
        @Volatile private var instance: PluginPlatform? = null

        fun get(context: Context): PluginPlatform = instance ?: synchronized(this) {
            instance ?: PluginPlatform(context).also { instance = it }
        }
    }
}

@Serializable
data class PluginCatalogDocument(
    val sourceId: String,
    val sourceType: PluginSourceType,
    val plugins: List<PluginCatalogItem>,
    val catalogFormatVersion: Int = 1,
)

@Serializable
data class PluginCatalogItem(
    val id: String,
    val name: String,
    val version: String,
    val contractVersion: Int,
    val entryPoint: String,
    val requiredCapabilities: Set<PluginCapability> = emptySet(),
    val description: String = "",
    val downloadUrl: String,
    val sha256: String,
    val type: PluginType = PluginType.DEX,
    val author: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val license: String? = null,
) {
    fun toEntry(document: PluginCatalogDocument) = PluginCatalogEntry(
        manifest = PluginManifest(
            id = id,
            name = name,
            version = version,
            contractVersion = contractVersion,
            entryPoint = entryPoint,
            sourceId = document.sourceId,
            sourceType = document.sourceType,
            requiredCapabilities = requiredCapabilities,
            description = description,
            type = type,
            author = author,
            homepage = homepage,
            repository = repository,
            license = license,
        ),
        downloadUrl = downloadUrl,
        sha256 = sha256,
    )
}

object PluginCatalogCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun decode(raw: String): PluginCatalogDocument = json.decodeFromString<PluginCatalogDocument>(raw).let { document ->
        require(document.catalogFormatVersion >= 1) {
            "Unsupported catalogFormatVersion: ${document.catalogFormatVersion}"
        }
        require(document.sourceId.isNotBlank()) { "Catalog sourceId must not be blank" }
        val normalizedPlugins = document.plugins.map { item ->
            require(item.id.isNotBlank() && item.name.isNotBlank() && item.version.isNotBlank() && item.entryPoint.isNotBlank()) {
                "Catalog item missing required text field"
            }
            require(item.contractVersion >= 1) { "Invalid catalog contractVersion: ${item.contractVersion}" }
            require(item.downloadUrl.isNotBlank()) { "Catalog item downloadUrl must not be blank" }
            if (item.downloadUrl.contains(":")) {
                val lower = item.downloadUrl.trim().lowercase(java.util.Locale.ROOT)
                require(lower.startsWith("http://") || lower.startsWith("https://")) {
                    "Unsafe catalog downloadUrl scheme"
                }
            }
            item.copy(
                author = PluginMetadataValidator.sanitizeOptionalText(item.author),
                homepage = PluginMetadataValidator.requireOptionalHttpUrl(item.homepage, "homepage"),
                repository = PluginMetadataValidator.requireOptionalHttpUrl(item.repository, "repository"),
                license = PluginMetadataValidator.sanitizeOptionalText(item.license),
            )
        }
        document.copy(plugins = normalizedPlugins)
    }
}

/** Installed by the application; transport is public and must never attach business credentials. */
object PluginPublicTransport {
    @Volatile var get: ((String) -> ByteArray)? = null
}

class PluginCatalogClient {
    fun fetch(url: String, lanAllowed: Boolean = false): PluginCatalogDocument {
        PluginPublicTransport.get?.let { get ->
            val document = PluginCatalogCodec.decode(get(url).toString(Charsets.UTF_8))
            return document.copy(plugins = document.plugins.map {
                val resolved = URL(URL(url), it.downloadUrl).toString()
                it.copy(downloadUrl = PluginSourcePolicy.validateResolvedDownloadUrl(url, resolved, lanAllowed))
            })
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            require(connection.responseCode in 200..299) { "Catalog request failed: ${connection.responseCode}" }
            val document = PluginCatalogCodec.decode(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            document.copy(plugins = document.plugins.map { item ->
                val resolved = URL(URL(url), item.downloadUrl).toString()
                item.copy(downloadUrl = PluginSourcePolicy.validateResolvedDownloadUrl(url, resolved, lanAllowed))
            })
        } finally {
            connection.disconnect()
        }
    }
}
