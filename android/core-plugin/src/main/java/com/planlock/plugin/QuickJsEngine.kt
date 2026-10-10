package com.planlock.plugin

data class JsRuntimeLimits(
    val memoryBytes: Long = 8L * 1024 * 1024,
    val stackBytes: Long = 256L * 1024,
    val timeoutMillis: Long = 2_000L,
) {
    init {
        require(memoryBytes in (512L * 1024)..(32L * 1024 * 1024))
        require(stackBytes in (64L * 1024)..(512L * 1024))
        require(timeoutMillis in 1L..2_000L)
    }
}

/** Loaded only on an actual JS invocation. Every native call owns and frees its runtime/context. */
internal object QuickJsEngine {
    init { System.loadLibrary("oriva_quickjs") }

    fun execute(source: String, entryPoint: String, event: String, bridge: JsPluginBridge, limits: JsRuntimeLimits): String {
        require(source.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "JS source exceeds 1 MiB" }
        require(entryPoint.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) { "Invalid JS entry point" }
        require(event.length <= JsPluginBridge.MAX_JSON_CHARS) { "JS event is too large" }
        return evaluateNative(source.toByteArray(Charsets.UTF_8), entryPoint.toByteArray(Charsets.UTF_8),
            event.toByteArray(Charsets.UTF_8), bridge, limits.memoryBytes, limits.stackBytes, limits.timeoutMillis)
            .toString(Charsets.UTF_8)
    }

    private external fun evaluateNative(source: ByteArray, entryPoint: ByteArray, event: ByteArray,
        bridge: JsPluginBridge, memoryBytes: Long, stackBytes: Long, timeoutMillis: Long): ByteArray
}
