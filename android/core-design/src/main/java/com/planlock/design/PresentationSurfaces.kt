package com.planlock.design

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import android.os.Build
import java.util.Locale
import java.util.TimeZone

/** Shared surface geometry; callers retain domain state and actions. */
@Composable
fun PresentationCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = InkTone.raised) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** 账页：小标题 + 逐行「标签 …… 值」。不画框，行间留白。 */
@Composable
fun InkLedger(title: String, rows: List<Pair<String, String>>, modifier: Modifier = Modifier, latin: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = InkTone.text)
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = InkTone.soft)
                Text(value, Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium, color = InkTone.text,
                    textAlign = TextAlign.End)
            }
        }
        footer?.invoke(this)
    }
}

/** 等宽选项格里的文字：居中、单行、放不下按宽缩到 10sp。 */
@Composable
fun EqualChipLabel(label: String) {
    FitText(label, MaterialTheme.typography.labelLarge.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        LocalContentColor.current, Modifier.fillMaxWidth(), minSize = 10.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    // v4.6（真机）：选项少且短（≤ 5 项、每项 ≤ 4 字，如「单次 每天 每周 每月 每年」）时排成一行等宽，
    // 放不下就缩字，不再在大字号手机上掉出第二行；选项多或长（关联计划名等）仍自动换行
    singleRow: Boolean = options.size in 2..5 && options.all { it.second.length <= 4 }) {
    val controls = LocalSecondaryControlColors.current
    if (singleRow) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) },
                    label = { EqualChipLabel(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = controls.container,
                        selectedLabelColor = controls.onContainer))
            }
        }
        return
    }
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) },
                label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = controls.container,
                    selectedLabelColor = controls.onContainer))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionHeading(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = InkTone.text)
        detail?.let { Text("  $it", style = InkText.caption, color = InkTone.soft, modifier = Modifier.padding(bottom = 2.dp)) }
    }
}

@Composable
fun PickerField(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { role = Role.Button }
        .clickable(onClick = onClick), shape = RoundedCornerShape(14.dp),
        color = InkTone.raised) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // v4.4：标签与数值同一字体族；标签用 soft（faint 对比度不足 4.5），数值用等宽数位，开始 / 结束两列对齐
                Text(title, style = InkText.eyebrow, color = InkTone.soft)
                // v4.6（真机）：大字号手机上「2026-10-20」曾折成两行；数值只占一行，放不下时按宽缩字
                FitText(value.ifBlank { "请选择" }, MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    LocalContentColor.current, minSize = 11.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "选择$title")
        }
    }
}

@Composable
fun DestructiveButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError)) { Text(label) }
}

private fun isoFormat(prolepticGregorian: Boolean) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        // Material uses java.time on API 26+, and the legacy cutover Calendar on API 24/25.
        if (prolepticGregorian) gregorianChange = Date(Long.MIN_VALUE)
    }
    isLenient = false
    timeZone = TimeZone.getTimeZone("UTC")
}

/** Convert UTC days using the active Material calendar model, never the device offset. */
fun datePickerUtcMillis(isoDate: String, prolepticGregorian: Boolean = true): Long? {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(isoDate)) return null
    val position = ParsePosition(0)
    val parsed = isoFormat(prolepticGregorian).parse(isoDate, position) ?: return null
    return parsed.time.takeIf { position.index == isoDate.length }
}

fun datePickerIsoDate(utcMillis: Long, prolepticGregorian: Boolean = true): String =
    isoFormat(prolepticGregorian).format(Date(utcMillis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDatePicker(title: String, initialIsoDate: String, onConfirm: (String) -> Unit,
    onDismiss: () -> Unit) {
    val prolepticGregorian = Build.VERSION.SDK_INT >= 26
    val state = rememberDatePickerState(initialSelectedDateMillis = datePickerUtcMillis(initialIsoDate, prolepticGregorian),
        yearRange = 1..9999)
    val configuration = LocalConfiguration.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 360.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.heightIn(max = (configuration.screenHeightDp - 64).coerceAtLeast(240).dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    // Material calendar cells have a 40dp visual container; the surrounding dialog
                    // must honor the available width rather than its default 360dp minimum.
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                        DatePicker(state, modifier = Modifier.fillMaxWidth(),
                            title = { Text(title, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.titleMedium) },
                            headline = { Text(state.selectedDateMillis?.let { datePickerIsoDate(it, prolepticGregorian) } ?: "请选择日期",
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.headlineSmall) }, showModeToggle = false)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                    TextButton(enabled = state.selectedDateMillis != null, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { state.selectedDateMillis?.let { onConfirm(datePickerIsoDate(it, prolepticGregorian)) } }) { Text("确定") }
                }
            }
        }
    }
}
