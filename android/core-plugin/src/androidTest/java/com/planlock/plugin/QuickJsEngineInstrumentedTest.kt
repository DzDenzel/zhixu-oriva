package com.planlock.plugin

import org.junit.Assert.*
import org.junit.Test

/** Runs the actual packaged JNI engine; no JS mock can satisfy resource-limit checks. */
class QuickJsEngineInstrumentedTest {
    private fun execute(source: String, limits: JsRuntimeLimits = JsRuntimeLimits()): String =
        QuickJsEngine.execute(source, "main", "{\"kind\":\"load\"}",
            JsPluginBridge(emptySet(), EmptyPluginCapabilities, EmptyPluginQueryProvider), limits)

    @Test fun es2020AndUnicodeRunOnRealQuickJs() {
        assertEquals("{\"text\":\"知序😀\",\"number\":42}", execute("function main(event,host) { return {text:'知序😀',number:({a:42})?.a ?? 0}; }"))
    }

    @Test fun languageContextHasNoNetworkFilesDomOrJavaReference() {
        assertEquals("[\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"undefined\"]",
            execute("function main() { return [typeof fetch, typeof XMLHttpRequest, typeof require, typeof std, typeof os, typeof document, typeof Java]; }"))
    }

    @Test fun realDeadLoopStopsAtDefaultTwoSecondDeadlineAndNextCallWorks() {
        val started = System.nanoTime()
        val error = runCatching { execute("function main() { while(true) {} }") }.exceptionOrNull()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("timeout", ignoreCase = true))
        assertTrue("Deadline ended too early: $elapsed", elapsed >= 1_700)
        assertTrue("Dead loop escaped deadline: $elapsed", elapsed < 5_000)
        assertEquals("42", execute("function main(){return 42;}"))
    }

    @Test fun realAllocationOverMemoryCapFailsOpen() {
        // A disabled cap would return a tiny number immediately, not time out while serializing an array.
        val error = runCatching { execute("function main(){ const allocation = new ArrayBuffer(32 * 1024 * 1024); return allocation.byteLength; }") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("memory", ignoreCase = true))
        assertEquals("42", execute("function main(){return 42;}"))
    }

    @Test fun realRecursionHitsStackCapBeforeDeadline() {
        val started = System.nanoTime()
        val error = runCatching { execute("function recurse(){ return recurse() + 1; } function main(){ return recurse(); }") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("stack", ignoreCase = true))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_700)
    }

    @Test fun undeclaredNativeBridgeRequestIsDenied() {
        assertTrue(execute("function main(e,host){return JSON.parse(host.postMessage(JSON.stringify({op:'query.today',args:{date:'2026-10-08'}})));}")
            .contains("CAPABILITY_NOT_DECLARED"))
    }

    @Test fun promisesAreRejectedAsUnsupportedSynchronousResults() {
        assertNotNull(runCatching { execute("async function main(){return 42;}") }.exceptionOrNull())
    }

    @Test fun unicodeErrorsSurviveJniAndDeadLoopInErrorFormattingIsInterrupted() {
        val error = runCatching { execute("function main(){throw Error('知序😀');}") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("知序😀"))
        val deadline = runCatching { execute("function main(){throw {toString(){while(true){}}};}", JsRuntimeLimits(timeoutMillis = 100)) }.exceptionOrNull()
        assertTrue(deadline?.message.orEmpty().contains("timeout", ignoreCase = true))
    }

    @Test fun sourceInitializationUsesSameNativeDeadline() {
        val error = runCatching { execute("while(true) {} function main(){return 42;}", JsRuntimeLimits(timeoutMillis = 100)) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("timeout", ignoreCase = true))
    }
}
