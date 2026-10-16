package com.planlock.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI 设计规范 v4.0：六套主题的明暗归属与可读性（WCAG 对比度）。 */
class ThemePaletteTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (maxOf(la, lb) + .05f) / (minOf(la, lb) + .05f)
    }

    private val all = ThemeStyle.entries.flatMap { s -> listOf(false, true).map { d -> s to InkPalettes.of(s, d) } }

    @Test fun darknessMatchesStyle() {
        assertEquals(false, ThemeStyle.CELADON.isDark(ThemeMode.DARK, true))
        assertEquals(true, ThemeStyle.ATLAS.isDark(ThemeMode.LIGHT, false))
        assertEquals(true, ThemeStyle.INK.isDark(ThemeMode.SYSTEM, true))
        all.forEach { (s, p) -> assertEquals("$s", p.isDark, p.ground.luminance() < .2f) }
    }

    @Test fun textIsReadableOnEverySurface() {
        all.forEach { (s, p) ->
            assertTrue("$s text", contrast(p.text, p.raised) >= 7f)
            assertTrue("$s soft", contrast(p.soft, p.raised) >= 4.5f)
            assertTrue("$s faint", contrast(p.faint, p.raised) >= 3f)
            assertTrue("$s accent", contrast(p.accent, p.raised) >= 4.5f)
            assertTrue("$s onAccent", contrast(p.onAccent, p.accent) >= 4.5f)
            assertTrue("$s onSlab", contrast(p.onSlab, p.slab) >= 7f)
            assertTrue("$s brass", contrast(p.brass, p.raised) >= 3f)
            p.cats?.let { c ->
                listOf(c.study, c.work, c.sport, c.sleep, c.rose, c.amber).forEach { assertTrue("$s category", contrast(it, p.raised) >= 3f) }
            }
        }
    }

    @Test fun everyThemeLooksDifferent() {
        val grounds = ThemeStyle.entries.map { InkPalettes.of(it, false).ground }.toSet()
        assertEquals(ThemeStyle.entries.size, grounds.size)
    }
}
