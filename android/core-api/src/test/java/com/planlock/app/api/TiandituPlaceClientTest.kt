package com.planlock.app.api

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int

class TiandituPlaceClientTest {
    @Test fun surroundingRequestRequiresKeywordKeepsWgs84AndUsesVerifiedBrandAlias() {
        val center = PlaceSelection("中心", 31.2304, 121.4737)
        val request = TiandituPlaceClient.searchDocument(" KfC ", center, 1_000)
        assertEquals("肯德基", request["keyWord"]?.jsonPrimitive?.contentOrNull)
        assertEquals("121.4737,31.2304", request["pointLonlat"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3, request["queryType"]!!.jsonPrimitive.int)
        assertEquals(1_000, request["queryRadius"]!!.jsonPrimitive.int)
        assertEquals("kfc大楼", TiandituPlaceClient.searchDocument("kfc大楼", center)["keyWord"]?.jsonPrimitive?.contentOrNull)
        assertThrows(IllegalArgumentException::class.java) { TiandituPlaceClient.searchDocument(" ", center) }
    }

    @Test fun poisRemainWgs84AndInvalidDuplicateCoordinatesAreRejected() {
        val raw = """{"status":{"infocode":1000},"pois":[{"name":"星巴克","address":"测试街1号","lonlat":"121.4737,31.2304"},{"name":"星巴克","lonlat":"121.4737,31.2304"},{"name":"错误","lonlat":"NaN,91"}]}"""
        val rows = TiandituPlaceClient.parsePois(raw)
        assertEquals(1, rows.size)
        assertEquals(31.2304, rows.single().latitude, .000001)
        assertEquals(121.4737, rows.single().longitude, .000001)
        assertEquals("测试街1号", rows.single().address)
        assertThrows(java.io.IOException::class.java) { TiandituPlaceClient.parsePois("""{"status":{"infocode":2001}}""") }
    }
    @Test fun reverseUsesRequestedCenterAndNoResultStaysEmpty() {
        assertEquals("图书馆", TiandituPlaceClient.parseReverse("""{"result":{"formatted_address":"测试路","addressComponent":{"poi":"图书馆"}}}""", 31.2, 121.4)?.name)
        assertNull(TiandituPlaceClient.parseReverse("{}", 31.2, 121.4))
    }
}
