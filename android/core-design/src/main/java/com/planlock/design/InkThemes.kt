package com.planlock.design

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * 主题（v4.0）：每套主题是一整套「材料」——底色、纸面、文字、强调色、字体与专属背景，
 * 而不是同一张纸换个颜色。墨序可随系统明暗；其余主题各有固定的明暗气质。
 */
data class InkPalette(
    val ground: Color,
    val raised: Color,
    val sunk: Color,
    val high: Color,
    val highest: Color,
    val hairline: Color,
    val text: Color,
    val soft: Color,
    val faint: Color,
    /** 主强调色（墨序为朱）：此刻、进行中、动作、选中。 */
    val accent: Color,
    val onAccent: Color,
    val jade: Color,
    val brass: Color,
    val indigo: Color,
    val crimson: Color,
    val slab: Color,
    val onSlab: Color,
    val inverse: Color,
    val onInverse: Color,
    val isDark: Boolean,
    /** 标题与数字字体。 */
    val display: FontFamily = FontFamily.Serif,
    /** 刻度与元信息字体。 */
    val meta: FontFamily = FontFamily.Monospace,
    /** 正文字体；null = 系统默认无衬线。 */
    val body: FontFamily? = null,
    /** 类别色覆盖（学习 / 工作 / 运动 / 睡眠 / 桃 / 琥珀）；null = 用墨序默认类别色。 */
    val cats: CategoryColors? = null,
)

data class CategoryColors(val study: Color, val work: Color, val sport: Color, val sleep: Color, val rose: Color, val amber: Color,
    val hobby: Color, val other: Color)

enum class ThemeStyle(val key: String, val label: String, val motto: String) {
    INK("ink", "墨序", "宣纸朱印 · 随系统明暗"),
    CELADON("celadon", "青瓷", "冰裂釉面 · 酱釉点睛"),
    DUSK("dusk", "暮霞", "落日天色 · 珊瑚梅紫"),
    BAUHAUS("bauhaus", "构成", "红黄蓝几何 · 粗黑字"),
    ATLAS("atlas", "星图", "夜空星盘 · 金线连星"),
    PHOSPHOR("phosphor", "荧屏", "绿光终端 · 琥珀光标"),
    ;

    fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (this) {
        INK -> mode.isDark(systemDark)
        ATLAS, PHOSPHOR -> true
        CELADON, DUSK, BAUHAUS -> false
    }
}

object InkPalettes {
    val inkLight = InkPalette(
        ground = Ink.paper, raised = Ink.paperRaised, sunk = Ink.paperSunk, high = Ink.paperSunk, highest = Ink.paperDeep,
        hairline = Ink.hairline, text = Ink.ink, soft = Ink.inkSoft, faint = Ink.inkFaint,
        accent = Ink.vermilion, onAccent = Color(0xFFFFF6EE), jade = Ink.jade, brass = Ink.brass, indigo = Ink.indigo, crimson = Ink.crimson,
        slab = Ink.ink, onSlab = Ink.paperRaised, inverse = Ink.ink, onInverse = Ink.paperRaised, isDark = false,
    )
    val inkDark = InkPalette(
        ground = Ink.night, raised = Ink.nightRaised, sunk = Ink.nightSunk, high = Ink.nightHigh, highest = Ink.nightHighest,
        hairline = Ink.nightHairline, text = Ink.moon, soft = Ink.moonSoft, faint = Ink.moonFaint,
        accent = Ink.vermilionNight, onAccent = Ink.night, jade = Ink.jadeNight, brass = Ink.brassNight, indigo = Ink.indigoNight, crimson = Ink.crimsonNight,
        slab = Ink.nightHighest, onSlab = Ink.moon, inverse = Ink.moon, onInverse = Ink.night, isDark = true,
    )

    /** 青瓷：灰青釉面，深松墨字，酱釉（铁锈橙）点睛。 */
    val celadon = InkPalette(
        ground = Color(0xFFD7E3DA), raised = Color(0xFFEEF4EF), sunk = Color(0xFFC6D5CA), high = Color(0xFFC6D5CA), highest = Color(0xFFB4C7B9),
        hairline = Color(0xFFB2C5B7), text = Color(0xFF1C3830), soft = Color(0xFF4A665C), faint = Color(0xFF6A8579),
        accent = Color(0xFFAD4F28), onAccent = Color(0xFFFFF4EC), jade = Color(0xFF2B7660), brass = Color(0xFF8A671D), indigo = Color(0xFF365A79), crimson = Color(0xFFA3202A),
        slab = Color(0xFF1C3830), onSlab = Color(0xFFEEF4EF), inverse = Color(0xFF1C3830), onInverse = Color(0xFFEEF4EF), isDark = false,
    )

    /** 暮霞：桃杏天色，梅紫字，珊瑚强调。 */
    val dusk = InkPalette(
        ground = Color(0xFFF5DDD2), raised = Color(0xFFFFF5EF), sunk = Color(0xFFEDCFC3), high = Color(0xFFEDCFC3), highest = Color(0xFFE2BDB0),
        hairline = Color(0xFFE6C4B8), text = Color(0xFF2D1D36), soft = Color(0xFF66506E), faint = Color(0xFF9A8494),
        accent = Color(0xFFC23B2C), onAccent = Color(0xFFFFF5EF), jade = Color(0xFF3A8571), brass = Color(0xFFB8741C), indigo = Color(0xFF5458A3), crimson = Color(0xFFA3202A),
        slab = Color(0xFF3A2747), onSlab = Color(0xFFFFF5EF), inverse = Color(0xFF2D1D36), onInverse = Color(0xFFFFF5EF), isDark = false,
    )

    /** 构成：包豪斯奶白底，纯黑字，红黄蓝三原色；无衬线粗字。 */
    val bauhaus = InkPalette(
        ground = Color(0xFFEFE9DA), raised = Color(0xFFFBF8F0), sunk = Color(0xFFE2DAC6), high = Color(0xFFE2DAC6), highest = Color(0xFFD4CAB2),
        hairline = Color(0xFFCFC5AE), text = Color(0xFF121212), soft = Color(0xFF444444), faint = Color(0xFF878274),
        accent = Color(0xFFD42818), onAccent = Color.White, jade = Color(0xFF1E4E9E), brass = Color(0xFF9A6C00), indigo = Color(0xFF1E4E9E), crimson = Color(0xFFA3202A),
        slab = Color(0xFF121212), onSlab = Color(0xFFFBF8F0), inverse = Color(0xFF121212), onInverse = Color(0xFFFBF8F0), isDark = false,
        display = FontFamily.SansSerif, meta = FontFamily.Monospace, body = FontFamily.SansSerif,
        cats = CategoryColors(
            study = Color(0xFF1E4E9E), work = Color(0xFF2B2B2B), sport = Color(0xFF306534),
            sleep = Color(0xFF5B4BA0), rose = Color(0xFFC2324A), amber = Color(0xFF9A6C00),
            hobby = Color(0xFFAF4C20), other = Color(0xFF59606B),
        ),
    )

    /** 星图：深夜靛蓝，羊皮纸白字，金色强调。 */
    val atlas = InkPalette(
        ground = Color(0xFF0B1027), raised = Color(0xFF141B3B), sunk = Color(0xFF080C1E), high = Color(0xFF1B2348), highest = Color(0xFF232C57),
        hairline = Color(0xFF29335F), text = Color(0xFFEDE6D1), soft = Color(0xFFA6A8C4), faint = Color(0xFF6B6F97),
        accent = Color(0xFFE8B754), onAccent = Color(0xFF1A1300), jade = Color(0xFF6FD3B5), brass = Color(0xFFF2CC87), indigo = Color(0xFF93AAFF), crimson = Color(0xFFFF9C9C),
        slab = Color(0xFF1E2750), onSlab = Color(0xFFEDE6D1), inverse = Color(0xFFEDE6D1), onInverse = Color(0xFF0B1027), isDark = true,
    )

    /** 荧屏：磷光绿终端，琥珀光标；全等宽字体。 */
    val phosphor = InkPalette(
        ground = Color(0xFF050A06), raised = Color(0xFF0B150D), sunk = Color(0xFF030604), high = Color(0xFF102016), highest = Color(0xFF163020),
        hairline = Color(0xFF1C3A24), text = Color(0xFFB4F5AC), soft = Color(0xFF6DB178), faint = Color(0xFF3F6E48),
        accent = Color(0xFFFFB347), onAccent = Color(0xFF1A0E00), jade = Color(0xFF7CFFB0), brass = Color(0xFFFFD27A), indigo = Color(0xFF7FD4FF), crimson = Color(0xFFFF6B6B),
        slab = Color(0xFF0F2416), onSlab = Color(0xFFB4F5AC), inverse = Color(0xFFB4F5AC), onInverse = Color(0xFF050A06), isDark = true,
        display = FontFamily.Monospace, meta = FontFamily.Monospace, body = FontFamily.Monospace,
        cats = CategoryColors(
            study = Color(0xFF7FD4FF), work = Color(0xFF7CFFB0), sport = Color(0xFFD7FF6B),
            sleep = Color(0xFFC6A2FF), rose = Color(0xFFFF8FB1), amber = Color(0xFFFFB347),
            hobby = Color(0xFFFF936B), other = Color(0xFFB2BDCA),
        ),
    )

    fun of(style: ThemeStyle, dark: Boolean): InkPalette = when (style) {
        ThemeStyle.INK -> if (dark) inkDark else inkLight
        ThemeStyle.CELADON -> celadon
        ThemeStyle.DUSK -> dusk
        ThemeStyle.BAUHAUS -> bauhaus
        ThemeStyle.ATLAS -> atlas
        ThemeStyle.PHOSPHOR -> phosphor
    }
}

/** 当前色板：所有 [InkTone] / [InkText] 从这里取值。 */
val LocalInkPalette = compositionLocalOf { InkPalettes.inkLight }

/** 主题选择：轻量偏好（SharedPreferences），全局 Compose 状态，切换即时生效。 */
object ThemeStyleStore {
    private const val FILE = "oriva_ui"
    private const val KEY = "theme_style"
    var current by mutableStateOf(ThemeStyle.INK)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        current = ThemeStyle.entries.firstOrNull { it.key == raw } ?: ThemeStyle.INK
    }

    fun select(context: Context, style: ThemeStyle) {
        current = style
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, style.key).apply()
    }
}

/** 切换主题时颜色像墨入纸一样过渡（字体与明暗直接切换）。 */
@Composable
internal fun animatePalette(target: InkPalette, ms: Int): InkPalette {
    @Composable fun a(c: Color, label: String) = animateColorAsState(c, tween(ms, easing = Ink.Easing), label = label).value
    return target.copy(
        ground = a(target.ground, "p-ground"), raised = a(target.raised, "p-raised"), sunk = a(target.sunk, "p-sunk"),
        high = a(target.high, "p-high"), highest = a(target.highest, "p-highest"), hairline = a(target.hairline, "p-hairline"),
        text = a(target.text, "p-text"), soft = a(target.soft, "p-soft"), faint = a(target.faint, "p-faint"),
        accent = a(target.accent, "p-accent"), onAccent = a(target.onAccent, "p-onAccent"), jade = a(target.jade, "p-jade"),
        brass = a(target.brass, "p-brass"), indigo = a(target.indigo, "p-indigo"), crimson = a(target.crimson, "p-crimson"),
        slab = a(target.slab, "p-slab"), onSlab = a(target.onSlab, "p-onSlab"), inverse = a(target.inverse, "p-inverse"),
        onInverse = a(target.onInverse, "p-onInverse"),
    )
}
