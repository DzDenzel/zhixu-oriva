package com.planlock.feature.plan

import com.planlock.app.api.PlaceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.IOException
import org.junit.Assert.assertThrows

class PlaceSourceTest {
    private val me = PlaceSelection("我", 31.2304, 121.4737)

    @Test fun duplicatePoiMergesFullWidthAndSmallCoordinateDifferencesButKeepsOtherStoresAndExits() {
        val near = PlaceHit("大世界站4号口", "A", 31.2313, 121.4737)
        val duplicate = PlaceHit("大世界站４号口", "B", 31.23132, 121.4737)
        val otherExit = PlaceHit("大世界站3号口", "A", 31.2314, 121.4737)
        val otherStore = PlaceHit("大世界站4号口", "C", 31.2344, 121.4737)
        val hits = distinctPlaces(sortByDistance(listOf(duplicate, otherExit, otherStore, near), me))
        assertEquals(listOf(near, otherExit, otherStore), hits.map { it.copy(distanceM = null) })
        assertEquals(listOf(100, 111, 445), hits.map { it.distanceM })
    }

    @Test fun nearbyKeepsCenterAndPartialSuccessFiltersRadiusAndDeduplicates() = runBlocking {
        val center = PlaceHit("中心", "地址", me.latitude, me.longitude)
        val close = PlaceHit("近店", null, 31.2314, 121.4737)
        val farther = PlaceHit("楼", null, 31.2354, 121.4737)
        val outside = PlaceHit("城外", null, 31.2504, 121.4737)
        val asked = mutableListOf<String>()
        val hits = loadNearbyPlaces(me, null, reverse = { center }, search = { keyword ->
            asked += keyword
            when (keyword) {
                "楼" -> listOf(outside, farther, close, center)
                "店" -> listOf(close)
                else -> throw IOException("single provider request failed")
            }
        })
        assertEquals(listOf("楼", "店", "站"), asked)
        assertEquals(listOf("中心", "近店", "楼"), hits.map { it.name })
        assertEquals(0, hits.first().distanceM)
        assertTrue(hits.drop(1).all { it.distanceM!! in 1..1_000 })
    }

    @Test fun nearbyReverseFailureDoesNotDiscardPoiResults() = runBlocking {
        val hit = PlaceHit("站", null, 31.2314, 121.4737)
        val hits = loadNearbyPlaces(me, null, reverse = { throw IOException() }, search = { listOf(hit) })
        assertEquals(listOf("站"), hits.map { it.name })
    }

    @Test fun nearbyDeduplicatesAcrossReverseAndPoiButKeepsDistantSameName() = runBlocking {
        val center = PlaceHit("中心楼", "地址", me.latitude, me.longitude)
        val duplicate = PlaceHit("中心楼", "地址", me.latitude + 0.00005, me.longitude)
        val distant = PlaceHit("中心楼", "另一地址", me.latitude + 0.001, me.longitude)
        val hits = loadNearbyPlaces(me, null, reverse = { center }, search = { listOf(duplicate, distant) })
        assertEquals(listOf(center, distant), hits.map { it.copy(distanceM = null) })
        assertEquals(0, hits.first().distanceM)
    }

    @Test fun nearbySearchFailureDoesNotDiscardReverseResult() = runBlocking {
        val hit = PlaceHit("中心", null, me.latitude, me.longitude)
        assertEquals(listOf("中心"), loadNearbyPlaces(me, null, reverse = { hit }, search = { throw IOException() }).map { it.name })
    }

    @Test fun nearbyAllFailuresRemainFailuresAndCancellationStopsRequests() {
        assertThrows(IOException::class.java) {
            runBlocking { loadNearbyPlaces(me, null, reverse = { throw IOException() }, search = { throw IOException() }) }
        }
        var searched = false
        assertThrows(CancellationException::class.java) {
            runBlocking { loadNearbyPlaces(me, null, reverse = { throw CancellationException() }, search = { searched = true; emptyList() }) }
        }
        assertTrue(!searched)
    }

    @Test fun nearestFirstWithDistances() {
        val far = PlaceHit("虹桥站", null, 31.1946, 121.3203)
        val near = PlaceHit("人民广场", null, 31.2317, 121.4752)
        val sorted = sortByDistance(listOf(far, near), me)
        assertEquals(listOf("人民广场", "虹桥站"), sorted.map { it.name })
        assertTrue(sorted[0].distanceM!! < 300)
        assertTrue(sorted[1].distanceM!! in 14_000..16_000)
    }

    @Test fun withoutMyLocationKeepsProviderOrder() {
        val hits = listOf(PlaceHit("B", null, 31.0, 121.0), PlaceHit("A", null, 31.2, 121.4))
        assertEquals(hits, sortByDistance(hits, null))
    }

    @Test fun distanceLabels() {
        assertEquals("10 米内", distanceLabel(4))
        assertEquals("80 米", distanceLabel(85))
        assertEquals("1.2 公里", distanceLabel(1_234))
        assertEquals("15 公里", distanceLabel(15_400))
    }

    @Test fun splitsAdministrativePrefixFromPlaceName() {
        assertEquals("南面村委会旁" to "广东省佛山市南海区大沥镇", splitPlaceName("广东省佛山市南海区大沥镇南面村委会旁"))
        assertEquals("天河路 208 号" to "广东省广州市天河区", splitPlaceName("广东省广州市天河区天河路 208 号"))
        assertEquals("南京东路 100 号" to "上海市黄浦区", splitPlaceName("上海市黄浦区南京东路 100 号"))
        assertEquals("星巴克" to null, splitPlaceName("星巴克"))
        assertEquals("广东省佛山市" to null, splitPlaceName("广东省佛山市"))
    }
}
