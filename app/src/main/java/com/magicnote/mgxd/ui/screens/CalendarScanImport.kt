package com.magicnote.mgxd.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.ui.viewmodel.CalendarViewModel
import com.magicnote.mgxd.ui.viewmodel.SettingsViewModel
import com.magicnote.mgxd.util.DiaryImageStore
import com.magicnote.mgxd.util.RecurrenceHelper
import com.magicnote.mgxd.util.TimeUtils
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** 识别出的待导入日程 */
private data class ScannedEvent(
    val title: String,
    val start: Long,
    val end: Long,
    val recurrence: String,
    val weekday: Int
)

private fun recurrenceLabel(rec: String, weekday: Int): String = when {
    rec == RecurrenceHelper.WEEKLY && weekday in 1..7 -> "↻ 每周" + RecurrenceHelper.weekdayCn(weekday)
    rec == RecurrenceHelper.DAILY -> "↻ 每天"
    rec == RecurrenceHelper.MONTHLY -> "↻ 每月"
    else -> ""
}

/**
 * 拍照 / 相册导入日程：选图 → 多模态识别 → 预览勾选 → 批量写入日历
 *
 * 依赖：设置里已配置支持视觉的模型（gpt-4o / qwen-vl / gemini 等）。
 * 拍照用系统相机 Intent（不需要 CAMERA 权限）；临时文件放在 cacheDir/scan/，
 * 该目录已在 res/xml/file_paths.xml 声明（否则 FileProvider 会抛 IllegalArgumentException 导致闪退）。
 */
@Composable
fun CalendarScanImportDialog(
    settings: SettingsViewModel,
    vm: CalendarViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiConfig by settings.aiConfig.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf<List<ScannedEvent>>(emptyList()) }
    var checked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var pendingPhotoFile by remember { mutableStateOf<File?>(null) }

    fun scanDir(ctx: Context): File = File(ctx.cacheDir, "scan").apply { mkdirs() }

    suspend fun recognize(file: File) {
        if (aiConfig.baseUrl.isBlank() || aiConfig.apiKey.isBlank() || aiConfig.model.isBlank()) {
            error = "请先在「设置 → Magic AI 接口配置」填写 Base URL / API Key / 模型"
            loading = false
            return
        }
        try {
            val base64 = withContext(Dispatchers.IO) { DiaryImageStore.encodeForVision(file.absolutePath) }
            if (base64 == null) {
                error = "图片读取失败"
                loading = false
                return
            }
            val today = LocalDate.now()
            val prompt = """
                你是日程识别助手。请识别图片中的日程、课表、活动安排，输出 JSON 数组（不要输出任何其他文字）：
                [{"title":"事项标题","date":"yyyy-MM-dd","start":"HH:mm","end":"HH:mm","recurrence":"NONE|DAILY|WEEKLY|MONTHLY","weekday":0}]
                规则：
                1. 图片里没有年份时按今天所在年份；完全没有日期时用今天（$today）。
                2. end 缺失时按 start + 1 小时。
                3. 图片里出现"每周六/每星期六"这类循环描述时：recurrence=WEEKLY，weekday 用 ISO 数字（周一=1 … 周日=7）。
                4. 出现"每天"用 DAILY；"每月X号"用 MONTHLY。
                5. 只识别真正的日程/安排，忽略装饰文字。
            """.trimIndent()
            val reply = AiClient().chat(
                baseUrl = aiConfig.baseUrl,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                messages = listOf(
                    AiClient.ChatMessage("system", prompt),
                    AiClient.ChatMessage("user", "请识别这张图片里的日程", images = listOf(base64))
                ),
                jsonMode = true,
                timeoutSeconds = 120
            )
            val parsed = parseScanned(reply)
            if (parsed.isEmpty()) {
                error = "没有识别到日程，换张更清晰的图试试"
            } else {
                scanned = parsed
                checked = parsed.indices.toSet()
                error = null
            }
        } catch (e: Exception) {
            error = "识别失败：" + (e.message ?: "未知错误")
        } finally {
            loading = false
        }
    }

    // 拍照（图片写入 cacheDir/scan/，与 file_paths.xml 中声明的路径一致）
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhotoFile
        if (!ok || file == null || !file.exists() || file.length() == 0L) {
            error = if (!ok) "已取消拍照" else "没有拿到照片，请重试"
            return@rememberLauncherForActivityResult
        }
        loading = true
        error = null
        scanned = emptyList()
        scope.launch { recognize(file) }
    }

    // 相册
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            error = "已取消选择"
            return@rememberLauncherForActivityResult
        }
        loading = true
        error = null
        scanned = emptyList()
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dst = File(scanDir(context), "pick_" + System.currentTimeMillis() + ".jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                    dst.takeIf { it.exists() && it.length() > 0 }
                }.getOrNull()
            }
            if (file == null) {
                error = "读取相册图片失败"
                loading = false
            } else {
                recognize(file)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入日程（拍照 / 相册）") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "拍课表 / 黑板 / 手写日程，或从相册选一张，Magic AI 会识别成日程（需支持视觉的模型）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        enabled = !loading,
                        onClick = {
                            // 关键：文件必须落在 file_paths.xml 声明过的目录，否则 FileProvider 抛异常闪退
                            runCatching {
                                val file = File(scanDir(context), "scan_" + System.currentTimeMillis() + ".jpg")
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    context.packageName + ".fileprovider",
                                    file
                                )
                                uri to file
                            }.onSuccess { (uri, file) ->
                                pendingPhotoFile = file
                                runCatching { takePicture.launch(uri) }
                                    .onFailure { error = "无法启动相机：" + (it.message ?: "") }
                            }.onFailure {
                                error = "创建拍照文件失败：" + (it.message ?: "")
                            }
                        }
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("拍照")
                    }
                    FilledTonalButton(
                        enabled = !loading,
                        onClick = {
                            runCatching {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }.onFailure { error = "无法打开相册：" + (it.message ?: "") }
                        }
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("从相册选择")
                    }
                }
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("识别中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (scanned.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("识别到 ${scanned.size} 条，勾选要导入的：", style = MaterialTheme.typography.labelLarge)
                    scanned.forEachIndexed { index, item ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth(),
                            leadingContent = {
                                Checkbox(
                                    checked = index in checked,
                                    onCheckedChange = { on ->
                                        checked = if (on) checked + index else checked - index
                                    }
                                )
                            },
                            headlineContent = { Text(item.title) },
                            supportingContent = {
                                val rec = recurrenceLabel(item.recurrence, item.weekday)
                                Text(
                                    TimeUtils.formatMillis(item.start, "MM-dd HH:mm") + " - " +
                                        TimeUtils.formatMillis(item.end, "HH:mm") +
                                        if (rec.isNotEmpty()) "   $rec" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    }
                    TextButton(
                        onClick = {
                            checked = if (checked.size == scanned.size) emptySet() else scanned.indices.toSet()
                        }
                    ) { Text(if (checked.size == scanned.size) "全不选" else "全选") }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = checked.isNotEmpty() && !loading,
                onClick = {
                    scanned.forEachIndexed { index, item ->
                        if (index in checked) {
                            vm.addEvent(
                                title = item.title,
                                start = item.start,
                                end = item.end,
                                description = "由图片导入",
                                color = com.magicnote.mgxd.ui.components.EVENT_COLOR_ARGB[
                                    index % com.magicnote.mgxd.ui.components.EVENT_COLOR_ARGB.size
                                ],
                                remindMinutes = 0,
                                recurrence = item.recurrence,
                                recurrenceWeekday = item.weekday,
                                recurrenceUntil = 0L
                            )
                        }
                    }
                    onDismiss()
                }
            ) { Text("导入 ${checked.size} 条") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 从 AI 返回文本里提取 JSON 数组并解析成日程；容错：截取首尾方括号 */
private fun parseScanned(raw: String): List<ScannedEvent> {
    val text = raw.trim()
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val jsonText = text.substring(start, end + 1)
    val array = runCatching { Json.parseToJsonElement(jsonText) as JsonArray }.getOrNull() ?: return emptyList()
    val zone = ZoneId.systemDefault()
    val out = ArrayList<ScannedEvent>()
    array.forEach { el ->
        val obj = el as? JsonObject ?: return@forEach
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotBlank() }
        val title = str("title")?.trim().orEmpty()
        if (title.isBlank()) return@forEach
        val dateStr = str("date") ?: LocalDate.now().toString()
        val startStr = str("start") ?: "09:00"
        val endStr = str("end")
        val date = runCatching { LocalDate.parse(dateStr.take(10)) }.getOrElse { LocalDate.now() }
        val st = runCatching { LocalTime.parse(startStr.take(5)) }.getOrElse { LocalTime.of(9, 0) }
        val en = endStr?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() } ?: st.plusHours(1)
        val startMs = date.atTime(st).atZone(zone).toInstant().toEpochMilli()
        var endMs = date.atTime(en).atZone(zone).toInstant().toEpochMilli()
        if (endMs <= startMs) endMs = startMs + 60 * 60 * 1000
        val rec = (str("recurrence") ?: RecurrenceHelper.NONE).uppercase()
            .takeIf { it in setOf(RecurrenceHelper.DAILY, RecurrenceHelper.WEEKLY, RecurrenceHelper.MONTHLY) }
            ?: RecurrenceHelper.NONE
        val weekday = runCatching { obj["weekday"]?.jsonPrimitive?.content?.toInt() }.getOrNull() ?: 0
        out += ScannedEvent(title, startMs, endMs, rec, weekday.coerceIn(0, 7))
    }
    return out
}