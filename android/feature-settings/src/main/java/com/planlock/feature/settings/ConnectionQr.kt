package com.planlock.feature.settings

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

internal object ConnectionQr {
    fun decode(width: Int, height: Int, pixels: IntArray): String = try {
        require(width in 1..2_048 && height in 1..2_048 && pixels.size == width * height)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        QRCodeReader().decode(bitmap, mapOf(com.google.zxing.DecodeHintType.CHARACTER_SET to "UTF-8")).text.also { require(it.length <= 16_384) }
    } catch (_: Exception) { throw IllegalArgumentException("未识别到连接二维码，请选择清晰的二维码图片") }
}
