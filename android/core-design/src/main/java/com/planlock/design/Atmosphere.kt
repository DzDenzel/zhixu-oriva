package com.planlock.design

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** 场景枚举保留给调用方表达语义；页面本身透明，背景由根部统一绘制（切页时背景不跟着动）。 */
enum class PageAtmosphere { TODAY, PLANS, ASSISTANT, STATS, QUIET }

@Composable
fun Modifier.planLockAtmosphere(@Suppress("UNUSED_PARAMETER") style: PageAtmosphere = PageAtmosphere.QUIET): Modifier = this

/**
 * 墨序主题下的五种背景（v3.3）：素纸 / 晨雾 / 稿纸 / 星点 / 远山。其他主题各有专属背景，不用这组。
 */
enum class Backdrop(val key: String, val label: String) {
    PLAIN("plain", "素纸"),
    MIST("mist", "晨雾"),
    RULED("ruled", "稿纸"),
    DOTS("dots", "星点"),
    HILLS("hills", "远山"),
}

/** 背景选择：轻量偏好（SharedPreferences），全局 Compose 状态，切换即时生效。 */
object BackdropStore {
    private const val FILE = "oriva_ui"
    private const val KEY = "backdrop"
    var current by mutableStateOf(Backdrop.MIST)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        current = Backdrop.entries.firstOrNull { it.key == raw } ?: Backdrop.MIST
    }

    fun select(context: Context, backdrop: Backdrop) {
        current = backdrop
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, backdrop.key).apply()
    }
}

/** 无限循环的 0→1 相位；减弱动画时恒为 0。只在绘制阶段读取，不触发重组。 */
@Composable
private fun rememberPhase(periodMs: Int, enabled: Boolean, label: String): State<Float> {
    if (!enabled) return remember0
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = label)
}

private val remember0: State<Float> = mutableStateOf(0f)

/** 根部背景：画在整个应用之下（含导航区），由 MainActivity 的根 Scaffold 使用。按当前主题选择专属背景。 */
@Composable
fun Modifier.planLockBackdrop(): Modifier = themeBackdrop(ThemeStyleStore.current, BackdropStore.current)

/** 指定主题（与墨序背景）的背景；主题小样也用它画出真实效果。 */
@Composable
fun Modifier.themeBackdrop(style: ThemeStyle, inkBackdrop: Backdrop = BackdropStore.current): Modifier {
    val p = LocalInkPalette.current
    val motion = !LocalReducedMotion.current
    return when (style) {
        ThemeStyle.INK -> inkBackdrop(inkBackdrop)
        ThemeStyle.CELADON -> celadonBackdrop(p)
        ThemeStyle.DUSK -> duskBackdrop(p, rememberPhase(60_000, motion, "dusk"))
        ThemeStyle.BAUHAUS -> bauhausBackdrop(p)
        ThemeStyle.ATLAS -> atlasBackdrop(p, rememberPhase(120_000, motion, "atlas"))
        ThemeStyle.PHOSPHOR -> phosphorBackdrop(p, rememberPhase(7_000, motion, "phosphor"))
    }
}

// ───────────────────────────── 墨序 ─────────────────────────────

/** 墨序背景（素纸 / 晨雾 / 稿纸 / 星点 / 远山）。 */
@Composable
fun Modifier.inkBackdrop(backdrop: Backdrop): Modifier {
    val p = LocalInkPalette.current
    val dark = p.isDark
    val drift = rememberPhase(24_000, backdrop == Backdrop.MIST && !LocalReducedMotion.current, "mist")
    val blooms = listOf(InkHue.study, InkHue.rose, InkHue.amber).map { if (dark) it.dark else it.light }
    val hills = listOf(InkHue.work, InkHue.study, InkHue.sleep).map { if (dark) it.dark else it.light }
    val base = p.ground
    val ink = p.text
    val seal = p.accent
    return drawWithCache {
        onDrawBehind {
            drawRect(base)
            when (backdrop) {
                Backdrop.PLAIN -> Unit
                Backdrop.MIST -> drawMist(blooms, drift.value, if (dark) .16f else .14f)
                Backdrop.RULED -> drawRuled(ink.copy(alpha = if (dark) .06f else .05f), seal.copy(alpha = .12f))
                Backdrop.DOTS -> drawDots(ink.copy(alpha = if (dark) .14f else .09f))
                Backdrop.HILLS -> drawHills(hills, if (dark) .20f else .13f)
            }
        }
    }
}

private fun DrawScope.drawMist(colors: List<Color>, t: Float, alpha: Float) {
    val w = size.width; val h = size.height
    val a = 2f * PI.toFloat() * t
    val centers = listOf(
        Offset(w * (.15f + .08f * sin(a)), h * (.12f + .04f * sin(a + 1f))),
        Offset(w * (.95f + .06f * sin(a + 2f)), h * (.38f + .05f * sin(a + 3f))),
        Offset(w * (.30f + .07f * sin(a + 4f)), h * (.88f + .03f * sin(a + 5f))),
    )
    val radii = listOf(w * .75f, w * .70f, w * .85f)
    colors.forEachIndexed { i, c ->
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = alpha), Color.Transparent), centers[i], radii[i]),
            radius = radii[i], center = centers[i])
    }
}

private fun DrawScope.drawRuled(line: Color, margin: Color) {
    val step = 30.dp.toPx()
    var y = step * 3
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
    val x = 14.dp.toPx()
    drawLine(margin, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
}

private fun DrawScope.drawDots(dot: Color) {
    val step = 22.dp.toPx()
    val r = 1.1.dp.toPx()
    var y = step
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) step / 2 else step
        while (x < size.width) {
            drawCircle(dot, r, Offset(x, y))
            x += step
        }
        y += step; row++
    }
}

private fun DrawScope.drawHills(colors: List<Color>, alpha: Float) {
    val w = size.width; val h = size.height
    colors.forEachIndexed { i, c ->
        val baseY = h * (.80f + i * .06f)
        val amp = h * (.05f - i * .01f)
        val path = Path().apply {
            moveTo(0f, h)
            lineTo(0f, baseY)
            val steps = 24
            for (k in 0..steps) {
                val x = w * k / steps
                val y = baseY - amp * sin((k.toFloat() / steps) * PI.toFloat() * (1.3f + i * .4f) + i * 1.7f)
                lineTo(x, y)
            }
            lineTo(w, h)
            close()
        }
        drawPath(path, c.copy(alpha = alpha * (1f - i * .15f)))
    }
    drawCircle(colors.first().copy(alpha = alpha * .8f), w * .07f, Offset(w * .78f, h * .70f))
}

// ───────────────────────────── 青瓷：冰裂纹 ─────────────────────────────

/** 釉面上半透亮、下半积釉渐深；满布随机走向的冰裂纹，每道裂纹带一道高光，像真的瓷面。 */
private fun Modifier.celadonBackdrop(p: InkPalette): Modifier = drawWithCache {
    val w = size.width; val h = size.height
    val rnd = Random(11)
    val cracks = Path()
    val unit = 1.dp.toPx()
    repeat(((w * h) / (unit * unit * 5200f)).toInt().coerceIn(40, 140)) {
        var x = rnd.nextFloat() * w
        var y = rnd.nextFloat() * h
        cracks.moveTo(x, y)
        var ang = rnd.nextFloat() * 2f * PI.toFloat()
        repeat(3 + rnd.nextInt(5)) {
            val len = (16f + rnd.nextFloat() * 48f) * unit
            ang += (rnd.nextFloat() - .5f) * 1.5f
            x += cos(ang) * len; y += sin(ang) * len
            cracks.lineTo(x, y)
        }
    }
    val glaze = Brush.verticalGradient(listOf(lerp(p.ground, Color.White, .25f), p.ground, lerp(p.ground, p.sunk, .9f)))
    val sheen = Brush.radialGradient(listOf(Color.White.copy(alpha = .42f), Color.Transparent), Offset(w * .18f, h * .06f), w * .95f)
    val crack = p.text.copy(alpha = .085f)
    val light = Color.White.copy(alpha = .38f)
    onDrawBehind {
        drawRect(glaze)
        drawRect(sheen)
        translate(unit * .8f, unit * .8f) { drawPath(cracks, light, style = Stroke(unit * .7f)) }
        drawPath(cracks, crack, style = Stroke(unit * .8f))
    }
}

// ───────────────────────────── 暮霞：落日天色 ─────────────────────────────

/** 桃杏→珊瑚→淡紫的天色渐变，地平线上一轮柔光落日，几道薄云缓慢横移。 */
private fun Modifier.duskBackdrop(p: InkPalette, t: State<Float>): Modifier = drawWithCache {
    val w = size.width; val h = size.height
    val sky = Brush.verticalGradient(
        0f to Color(0xFFF8D7C1), .42f to Color(0xFFF3C2B6), .78f to Color(0xFFE2C3DD), 1f to Color(0xFFCDBFE3),
    )
    val sunCenter = Offset(w * .70f, h * .66f)
    val glow = Brush.radialGradient(listOf(Color(0xFFFF9A6E).copy(alpha = .55f), Color.Transparent), sunCenter, w * .62f)
    val clouds = listOf(
        floatArrayOf(.10f, .20f, .70f, .035f), floatArrayOf(.45f, .30f, .80f, .028f),
        floatArrayOf(-.10f, .52f, .65f, .03f), floatArrayOf(.35f, .80f, .90f, .04f),
    )
    onDrawBehind {
        drawRect(sky)
        drawCircle(glow, w * .62f, sunCenter)
        drawCircle(Color(0xFFFFB08A).copy(alpha = .55f), w * .14f, sunCenter)
        val shift = t.value * w * .3f
        clouds.forEachIndexed { i, c ->
            val cw = w * c[2]; val ch = h * c[3]
            var x = (w * c[0] + shift * (1f + i * .3f)) % (w + cw) - cw * .3f
            if (x > w) x -= w + cw
            drawOval(Color.White.copy(alpha = .30f), Offset(x, h * c[1]), Size(cw, ch))
        }
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, p.slab.copy(alpha = .10f)), startY = h * .78f, endY = h))
    }
}

// ───────────────────────────── 构成：包豪斯 ─────────────────────────────

/** 红圆、蓝条、黄三角、黑弧与两道黑线——低透明度的几何构成，铺在奶白底上。 */
private fun Modifier.bauhausBackdrop(p: InkPalette): Modifier = drawWithCache {
    val w = size.width; val h = size.height
    val red = Color(0xFFD42818); val blue = Color(0xFF1E4E9E); val yellow = Color(0xFFE2A20A); val black = Color(0xFF121212)
    val tri = Path().apply { moveTo(w * .58f, h); lineTo(w * 1.02f, h); lineTo(w * 1.02f, h * .70f); close() }
    val unit = 1.dp.toPx()
    onDrawBehind {
        drawRect(p.ground)
        drawCircle(red.copy(alpha = .14f), w * .44f, Offset(w * .96f, h * .09f))
        // v4.3（审查 U7c）：原为 13% 透明度的宽蓝块，在奶白底上读成一块灰色矩形压在列表后面；
        // 改成贴左缘的一道窄而饱和的蓝条，像构成里的一笔色块，而不是渲染残留。
        drawRect(blue.copy(alpha = .55f), Offset(0f, h * .56f), Size(minOf(w * .025f, 10f * unit), h * .11f))
        drawPath(tri, yellow.copy(alpha = .22f))
        drawArc(black.copy(alpha = .12f), 270f, 90f, false, Offset(-w * .32f, h * .80f), Size(w * .64f, w * .64f), style = Stroke(3f * unit))
        drawLine(black.copy(alpha = .09f), Offset(w * .08f, 0f), Offset(w * .08f, h), 1.5f * unit)
        drawLine(black.copy(alpha = .07f), Offset(0f, h * .38f), Offset(w, h * .38f), unit)
        drawCircle(black.copy(alpha = .22f), 7f * unit, Offset(w * .86f, h * .47f))
    }
}

// ───────────────────────────── 星图：夜空星盘 ─────────────────────────────

/** 靛夜渐变上的星点（缓慢明灭）、三组金线星座，右下角一面缓慢转动的星盘刻度环。 */
private fun Modifier.atlasBackdrop(p: InkPalette, t: State<Float>): Modifier = drawWithCache {
    val w = size.width; val h = size.height
    val unit = 1.dp.toPx()
    val rnd = Random(5)
    val stars = List(((w * h) / (unit * unit * 2600f)).toInt().coerceIn(80, 260)) {
        floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h, (.5f + rnd.nextFloat() * 1.2f) * unit, .25f + rnd.nextFloat() * .65f, rnd.nextFloat())
    }
    val groups = List(3) { g ->
        val cx = w * (.18f + g * .32f); val cy = h * (.14f + (g % 2) * .22f)
        List(4 + rnd.nextInt(3)) { Offset(cx + (rnd.nextFloat() - .5f) * w * .32f, cy + (rnd.nextFloat() - .5f) * h * .14f) }
    }
    val bg = Brush.verticalGradient(listOf(p.ground, Color(0xFF16123A)))
    val gold = p.accent
    val pivot = Offset(w * 1.02f, h * 1.0f)
    onDrawBehind {
        drawRect(bg)
        rotate(t.value * 360f, pivot) {
            listOf(.95f, .78f, .62f).forEach { k -> drawCircle(gold.copy(alpha = .08f), w * k, pivot, style = Stroke(unit)) }
            for (i in 0 until 72) {
                val a = i * 5f / 180f * PI.toFloat()
                val r1 = w * .95f; val r2 = r1 - (if (i % 6 == 0) 10f else 4f) * unit
                drawLine(gold.copy(alpha = .10f), Offset(pivot.x + r1 * cos(a), pivot.y + r1 * sin(a)), Offset(pivot.x + r2 * cos(a), pivot.y + r2 * sin(a)), unit)
            }
        }
        val phase = t.value * 40f
        stars.forEach { s ->
            val tw = .65f + .35f * sin(2f * PI.toFloat() * (phase + s[4]))
            drawCircle(Color(0xFFF4EEDB).copy(alpha = (s[3] * tw).coerceIn(0f, 1f)), s[2], Offset(s[0], s[1]))
        }
        groups.forEach { pts ->
            for (i in 0 until pts.size - 1) drawLine(gold.copy(alpha = .26f), pts[i], pts[i + 1], .8f * unit)
            pts.forEach { drawCircle(gold.copy(alpha = .75f), 1.8f * unit, it) }
        }
    }
}

// ───────────────────────────── 荧屏：磷光终端 ─────────────────────────────

/** 中心泛绿光的暗角、细密扫描线、一道缓慢下行的刷新光带。 */
private fun Modifier.phosphorBackdrop(p: InkPalette, t: State<Float>): Modifier = drawWithCache {
    val w = size.width; val h = size.height
    val unit = 1.dp.toPx()
    val glow = Brush.radialGradient(listOf(Color(0xFF0F3318), p.ground), Offset(w / 2f, h * .42f), max(w, h) * .78f)
    val line = Color.Black.copy(alpha = .28f)
    val band = h * .14f
    onDrawBehind {
        drawRect(glow)
        var y = 0f
        while (y < h) { drawLine(line, Offset(0f, y), Offset(w, y), unit * .6f); y += 3f * unit }
        val top = t.value * (h + band) - band
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, p.text.copy(alpha = .05f), Color.Transparent), startY = top, endY = top + band),
            Offset(0f, top), Size(w, band))
    }
}

// ───────────────────────────── 设置页：主题与背景选择 ─────────────────────────────

/**
 * 主题小样：每张卡都用该主题自己的色板、字体与背景真实绘制（不是示意图）。两列排布，选中描当前强调色。
 */
@Composable
fun ThemeStyleCards(modifier: Modifier = Modifier, inkDark: Boolean = false) {
    val context = LocalContext.current
    val selected = ThemeStyleStore.current
    val ring = InkTone.accent
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThemeStyle.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { style ->
                    val isSelected = style == selected
                    Column(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) ring else InkTone.hairline, RoundedCornerShape(18.dp))
                            .clickable { ThemeStyleStore.select(context, style) }
                            .semantics { contentDescription = "主题" + style.label + if (isSelected) "，已选中" else "" },
                    ) {
                        ThemePreview(style, if (style == ThemeStyle.INK) inkDark else style.isDark(ThemeMode.SYSTEM, false))
                        Column(Modifier.fillMaxWidth().background(InkTone.raised).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(style.label, style = MaterialTheme.typography.titleSmall, color = if (isSelected) ring else InkTone.text)
                            FitText(style.motto, InkText.meta.copy(fontSize = 10.sp), InkTone.faint)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 一张缩小的「今日页」：该主题的背景、问候、表盘一角与一张进行中的卡片。 */
@Composable
private fun ThemePreview(style: ThemeStyle, dark: Boolean) {
    val p = InkPalettes.of(style, dark)
    CompositionLocalProvider(LocalInkPalette provides p, LocalDarkTheme provides p.isDark) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).themeBackdrop(style).padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("早上好", color = p.text, fontFamily = p.display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.raised.copy(alpha = .92f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(30.dp).background(p.accent))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("06:00", color = p.accent, fontFamily = p.meta, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        Text("晨读", color = p.text, fontFamily = p.display, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(p.accent))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(p.jade))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(p.brass))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(p.indigo))
                }
            }
        }
    }
}

/** 墨序背景小样：五格等分一行（不横滑，全部一眼可见），选中描朱边。 */
@Composable
fun BackdropSwatches(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selected = BackdropStore.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Backdrop.entries.forEach { option ->
            val isSelected = option == selected
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.7f)
                        .clip(RoundedCornerShape(12.dp))
                        .inkBackdrop(option)
                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) InkTone.vermilion else InkTone.hairline, RoundedCornerShape(12.dp))
                        .clickable { BackdropStore.select(context, option) }
                        .semantics { contentDescription = "背景" + option.label + if (isSelected) "，已选中" else "" },
                )
                Text(option.label, style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) InkTone.vermilion else InkTone.soft)
            }
        }
    }
}
