package com.planlock.app

import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.PlaceSelection
import com.planlock.feature.plan.BuildConfig
import com.planlock.feature.plan.TiandituPlaceSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Read-only public-coordinate probe. No device position, backend credential or map Key is retained. */
class M2PlaceProviderInstrumentedTest {
    @Test fun nearbyAndEnglishBrandUseRealProviderData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.qapack3a")
        check(BuildConfig.TIANDITU_KEY.isNotBlank()) { "Local map configuration is required" }
        val center = PlaceSelection("公开测试点", 31.2304, 121.4737)
        val source = TiandituPlaceSource(BuildConfig.TIANDITU_KEY)
        val nearby = try { source.nearby(center.latitude, center.longitude, center) }
            catch (error: Exception) { throw AssertionError("Nearby provider failed: ${error.javaClass.simpleName}") }
        assertTrue("Expected center and real surrounding POIs", nearby.size > 1)
        assertTrue(nearby.drop(1).all { distance(center, it.latitude, it.longitude) <= 1_001 })
        assertEquals(nearby.drop(1).map { it.distanceM }.sortedBy { it }, nearby.drop(1).map { it.distanceM })
        val brands = try { source.suggest("kfc", center) }
            catch (error: Exception) { throw AssertionError("Brand provider failed: ${error.javaClass.simpleName}") }
        assertTrue("Verified English brand alias should find multiple stores", brands.size > 1)
        assertTrue(brands.all { it.name.contains("肯德基", true) || it.name.contains("kfc", true) })
        context.filesDir.resolve("m2-provider-proof.json").writeText(buildJsonObject {
            put("publicTestLatitude", center.latitude); put("publicTestLongitude", center.longitude)
            put("nearbyCount", nearby.size); put("kfcCount", brands.size)
            putJsonArray("nearby") { nearby.forEach { hit -> addJsonObject { put("name", hit.name); put("distanceM", hit.distanceM) } } }
            putJsonArray("brands") { brands.take(8).forEach { hit -> addJsonObject { put("name", hit.name); put("distanceM", hit.distanceM) } } }
        }.toString(), Charsets.UTF_8)
    }

    private fun distance(center: PlaceSelection, lat: Double, lon: Double): Int {
        val dLat = Math.toRadians(lat - center.latitude)
        val dLon = Math.toRadians(lon - center.longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(center.latitude)) * cos(Math.toRadians(lat)) * sin(dLon / 2).pow(2)
        return (6_371_000 * 2 * asin(sqrt(a))).roundToInt()
    }
}
