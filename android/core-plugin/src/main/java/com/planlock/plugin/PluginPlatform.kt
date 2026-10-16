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
    private val currentSnapshot = MutableStateFlow(stateStore.snapshot())
    val snapshots = currentSnapshot.asStateFlow()
    private val verifier = SignedJarPluginPackageVerifier(PluginTrustAnchors.fingerprintsBySource)
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

    fun setGlobalEnabled(enabled: Boolean) = synchronized(reloadLock) {
        stateStore.setGlobalEnabled(enabled)
        reload()
    }

    fun setSafeMode(enabled: Boolean) = synchronized(reloadLock) {
        stateStore.setSafeMode(enabled)
        reload()
    }

    fun install(entry: PluginCatalogEntry): PluginInstallResult {
        val result = installer.install(entry)
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
        ),
        downloadUrl = downloadUrl,
        sha256 = sha256,
    )
}

object PluginCatalogCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun decode(raw: String): PluginCatalogDocument = json.decodeFromString(raw)
}

/** Installed by the application; transport is public and must never attach business credentials. */
object PluginPublicTransport {
    @Volatile var get: ((String) -> ByteArray)? = null
}

class PluginCatalogClient {
    fun fetch(url: String): PluginCatalogDocument {
        PluginPublicTransport.get?.let { get ->
            val document = PluginCatalogCodec.decode(get(url).toString(Charsets.UTF_8))
            return document.copy(plugins = document.plugins.map { it.copy(downloadUrl = URL(URL(url), it.downloadUrl).toString()) })
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
                item.copy(downloadUrl = URL(URL(url), item.downloadUrl).toString())
            })
        } finally {
            connection.disconnect()
        }
    }
}
