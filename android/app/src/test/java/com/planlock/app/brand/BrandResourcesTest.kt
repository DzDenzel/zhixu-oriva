package com.planlock.app.brand

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BrandResourcesTest {
    private val res = File("src/main/res")
    @Test fun iconVariantsAndBrandColorAreConsistent() {
        val color = File("../core-design/src/main/res/values/oriva_brand.xml").readText()
        assertTrue(color.contains("#1A1D1B"))
        // Launcher artwork now has the user-approved independent light palette;
        // BrandVisualAssetsTest checks the rendered fallbacks, not the old alias.
        assertTrue(File(res, "values/oriva_splash.xml").readText().contains("@color/oriva_brand"))
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            assertTrue(File(res, "mipmap-anydpi-v26/$name.xml").readText().contains("<monochrome"))
            for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
                assertTrue(File(res, "mipmap-$density/$name.png").length() in 1..50_000)
            }
        }
        assertFalse(File(res, "mipmap-nodpi/ic_launcher.png").exists())
        // Display-name acceptance is checked on compiled APK labels by
        // work/zhixu-name-20261003/verify-brand-name.ps1, not by source substrings.
    }
}
