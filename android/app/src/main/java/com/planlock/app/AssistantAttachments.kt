package com.planlock.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planlock.design.Ink
import com.planlock.design.InkHue
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.color
import com.planlock.design.inkTint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 知行附件（CHANGE-054 / PRD §5.2 A7~A9）：图片与文件进入对话。
 * 本地模式只在本次对话里展示附件（名称、类型、大小、图片缩略），不读取内容、不上传；
 * AI 模式就绪后由个人后端读取、总结，并在用户确认后沉淀进个人档案。
 */
internal enum class AttachmentKind(val glyph: String, val label: String) {
    IMAGE("图", "图片"),
    PDF("PDF", "PDF"),
    DOC("文", "文档"),
    SHEET("表", "表格"),
    SLIDES("演", "演示"),
    TEXT("记", "文本"),
    OTHER("件", "文件"),
}

internal data class AssistantAttachment(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long?,
    val kind: AttachmentKind,
)

internal object AssistantAttachmentRules {
    const val MAX_COUNT = 9

    /** 文件选择器可选的类型：文档、表格、演示、文本与 Markdown。 */
    val documentTypes = arrayOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/*",
        "application/json",
        "image/*",
    )
}

internal fun attachmentKindOf(mime: String, name: String): AttachmentKind {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") -> AttachmentKind.IMAGE
        mime == "application/pdf" || ext == "pdf" -> AttachmentKind.PDF
        mime.contains("wordprocessing") || mime == "application/msword" || ext in setOf("doc", "docx", "odt", "rtf") -> AttachmentKind.DOC
        mime.contains("spreadsheet") || mime == "application/vnd.ms-excel" || ext in setOf("xls", "xlsx", "csv", "ods") -> AttachmentKind.SHEET
        mime.contains("presentation") || mime == "application/vnd.ms-powerpoint" || ext in setOf("ppt", "pptx", "odp") -> AttachmentKind.SLIDES
        mime.startsWith("text/") || mime == "application/json" || ext in setOf("txt", "md", "markdown", "json") -> AttachmentKind.TEXT
        else -> AttachmentKind.OTHER
    }
}

internal fun formatAttachmentSize(bytes: Long?): String = when {
    bytes == null || bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

internal fun Context.describeAttachment(uri: Uri): AssistantAttachment {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "附件"
    var size: Long? = null
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
    }
    val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
    return AssistantAttachment(uri, name, mime, size, attachmentKindOf(mime, name))
}

/** 本地模式对附件的如实回函。 */
internal fun attachmentNote(attachments: List<AssistantAttachment>, backendConnected: Boolean): String {
    val names = attachments.take(3).joinToString("、") { "「${it.name}」" } + if (attachments.size > 3) " 等" else ""
    val how = if (backendConnected) "在个人后端启用 AI 组件后" else "连接你的个人后端并启用 AI 后"
    return "收到 ${attachments.size} 个附件：$names。\n" +
        "读懂图片和文件、提炼要点、整理成文档，需要 AI 模式——${how}，我会读完它们，并在你确认后把有用的内容写进你的个人档案。\n" +
        "现在是本地模式：附件只留在这次对话里，不读取内容，也不上传。"
}

@Composable
internal fun attachmentHue(kind: AttachmentKind): Color = when (kind) {
    AttachmentKind.IMAGE -> InkHue.rose.color()
    AttachmentKind.PDF -> InkHue.seal.color()
    AttachmentKind.DOC -> InkHue.study.color()
    AttachmentKind.SHEET -> InkHue.sport.color()
    AttachmentKind.SLIDES -> InkHue.amber.color()
    AttachmentKind.TEXT -> InkHue.sleep.color()
    AttachmentKind.OTHER -> InkHue.work.color()
}

/** 图片缩略：按目标边长取样解码，IO 线程完成；失败时返回 null，由调用方显示类别方签。 */
@Composable
private fun rememberThumbnail(uri: Uri, edge: Dp): ImageBitmap? {
    val context = LocalContext.current
    val target = with(LocalDensity.current) { edge.roundToPx() }
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, target) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/** 类别方签：彩色圆角方块 + 单字（或 PDF）。 */
@Composable
private fun KindSeal(kind: AttachmentKind, edge: Dp) {
    val hue = attachmentHue(kind)
    Box(
        Modifier.size(edge).clip(RoundedCornerShape(edge * .28f)).background(hue.inkTint()),
        contentAlignment = Alignment.Center,
    ) {
        Text(kind.glyph, color = hue, fontFamily = InkTone.display, fontWeight = FontWeight.Bold,
            fontSize = if (kind.glyph.length > 1) (edge.value * .26f).sp else (edge.value * .42f).sp)
    }
}

/** 图片附件：圆角缩略图；读不到时退回为「图」方签。 */
@Composable
private fun ImageTile(attachment: AssistantAttachment, edge: Dp) {
    val thumb = rememberThumbnail(attachment.uri, edge)
    if (thumb != null) {
        Image(thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(edge).clip(RoundedCornerShape(12.dp)).border(1.dp, InkTone.hairline, RoundedCornerShape(12.dp)))
    } else KindSeal(AttachmentKind.IMAGE, edge)
}

/** 文件附件：方签 + 文件名 + 「类型 · 大小」。 */
@Composable
private fun FileTile(attachment: AssistantAttachment, onInverse: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (onInverse) InkTone.raised.copy(alpha = .14f) else InkTone.raised)
            .border(1.dp, if (onInverse) Color.Transparent else InkTone.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindSeal(attachment.kind, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(max = 150.dp)) {
            Text(attachment.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (onInverse) InkTone.onInverse else InkTone.text)
            Text(listOf(attachment.kind.label, formatAttachmentSize(attachment.size)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = InkText.meta.copy(fontSize = 11.sp), color = if (onInverse) InkTone.onInverse.copy(alpha = .7f) else InkTone.faint)
        }
    }
}

/** 输入台上方的待发附件：可逐个移除。 */
@Composable
internal fun AttachmentTray(attachments: List<AssistantAttachment>, onRemove: (AssistantAttachment) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        items(attachments, key = { it.uri.toString() }) { attachment ->
            Box(Modifier.semantics { contentDescription = "附件 ${attachment.name}" }) {
                Box(Modifier.padding(top = 6.dp, end = 6.dp)) {
                    if (attachment.kind == AttachmentKind.IMAGE) ImageTile(attachment, 56.dp) else FileTile(attachment, onInverse = false)
                }
                Surface(
                    onClick = { onRemove(attachment) }, shape = CircleShape, color = InkTone.inverse,
                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp).semantics { contentDescription = "移除附件 ${attachment.name}" },
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("×", color = InkTone.onInverse, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/** 已发出的附件：图片成排缩略，文件成卡，靠右对齐在用户气泡之上。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SentAttachments(attachments: List<AssistantAttachment>) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            if (attachment.kind == AttachmentKind.IMAGE) ImageTile(attachment, 88.dp) else FileTile(attachment, onInverse = false)
        }
    }
}

/** 「附」钮：点开选择图片或文件。 */
@Composable
internal fun AttachButton(enabled: Boolean, onPickImages: () -> Unit, onPickFiles: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { menuOpen = true }, enabled = enabled, shape = RoundedCornerShape(28.dp), color = InkTone.raised,
            modifier = Modifier.size(52.dp).border(1.dp, InkTone.hairline, RoundedCornerShape(28.dp))
                .semantics { contentDescription = "添加附件" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("附", fontFamily = InkTone.display, fontWeight = FontWeight.Black, fontSize = 22.sp,
                    color = if (enabled) InkTone.soft else InkTone.faint)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = RoundedCornerShape(16.dp), containerColor = InkTone.raised) {
            DropdownMenuItem(
                text = { MenuLine("图片", "相册里的照片、截图") },
                leadingIcon = { KindSeal(AttachmentKind.IMAGE, 32.dp) },
                onClick = { menuOpen = false; onPickImages() },
            )
            DropdownMenuItem(
                text = { MenuLine("文件", "PDF、Word、表格、文本") },
                leadingIcon = { KindSeal(AttachmentKind.DOC, 32.dp) },
                onClick = { menuOpen = false; onPickFiles() },
            )
        }
    }
}

@Composable
private fun MenuLine(title: String, note: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = InkTone.text)
        Spacer(Modifier.height(2.dp))
        Text(note, style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint)
    }
}
