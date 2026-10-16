package com.planlock.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 墨序 Ink & Order —— UI 设计规范 v3.0 的唯一色值与动效来源。
 * 浅色是「宣纸」，深色是「夜墨」；四枚印色各有固定语义：
 * 朱 = 此刻 / 进行中 / 品牌印，青 = 完成，金 = 数字与提醒，靛 = 未开始。
 */
object Ink {
    // 宣纸 · 浅色
    val paper = Color(0xFFF2EDE3)
    val paperRaised = Color(0xFFFAF7F1)
    val paperSunk = Color(0xFFEAE3D6)
    val paperDeep = Color(0xFFDDD4C3)
    val ink = Color(0xFF1A1D1B)
    val inkSoft = Color(0xFF5B5F59)
    val inkFaint = Color(0xFF8E8C84)
    val hairline = Color(0xFFD9D1C2)

    // 夜墨 · 深色
    val night = Color(0xFF0E1210)
    val nightRaised = Color(0xFF161B18)
    val nightSunk = Color(0xFF0A0D0B)
    val nightHigh = Color(0xFF1E2420)
    val nightHighest = Color(0xFF272E2A)
    val moon = Color(0xFFECE5D8)
    val moonSoft = Color(0xFFA9A69A)
    val moonFaint = Color(0xFF6F6E66)
    val nightHairline = Color(0xFF2B322E)

    // 四印 + 违
    val vermilion = Color(0xFFC8412B)
    val vermilionNight = Color(0xFFFF7D5E)
    val jade = Color(0xFF2E6B56)
    val jadeNight = Color(0xFF74C7A6)
    val brass = Color(0xFF9C6F22)
    val brassNight = Color(0xFFE2B765)
    val indigo = Color(0xFF3D5878)
    val indigoNight = Color(0xFF93B3D8)
    val crimson = Color(0xFFA3202A)
    val crimsonNight = Color(0xFFFFB0A8)
    val stone = Color(0xFF8A877F)

    /** 宋体系衬线：标题、数字；无衬线：正文；等宽：刻度与元信息。 */
    val display: FontFamily = FontFamily.Serif
    val meta: FontFamily = FontFamily.Monospace

    /** 「落笔」曲线：起笔快、收笔稳。 */
    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val revealMs = 460
    const val revealStepMs = 45
}

/** 随主题切换的色值：全部取自当前色板 [LocalInkPalette]。 */
object InkTone {
    val vermilion: Color @Composable get() = LocalInkPalette.current.accent
    val accent: Color @Composable get() = LocalInkPalette.current.accent
    val onAccent: Color @Composable get() = LocalInkPalette.current.onAccent
    val jade: Color @Composable get() = LocalInkPalette.current.jade
    val brass: Color @Composable get() = LocalInkPalette.current.brass
    val indigo: Color @Composable get() = LocalInkPalette.current.indigo
    val crimson: Color @Composable get() = LocalInkPalette.current.crimson
    val text: Color @Composable get() = LocalInkPalette.current.text
    val soft: Color @Composable get() = LocalInkPalette.current.soft
    val faint: Color @Composable get() = LocalInkPalette.current.faint
    val hairline: Color @Composable get() = LocalInkPalette.current.hairline
    val ground: Color @Composable get() = LocalInkPalette.current.ground
    val raised: Color @Composable get() = LocalInkPalette.current.raised
    val sunk: Color @Composable get() = LocalInkPalette.current.sunk
    val high: Color @Composable get() = LocalInkPalette.current.high
    val highest: Color @Composable get() = LocalInkPalette.current.highest
    /** 「墨块」：反相的强调面（浅色下是墨，深色下是抬升的夜色）。 */
    val slab: Color @Composable get() = LocalInkPalette.current.slab
    val onSlab: Color @Composable get() = LocalInkPalette.current.onSlab
    /** 「反白」：选中标记用（浅色下墨底纸字，深色下月白底墨字），保证两种主题都一眼可辨。 */
    val inverse: Color @Composable get() = LocalInkPalette.current.inverse
    val onInverse: Color @Composable get() = LocalInkPalette.current.onInverse
    /** 当前主题的标题 / 数字字体与元信息字体。 */
    val display: FontFamily @Composable get() = LocalInkPalette.current.display
    val metaFont: FontFamily @Composable get() = LocalInkPalette.current.meta
}

/** 文字样式随主题换字体（墨序 = 宋体系衬线 + 等宽；构成 = 无衬线；荧屏 = 全等宽）。 */
object InkText {
    val eyebrow: TextStyle @Composable get() = TextStyle(fontFamily = InkTone.metaFont, fontSize = 11.sp, lineHeight = 14.sp,
        letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
    val meta: TextStyle @Composable get() = TextStyle(fontFamily = InkTone.metaFont, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .4.sp)
    val masthead: TextStyle @Composable get() = TextStyle(fontFamily = InkTone.display, fontSize = 30.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
    val numeral: TextStyle @Composable get() = TextStyle(fontFamily = InkTone.display, fontSize = 30.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
}

/** 把写死衬线 / 等宽的旧样式（`V2Tokens.numeral*` 等）换成当前主题的字体。 */
@Composable
fun TextStyle.themed(): TextStyle = when (fontFamily) {
    FontFamily.Serif -> copy(fontFamily = InkTone.display)
    FontFamily.Monospace -> copy(fontFamily = InkTone.metaFont)
    else -> this
}

/** 首次出现时「落笔」：淡入 + 上移，按序号错峰；系统关闭动画时直接呈现。 */
@Composable
fun Modifier.inkReveal(index: Int = 0, distance: Dp = 18.dp): Modifier {
    if (LocalReducedMotion.current) return this
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played) {
            delay((index.coerceIn(0, 10) * Ink.revealStepMs).toLong())
            progress.animateTo(1f, tween(Ink.revealMs, easing = Ink.Easing))
            played = true
        }
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * distance.toPx()
    }
}

/** 页头（轻）：一行淡色小字 + 衬线大标题，右侧可放一个动作。没有分隔线、没有装饰点。 */
@Composable
fun InkMasthead(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (eyebrow.isNotBlank()) Text(eyebrow, style = InkText.meta, color = InkTone.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = InkText.masthead, color = InkTone.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = InkTone.soft, modifier = Modifier.padding(top = 2.dp)) }
        }
        trailing?.invoke()
    }
}

/** 圆形小动作钮（新建等）：反白实心，40dp。 */
@Composable
fun InkRoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(InkTone.inverse)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = InkTone.onInverse, modifier = Modifier.size(20.dp))
    }
}

/** 留白提示：没有内容时只说一句话，给一个文字动作。 */
@Composable
fun QuietHint(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = InkTone.faint)
        if (action != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = InkTone.vermilion)
            }
        }
    }
}

/** 发丝线，右端带一枚小朱印刻度。 */
@Composable
fun InkRule(modifier: Modifier = Modifier, accent: Boolean = true) {
    val line = InkTone.hairline
    val mark = InkTone.vermilion
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val y = size.height / 2
        drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        if (accent) drawRect(mark, Offset(size.width - 18.dp.toPx(), y - 1.dp.toPx()), Size(18.dp.toPx(), 2.dp.toPx()))
    }
}

/** 朱印：品牌与助理的唯一图形符号。 */
@Composable
fun InkSeal(glyph: String = "序", sealSize: Dp = 44.dp, modifier: Modifier = Modifier, color: Color = InkTone.vermilion) {
    Box(
        modifier.size(sealSize).clip(RoundedCornerShape(sealSize * .22f)).background(color)
            .border(1.dp, Color.White.copy(alpha = .22f), RoundedCornerShape(sealSize * .22f))
            .semantics { contentDescription = "知序" },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = InkTone.onAccent, fontFamily = InkTone.display, fontWeight = FontWeight.Black,
            fontSize = (sealSize.value * .5f).sp, lineHeight = (sealSize.value * .56f).sp)
    }
}

/** 呼吸点：用于「此刻 / 执行中」。 */
@Composable
fun InkPulseDot(color: Color = InkTone.vermilion, dotSize: Dp = 8.dp, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val halo = if (reduced) 0f else rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulse-halo"
    ).value
    Canvas(modifier.size(dotSize * 2.2f)) {
        val r = dotSize.toPx() / 2
        if (halo > 0f) drawCircle(color.copy(alpha = (1f - halo) * .45f), radius = r + r * 1.2f * halo)
        drawCircle(color, radius = r)
    }
}

/**
 * 单行自适应文字：放不下时逐级缩小（下限 [minSize]），不折行、不截断。
 * 用于窄屏 / 大字体下不应折成两行的短标签（日期行、小样名、助理理念句）。
 */
@Composable
fun FitText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, minSize: TextUnit = 8.sp) {
    androidx.compose.foundation.text.BasicText(
        text, modifier, style = style.copy(color = color), maxLines = 1,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = style.fontSize, stepSize = .5.sp),
    )
}

/** 全天日程细环的绘制参数（BUG-102 任务包 §3.2：线宽 2dp、不透明度 35%）。 */
object DialAllDayRing {
    const val WIDTH_DP = 2f
    const val ALPHA = .35f
}

/** 一段日晷弧：分钟为单位（0..1440）。 */
data class DialArc(val startMinute: Int, val endMinute: Int, val color: Color, val emphasized: Boolean = false)

/**
 * 日晷盘（v3.4 表盘质感）：
 * 浮起的纸盘（光从左上来，盘下一圈软影）+ 双线表圈 + 96 格刻度（刻 / 时 / 四正）+ 每三小时衬线数字；
 * 昼夜色带：子夜藤紫 → 正午琥珀的环形渐变，空盘也有层次；盘内一圈「太阳纹」细放射线；
 * 当日弧段按类别着色（进行中加粗带晕）；指针后拖一道渐隐朱色尾迹，尖端白天是日、夜里是月，并缓慢呼吸。
 * 中心内容由调用方提供。
 */
@Composable
fun SundialDial(
    arcs: List<DialArc>,
    nowMinute: Int,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 14.dp,
    /** BUG-102：当天有全天日程时，内圈画一道该日程类别色的细环（不占 24 小时弧段）。 */
    allDayRing: Color? = null,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val sweep = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) { if (!reduced) sweep.animateTo(1f, tween(1100, easing = Ink.Easing)) else sweep.snapTo(1f) }
    val breathe = if (reduced) 0f else rememberInfiniteTransition(label = "dial").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "dial-breathe"
    ).value
    val measurer = rememberTextMeasurer()
    val dark = LocalDarkTheme.current
    val ink = InkTone.text
    val tick = InkTone.faint
    val needle = InkTone.vermilion
    val faceHi = InkTone.raised
    val faceLo = InkTone.sunk
    val hairline = InkTone.hairline
    val dayTint = InkHue.amber.color()
    val nightTint = InkHue.sleep.color()
    val bandAlpha = if (dark) .26f else .20f
    val band = remember(dayTint, nightTint, bandAlpha) {
        List(25) { h ->
            val f = (1f - cos(h / 24f * 2f * PI.toFloat())) / 2f
            androidx.compose.ui.graphics.lerp(nightTint, dayTint, f).copy(alpha = bandAlpha)
        }
    }
    val minorStyle = InkText.numeral.copy(fontSize = 10.sp, lineHeight = 12.sp, color = InkTone.soft)
    val majorStyle = InkText.numeral.copy(fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = ringWidth.toPx()
            val outer = size.minDimension / 2f
            val faceR = outer - 2.dp.toPx()
            val r = outer - w / 2f - 18.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)
            val p = sweep.value
            val ringBox = Offset(c.x - r, c.y - r)
            val ringSize = Size(r * 2, r * 2)

            // 盘下软影 + 浮起的纸盘
            val shadowC = c + Offset(0f, 6.dp.toPx())
            drawCircle(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = if (dark) .45f else .12f),
                    .90f to Color.Black.copy(alpha = if (dark) .45f else .12f),
                    1f to Color.Transparent,
                    center = shadowC, radius = faceR * 1.07f,
                ),
                radius = faceR * 1.07f, center = shadowC,
            )
            drawCircle(
                Brush.radialGradient(listOf(faceHi, faceHi, faceLo), center = c + Offset(-faceR * .32f, -faceR * .38f), radius = faceR * 1.55f),
                radius = faceR, center = c,
            )
            // 双线表圈
            drawCircle(hairline, faceR, c, style = Stroke(1.dp.toPx()))
            drawCircle(hairline.copy(alpha = .7f), faceR - 4.dp.toPx(), c, style = Stroke(.75.dp.toPx()))

            // 昼夜色带（槽）：从 0 点（顶部）顺时针，子夜藤紫 → 正午琥珀
            rotate(-90f, c) { drawCircle(Brush.sweepGradient(band, c), radius = r, center = c, style = Stroke(w)) }
            drawCircle(hairline.copy(alpha = .8f), r + w / 2f, c, style = Stroke(.75.dp.toPx()))
            drawCircle(hairline.copy(alpha = .8f), r - w / 2f, c, style = Stroke(.75.dp.toPx()))

            // 刻度：每刻一短刻、每时一中刻、四正一长刻
            val r1 = r + w / 2f + 3.dp.toPx()
            for (q in 0 until 96) {
                val a = angleOf(q * 15)
                val hour = q % 4 == 0
                val major = q % 24 == 0
                val len = when { major -> 8.dp; hour -> 5.dp; else -> 2.5.dp }.toPx()
                val col = when { major -> ink.copy(alpha = .85f); hour -> tick.copy(alpha = .75f); else -> tick.copy(alpha = .35f) }
                val sw = when { major -> 1.6.dp; hour -> 1.dp; else -> .7.dp }.toPx()
                drawLine(col, polar(c, r1, a), polar(c, r1 + len, a), sw)
            }
            // 每三小时一个数字，四正加粗
            // 盘面数字是表盘图形的一部分：按盘径缩放（以 264dp 盘为 1），不随系统字号放大
            val unit = outer / 132.dp.toPx()
            val majorFixed = majorStyle.copy(fontSize = (13.dp * unit).toSp(), lineHeight = (15.dp * unit).toSp())
            val minorFixed = minorStyle.copy(fontSize = (10.dp * unit).toSp(), lineHeight = (12.dp * unit).toSp())
            for (h in 0 until 24 step 3) {
                val layout = measurer.measure(h.toString(), if (h % 6 == 0) majorFixed else minorFixed)
                val pos = polar(c, r - w / 2f - 14.dp.toPx(), angleOf(h * 60))
                drawText(layout, topLeft = Offset(pos.x - layout.size.width / 2f, pos.y - layout.size.height / 2f))
            }
            // 盘心外圈的太阳纹：细放射线 + 一圈点线
            val rIn = r - w / 2f - 28.dp.toPx()
            val sun = ink.copy(alpha = if (dark) .07f else .06f)
            for (k in 0 until 144) {
                val a = k * 2.5f
                drawLine(sun, polar(c, rIn * .66f, a), polar(c, rIn, a), .6.dp.toPx())
            }
            for (k in 0 until 72) drawCircle(tick.copy(alpha = .35f), .8.dp.toPx(), polar(c, rIn + 3.dp.toPx(), k * 5f))
            // 全天日程：盘内一圈细环，压在点线圈上；层级在弧段与指针之下
            allDayRing?.let { ring ->
                drawCircle(ring.copy(alpha = DialAllDayRing.ALPHA * p), rIn + 3.dp.toPx(), c, style = Stroke(DialAllDayRing.WIDTH_DP.dp.toPx()))
            }

            // 弧段（按进场进度截断）；进行中加粗并带一层晕
            val limit = p * 1440f
            arcs.sortedBy { it.emphasized }.forEach { arc ->
                val s = arc.startMinute.coerceIn(0, 1440).toFloat()
                val e = arc.endMinute.coerceIn(0, 1440).toFloat().coerceAtLeast(s + 6f)
                if (s >= limit) return@forEach
                val sweepDeg = (minOf(e, limit) - s) / 1440f * 360f
                if (arc.emphasized) drawArc(arc.color.copy(alpha = .16f), angleOf(s.toInt()), sweepDeg, false, ringBox, ringSize,
                    style = Stroke(w * 1.55f, cap = StrokeCap.Butt))
                drawArc(arc.color, angleOf(s.toInt()), sweepDeg, false, ringBox, ringSize,
                    style = Stroke(if (arc.emphasized) w * 1.15f else w * .72f, cap = StrokeCap.Butt))
            }

            // 已流逝：槽内侧一道渐隐尾迹（越近此刻越浓）
            val elapsedSweep = nowMinute.coerceIn(0, 1440) / 1440f * 360f * p
            if (elapsedSweep > 1f) {
                val tr = r - w / 2f - 4.dp.toPx()
                val frac = elapsedSweep / 360f
                rotate(-90f, c) {
                    drawArc(
                        Brush.sweepGradient(0f to Color.Transparent, frac to needle.copy(alpha = .75f), (frac + .001f).coerceAtMost(1f) to Color.Transparent, center = c),
                        0f, elapsedSweep, false, Offset(c.x - tr, c.y - tr), Size(tr * 2, tr * 2),
                        style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Butt),
                    )
                }
            }
            // 指针 + 尾端小轴 + 尖端日 / 月
            val na = -90f + elapsedSweep
            val tail = polar(c, r - w * 1.5f, na)
            val tip = polar(c, r + w / 2f + 4.dp.toPx(), na)
            drawLine(needle, tail, tip, 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(needle, 2.5.dp.toPx(), tail)
            if (breathe > 0f) drawCircle(needle.copy(alpha = (1f - breathe) * .35f), radius = 6.dp.toPx() + 10.dp.toPx() * breathe, center = tip)
            val daytime = nowMinute in 360 until 1080
            if (daytime) {
                drawCircle(needle, 4.dp.toPx(), tip)
                for (k in 0 until 8) {
                    val a = k * 45f + na
                    drawLine(needle, polar(tip, 6.dp.toPx(), a), polar(tip, 8.5.dp.toPx(), a), 1.2.dp.toPx(), cap = StrokeCap.Round)
                }
            } else {
                val m = 5.dp.toPx()
                val disc = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(tip, m)) }
                val bite = androidx.compose.ui.graphics.Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(tip + Offset(2.6.dp.toPx(), -2.2.dp.toPx()), m * .86f))
                }
                val crescent = androidx.compose.ui.graphics.Path().apply { op(disc, bite, androidx.compose.ui.graphics.PathOperation.Difference) }
                drawCircle(faceHi, m + 1.5.dp.toPx(), tip)
                drawPath(crescent, needle)
            }
        }
        center()
    }
}

/** 十二时辰：「丑正三刻」式的时刻名（子时 23:00 起，单数点为初、双数点为正，每 15 分一刻）。 */
fun shichenLabel(minuteOfDay: Int): String {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    val hour = m / 60
    val branch = "子丑寅卯辰巳午未申酉戌亥"[((hour + 1) / 2) % 12]
    val half = if (hour % 2 == 1) "初" else "正"
    val ke = when ((m % 60) / 15) { 1 -> "一刻"; 2 -> "二刻"; 3 -> "三刻"; else -> "" }
    return "$branch$half$ke"
}

/** 圆环进度（执行卡、报告完成率）：朱色弧 + 绕行秒点。 */
@Composable
fun InkRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = InkTone.vermilion,
    track: Color = InkTone.hairline,
    stroke: Dp = 6.dp,
    orbiting: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(progress, reduced) {
        val target = progress.coerceIn(0f, 1f)
        if (reduced) shown.snapTo(target) else shown.animateTo(target, tween(900, easing = Ink.Easing))
    }
    val orbit = if (!orbiting || reduced) -1f else rememberInfiniteTransition(label = "orbit").animateFloat(
        0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "orbit-angle"
    ).value
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val r = size.minDimension / 2f - s
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(track, r, c, style = Stroke(s))
            drawArc(color, -90f, 360f * shown.value, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2),
                style = Stroke(s, cap = StrokeCap.Round))
            if (orbit >= 0f) rotate(orbit, c) { drawCircle(color, s * .55f, Offset(c.x, c.y - r - s * 1.6f)) }
        }
        content()
    }
}

/** 圆圈（ensō）插画：空态的一笔，错误态带一枚违印。 */
@Composable
fun EnsoMark(isError: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val draw = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) draw.animateTo(1f, tween(900, easing = Ink.Easing)) }
    val ink = InkTone.text
    val seal = if (isError) InkTone.crimson else InkTone.vermilion
    Canvas(modifier.size(92.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * .36f
        drawEnso(ink, c, r, draw.value)
        val sealSize = size.minDimension * .2f
        drawRoundRect(seal, Offset(c.x + r * .55f, c.y + r * .45f), Size(sealSize, sealSize),
            androidx.compose.ui.geometry.CornerRadius(sealSize * .22f))
    }
}

private fun DrawScope.drawEnso(color: Color, c: Offset, r: Float, progress: Float) {
    // 起笔粗、收笔细：分段画出笔锋
    val segments = 36
    val total = 320f * progress
    for (i in 0 until segments) {
        val f0 = i / segments.toFloat()
        val start = -70f + total * f0
        val sweep = total / segments + .6f
        val width = (7.5f - 5.5f * f0).dp.toPx()
        drawArc(color.copy(alpha = .92f - .35f * f0), start, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2),
            style = Stroke(width, cap = StrokeCap.Round))
    }
}

internal fun angleOf(minute: Int): Float = minute / 1440f * 360f - 90f

internal fun polar(c: Offset, r: Float, angleDeg: Float): Offset {
    val rad = angleDeg / 180f * PI.toFloat()
    return Offset(c.x + r * cos(rad), c.y + r * sin(rad))
}

/** 墨块分段：选中的墨块以弹簧滑动到目标项。用于两到四个互斥视图的切换。 */
@Composable
fun <T> InkSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().height(40.dp)
            .background(InkTone.sunk, RoundedCornerShape(12.dp))
            .padding(3.dp)
    ) {
        val slot = maxWidth / options.size.coerceAtLeast(1)
        val x by androidx.compose.animation.core.animateDpAsState(
            slot * index,
            if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .7f, stiffness = 500f),
            label = "segment-indicator",
        )
        Box(Modifier.offset(x = x).width(slot).fillMaxHeight().background(InkTone.inverse, RoundedCornerShape(9.dp)))
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val color by androidx.compose.animation.animateColorAsState(if (isSelected) InkTone.onInverse else InkTone.soft, label = "segment-label")
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelect(value) }
                        .semantics { contentDescription = label + if (isSelected) "，已选中" else "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 主题选择：三张小样纸——跟随系统（半纸半墨）、宣纸、夜墨。选中者描朱边。 */
@Composable
fun ThemeSwatches(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val border = if (isSelected) InkTone.vermilion else InkTone.hairline
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .border(if (isSelected) 2.dp else 1.dp, border, RoundedCornerShape(14.dp))
                    .clickable { onSelect(mode) }
                    .semantics { contentDescription = mode.label + if (isSelected) "，已选中" else "" }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(8.dp))) {
                    fun page(bg: Color, ink: Color, left: Float, width: Float) {
                        drawRect(bg, Offset(left, 0f), Size(width, size.height))
                        for (i in 0..3) {
                            val y = size.height * (.3f + i * .17f)
                            drawLine(ink.copy(alpha = .25f), Offset(left + 8.dp.toPx(), y), Offset(left + width - 8.dp.toPx(), y), 1.dp.toPx())
                        }
                    }
                    when (mode) {
                        ThemeMode.SYSTEM -> {
                            page(Ink.paper, Ink.ink, 0f, size.width / 2)
                            page(Ink.night, Ink.moon, size.width / 2, size.width / 2)
                        }
                        ThemeMode.LIGHT -> page(Ink.paper, Ink.ink, 0f, size.width)
                        ThemeMode.DARK -> page(Ink.night, Ink.moon, 0f, size.width)
                    }
                    val seal = 14.dp.toPx()
                    drawRoundRect(if (mode == ThemeMode.DARK) Ink.vermilionNight else Ink.vermilion,
                        Offset(8.dp.toPx(), 8.dp.toPx()), Size(seal, seal), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                }
                FitText(
                    when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "宣纸 · 浅"; ThemeMode.DARK -> "夜墨 · 深" },
                    MaterialTheme.typography.labelLarge, if (isSelected) InkTone.vermilion else InkTone.text,
                )
            }
        }
    }
}

/**
 * 日带：一根 24 小时细线。安排是线上的短墨段（进行中为朱），此刻是一枚朱点。
 * 取代体量很大的日晷，作为今日页唯一的「一天全貌」。
 */
@Composable
fun DayRibbon(arcs: List<DialArc>, nowMinute: Int?, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val sweep = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) { if (!reduced) sweep.animateTo(1f, tween(900, easing = Ink.Easing)) else sweep.snapTo(1f) }
    val measurer = rememberTextMeasurer()
    val line = InkTone.hairline
    val labelStyle = InkText.meta.copy(fontSize = 9.sp, color = InkTone.faint)
    val now = InkTone.vermilion
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        val y = 12.dp.toPx()
        val w = size.width
        drawLine(line, Offset(0f, y), Offset(w, y), 1.dp.toPx())
        listOf(0, 6, 12, 18, 24).forEach { h ->
            val x = w * h / 24f
            drawLine(line, Offset(x, y - 3.dp.toPx()), Offset(x, y + 3.dp.toPx()), 1.dp.toPx())
            val layout = measurer.measure(if (h == 24) "24" else "$h", labelStyle)
            val lx = (x - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
            drawText(layout, topLeft = Offset(lx, y + 8.dp.toPx()))
        }
        val limit = 1440f * sweep.value
        arcs.forEach { arc ->
            val s = arc.startMinute.coerceIn(0, 1440).toFloat()
            if (s > limit) return@forEach
            val e = minOf(arc.endMinute.coerceIn(0, 1440).toFloat().coerceAtLeast(s + 10f), limit)
            val h = if (arc.emphasized) 8.dp.toPx() else 5.dp.toPx()
            drawRoundRect(arc.color, Offset(w * s / 1440f, y - h / 2), Size(w * (e - s) / 1440f, h),
                androidx.compose.ui.geometry.CornerRadius(h / 2))
        }
        nowMinute?.let { m ->
            val x = w * m.coerceIn(0, 1440) / 1440f
            drawCircle(now, 4.5.dp.toPx(), Offset(x, y))
            drawCircle(now.copy(alpha = .2f), 9.dp.toPx(), Offset(x, y))
        }
    }
}

/**
 * 类别色（v3.2）：给「内容」上色，与状态印色分工——状态用朱/青/金/靛，类别用下面这组。
 * 计划类型固定映射；未知类型按名称稳定散列到扩展色，同名永远同色。
 */
object InkHue {
    data class Hue(val light: Color, val dark: Color)
    val study = Hue(Color(0xFF3F5FA0), Color(0xFF9DB4EA))     // 学习 · 靛蓝
    val work = Hue(Color(0xFF23727F), Color(0xFF7CCAD6))      // 工作 · 黛青
    val sport = Hue(Color(0xFF4E8A3A), Color(0xFFA6D68F))     // 运动 · 草绿
    val sleep = Hue(Color(0xFF6E58A0), Color(0xFFB9A6E6))     // 睡眠 · 藤紫
    val rose = Hue(Color(0xFFB44A6A), Color(0xFFF0A0B8))      // 桃
    val amber = Hue(Color(0xFFA9701E), Color(0xFFE8BC6A))     // 琥珀（日程）
    val seal = Hue(Ink.vermilion, Ink.vermilionNight)          // 朱（进行中）
    private val extras = listOf(rose, amber, work, study, sleep, sport)

    fun forType(type: String?): Hue = when (type?.trim()) {
        "学习" -> study
        "工作" -> work
        "运动" -> sport
        "睡眠" -> sleep
        null, "" -> study
        else -> extras[(type.trim().hashCode() and 0x7fffffff) % extras.size]
    }
}

@Composable
fun InkHue.Hue.color(): Color {
    val palette = LocalInkPalette.current
    if (this === InkHue.seal) return palette.accent
    palette.cats?.let { c ->
        when {
            this === InkHue.study -> return c.study
            this === InkHue.work -> return c.work
            this === InkHue.sport -> return c.sport
            this === InkHue.sleep -> return c.sleep
            this === InkHue.rose -> return c.rose
            this === InkHue.amber -> return c.amber
        }
    }
    return if (palette.isDark) dark else light
}

/** 类别色的「底」：浅色 10%、深色 18% 的同色纸，用来区分一张张内容卡。 */
@Composable
fun InkHue.Hue.tint(): Color = color().copy(alpha = if (LocalDarkTheme.current) .18f else .10f)

@Composable
fun Color.inkTint(): Color = copy(alpha = if (LocalDarkTheme.current) .18f else .10f)

/** 助理的名字：知行——「知你所想，序你所行」的知与行。 */
object AssistantIdentity {
    const val NAME = "知行"
    const val TAGLINE = "知你所想 · 序你所行"
}

/**
 * 知行的标识：朱→琥珀渐变的圆角方块里，一点（知：一个念头）落在一道弧（行：一条路）之上。
 * 不用汉字、不用星芒——避免「又一个 AI 图标」。active 时那一点会沿弧轻轻起伏。
 */
@Composable
fun AssistantMark(markSize: Dp = 40.dp, modifier: Modifier = Modifier, active: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val bob = if (!active || reduced) 0f else rememberInfiniteTransition(label = "mark").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mark-bob"
    ).value
    val dark = LocalDarkTheme.current
    val top = InkTone.accent
    // 墨序保留品牌的朱→琥珀；其他主题由强调色过渡到该主题的金色
    val bottom = if (ThemeStyleStore.current == ThemeStyle.INK) (if (dark) Color(0xFFE8BC6A) else Color(0xFFD9822B)) else InkTone.brass
    Canvas(modifier.size(markSize).semantics { contentDescription = AssistantIdentity.NAME }) {
        val s = size.minDimension
        drawRoundRect(
            brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(top, bottom), Offset(0f, 0f), Offset(s, s)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * .3f),
        )
        val white = Color(0xFFFFF8F0)
        // 行：一道向右上扬起的弧
        drawArc(white, startAngle = 160f, sweepAngle = 150f, useCenter = false,
            topLeft = Offset(s * .22f, s * .40f), size = Size(s * .56f, s * .56f),
            style = Stroke(s * .085f, cap = StrokeCap.Round))
        // 知：弧上方的一点
        drawCircle(white, radius = s * .1f, center = Offset(s * .5f, s * (.33f - .04f * bob)))
    }
}

/** 新建（右下角，悬在导航之上）：朱色圆角方钮，按下回弹。 */
@Composable
fun InkFab(onClick: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by androidx.compose.animation.core.animateFloatAsState(if (pressed) .9f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = .5f, stiffness = 600f), label = "fab-press")
    val accent = InkTone.vermilion
    Box(
        modifier.size(56.dp).graphicsLayer { scaleX = press; scaleY = press }
            .shadow(12.dp, RoundedCornerShape(18.dp), ambientColor = accent, spotColor = accent)
            .clip(RoundedCornerShape(18.dp)).background(accent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Add, null,
            tint = InkTone.onAccent, modifier = Modifier.size(26.dp))
    }
}
