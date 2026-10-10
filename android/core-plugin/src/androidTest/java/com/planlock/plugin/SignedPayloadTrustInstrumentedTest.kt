package com.planlock.plugin

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SignedPayloadTrustInstrumentedTest {
    @Test fun everyPayloadMustShareATrustedSignerOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.planlock.core.plugin.test")
        val verifier = SignedJarPluginPackageVerifier(mapOf("official" to setOf(
            "C5:9E:81:65:8F:31:08:29:B4:23:BE:24:2D:C3:28:B3:76:CB:CE:1B:C6:1C:1F:F2:31:31:61:12:41:2B:8C:44")))
        fun fixture(name: String, check: (File) -> Unit) {
            val file = File.createTempFile("signed-payload-", ".pljs", context.cacheDir)
            try {
                instrumentation.context.assets.open("signing/$name.pljs").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                check(file)
            } finally { file.delete() }
        }
        fixture("official") { assertEquals(PluginType.JS, verifier.verify(it, "official").manifest.type) }
        fixture("mixed") { file -> assertThrows(IllegalArgumentException::class.java) { verifier.verify(file, "official") } }
    }
}
