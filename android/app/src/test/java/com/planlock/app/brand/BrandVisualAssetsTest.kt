package com.planlock.app.brand

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Consumer asset checks: catches dark fallback tiles and independently redrawn splash shapes. */
class BrandVisualAssetsTest {
    private val res = File("src/main/res")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test fun fallbackTilesAreLightWithGreenVerticalDepth() {
        for ((density, size) in listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)) {
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val image = HostPng(File(res, "mipmap-$density/$name.png"))
                assertEquals(size, image.width)
                assertEquals(size, image.height)
                val background = sample(image, .5, .05)
                assertTrue("$density/$name should have a light background", luma(background) > .82)
                val top = sample(image, .5, .25)
                val bottom = sample(image, .53, .75)
                assertTrue("$density/$name center should be green", bottom.green > bottom.red + 15)
                assertTrue("$density/$name center should deepen downward", luma(top) > luma(bottom) + .08)
                assertEquals("$density/$name mask should not bake a rectangular board", 0, image.getRGB(0, 0) ushr 24)
            }
        }
    }

    @Test fun splashUsesTheLauncherInnerSilhouetteWithoutOuterArcs() {
        val launcher = paths("ic_launcher_foreground.xml")
        val splash = paths("oriva_splash_mark.xml")
        assertEquals("Splash should contain only the connected inner mark", 1, splash.size)
        val expected = normalizedGeometry(launcher[1])
        val actual = normalizedGeometry(splash.single())
        assertEquals("Splash should preserve the inner logo's curve topology", expected.size, actual.size)
        expected.zip(actual).forEach { (want, got) -> assertEquals(want, got, .000001) }
        assertEquals(commands(launcher[1]), commands(splash.single()))
        assertCenteredInsideViewport("oriva_splash_mark.xml")
        val coreFile = File(res, "drawable/zhixu_splash_core.xml")
        assertTrue("Brand lockup needs an unpadded mark to avoid a large invisible spacer", coreFile.exists())
        val core = paths(coreFile.name).single()
        assertEquals(commands(launcher[1]), commands(core))
        expected.zip(normalizedGeometry(core)).forEach { (want, got) -> assertEquals(want, got, .000001) }
        assertCenteredInsideViewport(coreFile.name)
    }

    @Test fun approvedMottoFontCanDisplayTheEntirePhraseWithoutFallback() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "values/strings.xml")).getElementsByTagName("string")
        val motto = (0 until strings.length).map { strings.item(it) as Element }
            .single { it.getAttribute("name") == "brand_motto" }.textContent
        assertEquals("知所想，序所行", motto)
        val fontFile = File(res, "font/zhixu_motto.ttf")
        assertTrue("The approved calligraphy must be bundled, not a system-font approximation", fontFile.exists())
        val type = Class.forName("java.awt.Font")
        val font = type.getMethod("createFont", Int::class.javaPrimitiveType, File::class.java)
            .invoke(null, 0, fontFile)
        assertTrue((type.getMethod("getName").invoke(font) as String).replace(" ", "").contains("MaShanZheng"))
        for (glyph in motto) {
            assertTrue("Missing glyph $glyph would silently fall back", type.getMethod("canDisplay", Char::class.javaPrimitiveType)
                .invoke(font, glyph) as Boolean)
        }
        assertTrue("A single motto must not bundle the multi-megabyte font", fontFile.length() in 1..100_000)
        val license = File("src/main/assets/licenses/zhixu_motto_OFL.txt")
        assertTrue("Font distribution must carry the original license", license.exists())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(license.readBytes())
            .joinToString("") { "%02X".format(it) }
        assertEquals("D7BDB1CEE215B689E23C2F95672A6084C790542170648267A55114103D756A08", digest)
    }

    private fun sample(image: HostPng, x: Double, y: Double) =
        Rgb(image.getRGB((x * image.width).toInt(), (y * image.height).toInt()))

    private fun luma(color: Rgb) = (.2126 * color.red + .7152 * color.green + .0722 * color.blue) / 255

    private class Rgb(argb: Int) {
        val red = (argb ushr 16) and 255
        val green = (argb ushr 8) and 255
        val blue = argb and 255
    }

    // Android's compile bootclasspath omits desktop image APIs; these host-only
    // asset tests use JDK ImageIO at runtime without adding a production dependency.
    private class HostPng(file: File) {
        private val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
        private val type = image.javaClass
        val width = type.getMethod("getWidth").invoke(image) as Int
        val height = type.getMethod("getHeight").invoke(image) as Int
        fun getRGB(x: Int, y: Int) = type.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, x, y) as Int
    }

    private fun paths(name: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(File(res, "drawable/$name")).getElementsByTagName("path")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "pathData") }
    }

    private fun normalizedGeometry(data: String): List<Double> {
        val values = Regex("-?\\d+(?:\\.\\d+)?").findAll(data).map { it.value.toDouble() }.toList()
        val xs = values.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = values.filterIndexed { index, _ -> index % 2 == 1 }
        val scale = maxOf(xs.max() - xs.min(), ys.max() - ys.min())
        return values.mapIndexed { index, value ->
            (value - if (index % 2 == 0) xs.min() else ys.min()) / scale
        }
    }

    private fun commands(data: String) = Regex("[A-Za-z]").findAll(data).map { it.value }.toList()

    /** Current marks use translation-only groups: validate their actual viewport placement. */
    private fun assertCenteredInsideViewport(name: String) {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(res, "drawable/$name"))
        val root = doc.documentElement
        val width = root.getAttributeNS(androidNs, "viewportWidth").toDouble()
        val height = root.getAttributeNS(androidNs, "viewportHeight").toDouble()
        val path = doc.getElementsByTagName("path").item(0) as Element
        var dx = 0.0; var dy = 0.0
        var parent = path.parentNode
        while (parent is Element && parent != root) {
            fun attribute(key: String, default: Double) = parent.let { it as Element }
                .getAttributeNS(androidNs, key).toDoubleOrNull() ?: default
            assertEquals("Unexpected nonuniform/rotated mark transform", 1.0, attribute("scaleX", 1.0), .000001)
            assertEquals(1.0, attribute("scaleY", 1.0), .000001)
            assertEquals(0.0, attribute("rotation", 0.0), .000001)
            dx += attribute("translateX", 0.0); dy += attribute("translateY", 0.0)
            parent = parent.parentNode
        }
        val coords = Regex("-?\\d+(?:\\.\\d+)?").findAll(path.getAttributeNS(androidNs, "pathData"))
            .map { it.value.toDouble() }.toList()
        val xs = coords.filterIndexed { i, _ -> i % 2 == 0 }.map { it + dx }
        val ys = coords.filterIndexed { i, _ -> i % 2 == 1 }.map { it + dy }
        assertTrue("$name would clip its path", xs.min() >= 0 && xs.max() <= width && ys.min() >= 0 && ys.max() <= height)
        assertEquals(width / 2, (xs.min() + xs.max()) / 2, width * .01)
        assertEquals(height / 2, (ys.min() + ys.max()) / 2, height * .01)
    }
}
