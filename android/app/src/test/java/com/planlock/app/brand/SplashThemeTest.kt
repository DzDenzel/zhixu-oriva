package com.planlock.app.brand

import androidx.compose.ui.graphics.toArgb
import com.planlock.design.InkPalettes
import com.planlock.design.ThemeMode
import com.planlock.design.ThemeStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** BUG-103：启动底色 = 所选主题页面底色；墨序跟随系统不设启动主题。 */
class SplashThemeTest {
    private val xml = File("src/main/res/values-v31/oriva_splash_themes.xml").readText()

    private fun splashColor(styleName: String): Int {
        val m = Regex("""<style name="${Regex.escape(styleName)}"[^>]*>\s*<item name="android:windowSplashScreenBackground">#([0-9A-Fa-f]{6})</item>""").find(xml)
        assertNotNull("missing $styleName", m)
        return (0xFF000000.toInt()) or m!!.groupValues[1].toInt(16)
    }

    @Test fun splashBackgroundMatchesThemeGround() {
        val cases = listOf(
            "Theme.PlanLock.Splash.InkLight" to InkPalettes.inkLight,
            "Theme.PlanLock.Splash.InkDark" to InkPalettes.inkDark,
            "Theme.PlanLock.Splash.Celadon" to InkPalettes.celadon,
            "Theme.PlanLock.Splash.Dusk" to InkPalettes.dusk,
            "Theme.PlanLock.Splash.Bauhaus" to InkPalettes.bauhaus,
            "Theme.PlanLock.Splash.Atlas" to InkPalettes.atlas,
            "Theme.PlanLock.Splash.Phosphor" to InkPalettes.phosphor,
        )
        cases.forEach { (name, palette) -> assertEquals(name, palette.ground.toArgb(), splashColor(name)) }
    }

    @Test fun everyChoiceHasAStyleExceptInkFollowingSystem() {
        assertNull(SplashThemeSync.styleFor(ThemeStyle.INK, ThemeMode.SYSTEM))
        ThemeMode.entries.filter { it != ThemeMode.SYSTEM }.forEach { assertNotNull(SplashThemeSync.styleFor(ThemeStyle.INK, it)) }
        ThemeStyle.entries.filter { it != ThemeStyle.INK }.forEach { style ->
            ThemeMode.entries.forEach { mode -> assertNotNull(SplashThemeSync.styleFor(style, mode)) }
        }
    }

    @Test fun keysDistinguishInkModesOnly() {
        assertEquals("ink-system", SplashThemeSync.keyFor(ThemeStyle.INK, ThemeMode.SYSTEM))
        assertEquals("ink-dark", SplashThemeSync.keyFor(ThemeStyle.INK, ThemeMode.DARK))
        assertEquals("atlas", SplashThemeSync.keyFor(ThemeStyle.ATLAS, ThemeMode.LIGHT))
        assertEquals("atlas", SplashThemeSync.keyFor(ThemeStyle.ATLAS, ThemeMode.DARK))
    }
}
