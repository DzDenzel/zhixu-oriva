package com.planlock.plugin

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Serializable
data class InstalledPluginRecord(
    val manifest: PluginManifest,
    val path: String,
    val sha256: String,
    val signerFingerprint: String,
    val enabled: Boolean = false,
    val lastError: String? = null,
)

data class PluginPlatformSnapshot(
    val globalEnabled: Boolean,
    val safeMode: Boolean,
    val installed: List<InstalledPluginRecord>,
)

internal object ReadOnlyPluginArtifact {
    fun seal(file: File) {
        require(file.isFile) { "Plugin artifact is missing" }
        require(file.setReadOnly() || !file.canWrite()) { "Cannot make plugin artifact read-only" }
    }
}

class PluginStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun snapshot(): PluginPlatformSnapshot = PluginPlatformSnapshot(
        globalEnabled = preferences.getBoolean(GLOBAL_ENABLED, false),
        safeMode = preferences.getBoolean(SAFE_MODE, true),
        installed = preferences.getStringSet(INSTALLED_IDS, emptySet()).orEmpty().mapNotNull(::record),
    )

    fun setGlobalEnabled(value: Boolean) = preferences.edit().putBoolean(GLOBAL_ENABLED, value).apply()
    fun setSafeMode(value: Boolean) = preferences.edit().putBoolean(SAFE_MODE, value).apply()

    @Synchronized fun save(record: InstalledPluginRecord) {
        val ids = preferences.getStringSet(INSTALLED_IDS, emptySet()).orEmpty().toMutableSet().apply {
            add(record.manifest.id)
        }
        preferences.edit()
            .putStringSet(INSTALLED_IDS, ids)
            .putString(recordKey(record.manifest.id), json.encodeToString(record))
            .apply()
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean): Boolean {
        val current = record(id) ?: return false
        save(current.copy(enabled = enabled, lastError = null))
        return true
    }

    @Synchronized fun setError(id: String, message: String?) {
        record(id)?.let { save(it.copy(lastError = message)) }
    }

    @Synchronized fun uninstall(id: String): Boolean {
        val current = record(id) ?: return false
        File(current.path).delete()
        val ids = preferences.getStringSet(INSTALLED_IDS, emptySet()).orEmpty().toMutableSet().apply { remove(id) }
        preferences.edit().putStringSet(INSTALLED_IDS, ids).remove(recordKey(id)).apply()
        return true
    }

    fun record(id: String): InstalledPluginRecord? = preferences.getString(recordKey(id), null)?.let { raw ->
        runCatching { json.decodeFromString<InstalledPluginRecord>(raw) }.getOrNull()
    }

    private fun recordKey(id: String) = "plugin.$id"

    companion object {
        private const val PREFERENCES = "planlock_plugins"
        private const val GLOBAL_ENABLED = "plugins_enabled"
        private const val SAFE_MODE = "safe_mode"
        private const val INSTALLED_IDS = "installed_ids"
    }
}

class FilePluginArtifactStore(context: Context) : PluginArtifactStore {
    private val root = File(context.applicationContext.filesDir, "plugins")

    override fun persist(source: File, manifest: PluginManifest): File {
        val directory = File(root, "${safeSegment(manifest.id)}/${safeSegment(manifest.version)}")
        require(directory.mkdirs() || directory.isDirectory) { "Cannot create plugin directory" }
        val target = File(directory, "plugin.plp")
        source.copyTo(target, overwrite = true)
        ReadOnlyPluginArtifact.seal(target)
        return target
    }

    private fun safeSegment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe plugin path segment" }
        return value
    }
}

class ExternalPluginLoader(
    private val context: Context,
    private val verifier: PluginPackageVerifier,
    private val stateStore: PluginStateStore,
) {
    fun loadEffective(reportError: (InstalledPluginRecord, String?) -> Unit = { record, error ->
        stateStore.setError(record.manifest.id, error)
    }): List<LoadedPlugin> {
        val state = stateStore.snapshot()
        return state.installed.mapNotNull { record ->
            if (!PluginLifecycle.isEffective(
                    state.globalEnabled,
                    installed = true,
                    enabled = record.enabled,
                    sourceType = record.manifest.sourceType,
                    safeMode = state.safeMode,
                )
            ) return@mapNotNull null
            runCatching {
                val artifact = File(record.path)
                // Android 14+ rejects dynamically loaded code that is still writable.
                // Seal again here so plugins installed by older app versions migrate safely.
                ReadOnlyPluginArtifact.seal(artifact)
                val verified = verifier.verify(artifact, record.manifest.sourceId)
                require(verified.sha256.equals(record.sha256, ignoreCase = true)) { "Installed plugin changed" }
                if (record.manifest.type == PluginType.JS) {
                    val source = java.util.zip.ZipFile(artifact).use { zip ->
                        val entry = zip.getEntry("index.js") ?: error("Missing JS source")
                        require(entry.size in 1..(1024 * 1024)) { "JS source is too large" }
                        zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                    val instance = JsPluginRuntime(record.manifest, source, PluginHostQueries)
                    instance.initialize()
                    return@runCatching LoadedPlugin(record.manifest, instance)
                }
                val optimized = File(context.codeCacheDir, "plugin-dex/${record.manifest.id}").apply { mkdirs() }
                val loader = DexClassLoader(artifact.absolutePath, optimized.absolutePath, null, context.classLoader)
                require(record.manifest.id.matches(Regex("[A-Za-z0-9._-]+")) && record.manifest.id !in setOf(".", "..")) { "Unsafe plugin state id" }
                val directory = File(context.filesDir, "plugin-state/${record.manifest.id}")
                require(directory.mkdirs() || directory.isDirectory) { "Plugin state directory unavailable" }
                val instance = instantiatePluginEntry(loader.loadClass(record.manifest.entryPoint), directory)
                LoadedPlugin(record.manifest, instance)
            }.onSuccess {
                reportError(record, null)
            }.onFailure {
                reportError(record, it.message ?: it::class.java.simpleName)
            }.getOrNull()
        }
    }
}

object PluginRuntimeRegistry {
    private val loaded = MutableStateFlow<List<LoadedPlugin>>(emptyList())
    val plugins: StateFlow<List<LoadedPlugin>> = loaded.asStateFlow()
    private val dispatcher = PluginDispatcher()
    private val actionExecutors = java.util.concurrent.ConcurrentHashMap<String, PluginActionExecutor>()
    private val timedResponses = TimedResponseRegistry()
    private val jsDecisionExecutors = java.util.concurrent.ConcurrentHashMap<String, JsDecisionExecutor>()
    @Volatile private var responseScheduler: PluginResponseScheduler? = null

    fun setResponseScheduler(scheduler: PluginResponseScheduler?) {
        responseScheduler = scheduler
        if (scheduler == null) timedResponses.retain(emptySet())
    }

    fun replace(plugins: List<LoadedPlugin>) {
        loaded.value.filter { old -> plugins.none { it.instance === old.instance } }.forEach {
            (it.instance as? java.io.Closeable)?.close()
        }
        loaded.value = plugins.toList()
        val activeIds = plugins.mapTo(mutableSetOf()) { it.manifest.id }
        jsDecisionExecutors.entries.forEach { (id, executor) ->
            if (id !in activeIds && jsDecisionExecutors.remove(id, executor)) executor.close()
        }
        timedResponses.retain(activeIds)
        actionExecutors.entries.forEach { (id, executor) ->
            // Quarantine lasts until process restart: reloading must not spawn more stuck workers.
            if (id !in activeIds && !executor.isQuarantined && actionExecutors.remove(id, executor)) executor.close()
        }
    }

    fun dispatch(decision: PluginDecision, capabilities: PluginCapabilities): List<PluginDispatchResult> =
        loaded.value.map { plugin ->
            if (plugin.manifest.type == PluginType.DEX) dispatchOne(plugin, decision, capabilities)
            else {
                val active = { loaded.value.any { it.instance === plugin.instance } }
                val safeCapabilities = JsMainThreadCapabilities(capabilities, active)
                val accepted = jsDecisionExecutors.computeIfAbsent(plugin.manifest.id) { JsDecisionExecutor() }.submit {
                    if (active()) dispatchOne(plugin, decision, safeCapabilities)
                }
                PluginDispatchResult(plugin.manifest.id, if (accepted) PluginDispatchStatus.QUEUED else PluginDispatchStatus.FAILED_OPEN)
            }
        }

    private fun dispatchOne(plugin: LoadedPlugin, decision: PluginDecision, capabilities: PluginCapabilities): PluginDispatchResult {
        val result = dispatcher.dispatch(listOf(plugin), decision, capabilities).single()
        timedResponses.cancel(plugin.manifest.id)
        if (result.status == PluginDispatchStatus.EXECUTED) runCatching {
            val delay = (plugin.instance as? TimedPluginResponse)?.responseDelayMillis(decision)
            val scheduler = responseScheduler
            if (delay != null && delay > 0 && scheduler != null) timedResponses.schedule(plugin.manifest.id, delay, decision, scheduler) { fresh ->
                loaded.value.firstOrNull { it.manifest.id == plugin.manifest.id }?.let { dispatchOne(it, fresh, capabilities) }
            }
        }
        return result
    }

    fun activePluginIds(): Set<String> = loaded.value.mapTo(linkedSetOf()) { it.manifest.id }

    suspend fun dispatchAction(pluginId: String, event: PluginActionEvent): PluginActionResult =
        if (loaded.value.none { it.manifest.id == pluginId }) PluginActionResult(PluginActionStatus.UNAVAILABLE)
        else actionExecutors.computeIfAbsent(pluginId) { PluginActionExecutor() }.execute {
            dispatchContributionAction(loaded.value, pluginId, event)
        }
}
