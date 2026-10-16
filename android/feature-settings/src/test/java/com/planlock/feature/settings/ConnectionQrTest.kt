package com.planlock.feature.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class ConnectionQrTest {
    @Test fun actualQrPixelsDecodeAndInvalidImagesDoNotBecomeConfiguration() {
        val raw = """{"version":1,"instanceLabel":"我的服务","apiBaseUrl":"https://example.org/api/v1/","pluginsCatalogUrl":"https://plugins.example.org/catalog/","lanAllowed":false}"""
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 600, 600, mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
        val pixels = IntArray(600 * 600) { if (matrix[it % 600, it / 600]) 0xff000000.toInt() else -1 }
        assertEquals(raw, ConnectionQr.decode(600, 600, pixels))
        java.io.File("build/req032-connection-qr.ppm").outputStream().use { out ->
            out.write("P6\n600 600\n255\n".toByteArray())
            pixels.forEach { pixel -> repeat(3) { out.write(pixel and 255) } }
        }
        try { ConnectionQr.decode(64, 64, IntArray(64 * 64) { -1 }); fail("Blank image accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
