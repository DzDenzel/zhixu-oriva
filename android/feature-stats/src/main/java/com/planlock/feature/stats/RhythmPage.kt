package com.planlock.feature.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planlock.app.api.PlanTypeDefinition
import com.planlock.app.api.PlanTypes
import com.planlock.app.api.dto.Distraction
import com.planlock.app.api.dto.PlanPerformance
import com.planlock.app.api.dto.RhythmSummary
import com.planlock.app.record.OccurrenceDate
import com.planlock.design.InkHue
import com.planlock.design.InkSegmented
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.LocalReducedMotion
import com.planlock.design.color

/*
 * 统计页「我的节奏」（v4.3，U3 方向 C 精简版，负责人 2026-10-19 20:57 定稿）：
 * 完成率卡（环 + 一句话 + 三个小指标）→ 每天完成情况（周 / 月柱状图，可翻页，点一天看明细）→ 各计划 → 分心 → 导出。
 * 同一事实只出现一次；「知行对你的了解」（v2.1.2）放在分心之后、导出之前，紧凑提醒式小条。
 */

private val CardShape = RoundedCornerShape(18.dp)

@Composable
private fun RhythmCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(CardShape).background(InkTone.raised).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun CardTitle(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = InkTone.text, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = InkText.caption, color = InkTone.soft) }
    }
}

/** F1 + F2：完成率环、一句话总结、连续达成 / 专注 / 提前结束。 */
@Composable
internal fun RhythmCompletionCard(summary: RhythmSummary) {
    val rate = summary.completionRate7d
    val reduced = LocalReducedMotion.current
    val sweep by animateFloatAsState(if (rate.total == 0) 0f else rate.percent / 100f,
        if (reduced) tween(0) else tween(600, easing = com.planlock.design.Ink.Easing), label = "rate-ring")
    val jade = InkTone.jade
    val track = InkTone.sunk
    RhythmCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(92.dp).semantics(mergeDescendants = true) {
                contentDescription = if (rate.total == 0) "近 7 天还没有可计入的计划" else "近 7 天完成率 ${rate.percent}%"
            }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 10.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    if (sweep > 0f) drawArc(jade, -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (rate.total == 0) "—" else "${rate.percent}", fontFamily = InkTone.display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = InkTone.text)
                    if (rate.total > 0) Text("%", style = InkText.caption, color = InkTone.soft, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // v4.6（真机）：一句长句在大字号手机上折成两行；改成「小标签 + 一行大数字」，数字按宽缩字不折行
                Text(if (rate.total == 0) "完成率" else "近7天完成", style = InkText.caption, color = InkTone.soft)
                if (rate.total == 0) {
                    Text(rhythmHeroLine(rate), style = MaterialTheme.typography.bodyMedium, color = InkTone.text)
                } else {
                    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = rhythmHeroLine(rate) }, verticalAlignment = Alignment.Bottom) {
                        com.planlock.design.FitText("${rate.completed}/${rate.total}",
                            androidx.compose.ui.text.TextStyle(fontFamily = InkTone.display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp,
                                fontFeatureSettings = "tnum"), InkTone.text, Modifier.weight(1f, fill = false), minSize = 16.sp)
                        Text(" 次", style = InkText.caption, color = InkTone.soft, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    val delta = rateDelta(rate, summary.completionRatePrev7d)
                    Text(delta.text, style = InkText.caption, color = when (delta.better) { true -> InkHue.sport.color(); false -> InkHue.amber.color(); null -> InkTone.soft },
                        maxLines = 2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().drawBehind {
            drawLine(track, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        }.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // v4.6（真机）：标签去掉「30 天」，统一写在下面一行；变化写成「+1 分钟」短式，整句留给读屏
            SmallMetric("连续达成", "${summary.streakDays}", "天", Delta(if (summary.streakDays > 0) "到今天" else "完成一个即开始", null), Modifier.weight(1f))
            SmallMetric("专注", minutesNumber(summary.focusMinutes30d), minutesUnit(summary.focusMinutes30d),
                focusDelta(summary.focusMinutes30d, summary.focusMinutesPrev30d), Modifier.weight(1f))
            SmallMetric("提前结束", "${summary.earlyEnds30d}", "次", earlyDelta(summary.earlyEnds30d, summary.earlyEndsPrev30d), Modifier.weight(1f))
        }
        Text("专注与提前结束按近30天计，与再前30天比较", style = InkText.caption, color = InkTone.soft,
            modifier = Modifier.padding(top = 8.dp))
    }
}

private fun minutesNumber(m: Int): String = if (m < 60) "$m" else "%.1f".format(java.util.Locale.ROOT, m / 60.0).removeSuffix(".0")
private fun minutesUnit(m: Int): String = if (m < 60) "分钟" else "小时"

@Composable
private fun SmallMetric(label: String, value: String, unit: String, delta: Delta, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label $value $unit，${delta.text}" }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // v4.5（U-a）：窄屏 + 大字体时标签允许折两行，不截成「提前结束…」
        Text(label, style = InkText.caption, color = InkTone.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = InkTone.display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = InkTone.text)
            Text(" $unit", style = InkText.caption, color = InkTone.soft, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(delta.short, style = InkText.caption.copy(fontWeight = if (delta.better != null) FontWeight.SemiBold else FontWeight.Normal),
            color = when (delta.better) { true -> InkTone.jade; false -> InkTone.brass; null -> InkTone.soft }, maxLines = 2)
    }
}

/** F3：每天完成情况。周 / 月标签、左右翻页、点一天看明细。 */
@Composable
internal fun RhythmChartCard(summary: RhythmSummary, today: OccurrenceDate) {
    var monthly by rememberSaveable { mutableStateOf(false) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var pickedIso by rememberSaveable { mutableStateOf<String?>(null) }
    val range = if (monthly) RhythmRange.MONTH else RhythmRange.WEEK
    val period = remember(summary.days, range, offset, today) { rhythmPeriod(range, offset, summary.days, today) }
    val label = rhythmRangeLabel(period, today)
    val picked = period.bars.firstOrNull { it.date.iso() == pickedIso } ?: period.bars.firstOrNull { it.isToday }
        ?: period.bars.lastOrNull { !it.future && it.cell != null }
    RhythmCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InkSegmented(listOf("week" to "周", "month" to "月"), if (monthly) "month" else "week",
                { monthly = it == "month"; offset = 0; pickedIso = null }, Modifier.width(112.dp))
            // v4.4：翻页区占满剩余宽度，日期在两箭头之间按宽度缩字（真机默认字号大时「10 月 12 日 – 18 日」曾超出卡片）
            IconButton(onClick = { offset -= 1; pickedIso = null }, enabled = period.canGoBack, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, if (monthly) "上个月" else "上一周",
                    tint = if (period.canGoBack) InkTone.text else InkTone.hairline)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                com.planlock.design.FitText(label, MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center, fontFeatureSettings = "tnum"),
                    InkTone.text, minSize = 10.sp)
            }
            IconButton(onClick = { offset += 1; pickedIso = null }, enabled = period.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, if (monthly) "下个月" else "下一周",
                    tint = if (period.canGoForward) InkTone.text else InkTone.hairline)
            }
        }
        Text(rhythmPeriodSummary(period, label), style = MaterialTheme.typography.titleMedium, color = InkTone.text)
        // v4.4：整段时间一次都没安排时，只留上面一句，不画空图、图例与当天明细（避免三处说同一件事）
        if (period.counted + period.upcoming > 0) {
            RhythmBars(period, picked) { pickedIso = it.date.iso() }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(InkTone.jade, "完成"); LegendDot(InkTone.brass, "提前结束"); LegendDot(InkTone.hairline, "未执行")
            }
            picked?.let { Text(rhythmDayLine(it), style = MaterialTheme.typography.bodyMedium, color = InkTone.text) }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = InkText.caption, color = InkTone.soft)
    }
}

@Composable
private fun RhythmBars(period: RhythmPeriod, picked: RhythmBar?, onPick: (RhythmBar) -> Unit) {
    val weekly = period.range == RhythmRange.WEEK
    val gap = if (weekly) 10.dp else 3.dp
    val chartHeight = 112.dp
    val jade = InkTone.jade; val brass = InkTone.brass; val hair = InkTone.hairline; val soft = InkTone.soft
    val text = InkTone.text; val empty = InkTone.sunk
    val avg = period.averageCompleted
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(chartHeight).drawBehind {
                if (avg > 0) {
                    val y = size.height - (avg / period.maxHeight * size.height).toFloat()
                    drawLine(soft, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                }
            },
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.Bottom,
        ) {
            period.bars.forEach { bar ->
                val selected = picked?.date == bar.date
                val clickable = !bar.future
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .then(if (clickable) Modifier.clickable { onPick(bar) } else Modifier)
                        .semantics {
                            contentDescription = rhythmDayLine(bar)
                            if (clickable) { role = Role.Button; this.selected = selected }
                        }
                        .drawBehind {
                            val unit = size.height / period.maxHeight
                            val r = (if (weekly) 6.dp else 3.dp).toPx()
                            if (bar.cell == null || bar.height == 0) {
                                // 没有记录 / 未来：一条贴底的浅线，不画柱
                                val h = 3.dp.toPx()
                                drawRoundRect(empty, Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h), CornerRadius(h / 2))
                            } else {
                                var bottom = size.height
                                fun seg(count: Int, color: Color) {
                                    if (count <= 0) return
                                    val h = count * unit
                                    drawRect(color, Offset(0f, bottom - h), androidx.compose.ui.geometry.Size(size.width, h))
                                    bottom -= h
                                }
                                seg(bar.done, jade); seg(bar.early, brass); seg(bar.missed, hair)
                                if (bar.upcoming > 0) {
                                    val h = bar.upcoming * unit
                                    drawRoundRect(soft, Offset(0f, bottom - h), androidx.compose.ui.geometry.Size(size.width, h), CornerRadius(r),
                                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
                                }
                            }
                            // v4.4：选中框只框住柱子本身；空的一天只把底线加深（真机空数据时整格高的空框很怪）
                            if (selected) {
                                val pad = 2.dp.toPx()
                                if (bar.cell == null || bar.height == 0) {
                                    val h = 4.dp.toPx()
                                    drawRoundRect(text, Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h), CornerRadius(h / 2))
                                } else {
                                    val top = (size.height - bar.height * unit - pad).coerceAtLeast(0f)
                                    drawRoundRect(text, Offset(-pad, top), androidx.compose.ui.geometry.Size(size.width + 2 * pad, size.height - top),
                                        CornerRadius(r), style = Stroke(1.5.dp.toPx()))
                                }
                            }
                        },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            period.bars.forEach { bar ->
                val tick = when {
                    weekly -> if (bar.isToday) "今天" else weekdayLabel(bar.date).removePrefix("周")
                    bar.date.day in setOf(1, 8, 15, 22, 29) -> "${bar.date.day}"
                    else -> ""
                }
                Text(tick, style = InkText.caption.copy(fontSize = if (weekly) 12.sp else 10.sp,
                    fontWeight = if (bar.isToday) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (bar.isToday) InkTone.accent else InkTone.soft, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    // 月视图每格只有几 dp 宽：让刻度文字越出本格、以柱子为中心显示，不被截成「1!」「2:」
                    modifier = Modifier.weight(1f).wrapContentWidth(unbounded = true))
            }
        }
    }
}

/** F4：各计划近 30 天完成率（已按完成率升序）。点进计划详情。 */
@Composable
internal fun RhythmPlansCard(plans: List<PlanPerformance>, custom: List<PlanTypeDefinition>, onOpenPlan: (Long) -> Unit) {
    RhythmCard {
        CardTitle("各计划", "近 30 天")
        plans.forEach { plan ->
            val hue = hueOf(PlanTypes.definition(plan.type, custom).color)
            val rate = plan.completionRate30d
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpenPlan(plan.planId) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${plan.name}，近 30 天完成 ${plan.completed} / ${plan.total} 次" + if (rate.total > 0) "，${rate.percent}%" else ""
                    },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(plan.name, style = MaterialTheme.typography.bodyLarge, color = InkTone.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(112.dp))
                ProgressTrack(if (rate.total == 0) 0f else rate.percent / 100f, hue.color(), Modifier.weight(1f))
                Text(if (rate.total == 0) "—" else "${rate.percent}%", style = InkText.caption.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = if (rate.total > 0 && rate.percent < 50) InkTone.brass else InkTone.text, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 40.dp))
            }
        }
    }
}

/** F5：分心（无行为证据时整块不出现）。 */
@Composable
internal fun RhythmDistractionCard(distraction: Distraction) {
    val max = distraction.topSources.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
    RhythmCard {
        CardTitle("分心", "近 7 天共 ${minutesLabel(distraction.minutes7d)}")
        distraction.topSources.take(3).forEach { source ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(source.appName, style = MaterialTheme.typography.bodyLarge, color = InkTone.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(112.dp))
                ProgressTrack(source.minutes.toFloat() / max, InkTone.accent, Modifier.weight(1f))
                Text(minutesLabel(source.minutes), style = InkText.caption, color = InkTone.soft, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 52.dp))
            }
        }
    }
}

@Composable
private fun ProgressTrack(fraction: Float, color: Color, modifier: Modifier) {
    val track = InkTone.sunk
    Box(modifier.height(6.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(color))
    }
}

/** F6：导出，一行小字按钮。 */
@Composable
internal fun RhythmExportRow(busy: Boolean, onIcs: () -> Unit, onCsv: () -> Unit, onJson: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (busy) "导出中…" else "导出", style = InkText.caption, color = InkTone.soft, modifier = Modifier.padding(start = 4.dp, end = 4.dp))
        TextButton(onClick = onIcs, enabled = !busy) { Text("日历 ICS") }
        TextButton(onClick = onCsv, enabled = !busy) { Text("表格 CSV") }
        TextButton(onClick = onJson, enabled = !busy) { Text("完整 JSON") }
    }
}

internal fun hueOf(colorKey: String): InkHue.Hue = when (colorKey) {
    "study" -> InkHue.study
    "work" -> InkHue.work
    "sport" -> InkHue.sport
    "sleep" -> InkHue.sleep
    "rose" -> InkHue.rose
    "amber" -> InkHue.amber
    else -> InkHue.study
}
