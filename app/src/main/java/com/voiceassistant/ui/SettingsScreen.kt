package com.voiceassistant.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.voiceassistant.cloud.ProviderPresets
import com.voiceassistant.data.AppSettings
import com.voiceassistant.data.CloudProvider
import com.voiceassistant.data.TtsEngine
import com.voiceassistant.model.ModelKind
import com.voiceassistant.model.ModelRepository
import com.voiceassistant.util.SystemSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current
    val scope = remember { CoroutineScope(Dispatchers.Main) }

    var expandWake by remember { mutableStateOf(true) }
    var expandCloud by remember { mutableStateOf(false) }
    var expandSkills by remember { mutableStateOf(false) }
    var expandSystem by remember { mutableStateOf(false) }

    var showReset by remember { mutableStateOf(false) }
    var cloudTestResult by remember { mutableStateOf<String?>(null) }
    var downloadKind by remember { mutableStateOf<ModelKind?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    val downloadState by vm.download.collectAsState()

    // 模型导入：一个选择器，按当前待导入类别分流
    var pendingKind by remember { mutableStateOf<ModelKind?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val kind = pendingKind
        if (uri != null && kind != null) {
            scope.launch {
                val file = ModelRepository.importFile(context, uri, kind, kind.label)
                if (file != null) {
                    val dirPath = file.parentFile?.absolutePath ?: ""
                    vm.update { s ->
                        when (kind) {
                            ModelKind.KWS -> s.copy(wakeModelDir = dirPath)
                            ModelKind.ASR -> s.copy(asrModelDir = dirPath)
                            ModelKind.VAD -> s.copy(vadModelPath = file.absolutePath)
                            ModelKind.TTS -> s.copy(ttsModelDir = dirPath)
                        }
                    }
                }
            }
        }
        pendingKind = null
    }

    fun pick(kind: ModelKind) {
        pendingKind = kind
        importLauncher.launch(arrayOf("*/*"))
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }

            // ---------------- 分组一 ----------------
            GroupCard("唤醒与音频", "唤醒词、模型、音频", expandWake, onToggle = { expandWake = !expandWake }) {
                SwitchRow(
                    "启用唤醒",
                    "总开关，关闭后前台服务不采集音频",
                    settings.wakeEnabled
                ) { v -> vm.update { it.copy(wakeEnabled = v) } }

                FieldRow(
                    "唤醒词",
                    settings.wakeKeyword,
                    "例如：你好小助手",
                    enabled = settings.wakeEnabled
                ) { v -> vm.update { it.copy(wakeKeyword = v) } }
                Hint("唤醒词是纯文本，改词不需要重新训练模型；保存后需重启监听生效。")

                SliderRow(
                    "唤醒灵敏度",
                    settings.wakeThreshold,
                    0.05f..0.95f
                ) { v -> vm.update { it.copy(wakeThreshold = v) } }
                Hint("数值越小越容易触发，也越容易被环境噪音误唤醒。")

                PathRow(
                    "唤醒模型目录",
                    settings.wakeModelDir,
                    onPick = { pick(ModelKind.KWS) },
                    onDownload = { downloadKind = ModelKind.KWS }
                )
                Hint("唤醒词写在模型的 keywords.txt 里，改词不用重训；格式见 docs/MODELS.md。")

                Divider(Modifier.padding(vertical = 6.dp))

                SwitchRow("本地语音识别", "离线把语音转文字（ASR）", settings.asrEnabled) { v ->
                    vm.update { it.copy(asrEnabled = v) }
                }
                PathRow(
                    "识别模型目录",
                    settings.asrModelDir,
                    onPick = { pick(ModelKind.ASR) },
                    onDownload = { downloadKind = ModelKind.ASR }
                )

                PathRow(
                    "VAD 模型（可选）",
                    settings.vadModelPath,
                    onPick = { pick(ModelKind.VAD) }
                )
                Hint("导入 silero_vad.onnx 可提升嘈杂环境下的截句准确率；不导入则用内置能量 VAD，功能不受影响。")

                SliderRow("静音判定（毫秒）", settings.vadSilenceMs.toFloat(), 300f..1500f) { v ->
                    vm.update { it.copy(vadSilenceMs = v.toInt()) }
                }
                Hint("音频格式固定 16 kHz 单声道，全链路不重采样。")
            }

            // ---------------- 分组二 ----------------
            GroupCard("云端解析（大模型）", "关闭后全部指令只走本地", expandCloud, onToggle = { expandCloud = !expandCloud }) {
                SwitchRow("启用云端解析", "只发送识别后的文本，绝不上传音频", settings.cloudEnabled) { v ->
                    vm.update { it.copy(cloudEnabled = v) }
                }

                val cloudEnabled = settings.cloudEnabled
                ProviderSelector(settings.cloudProvider, cloudEnabled) { p ->
                    val preset = ProviderPresets.of(p)
                    vm.update {
                        it.copy(
                            cloudProvider = p,
                            cloudBaseUrl = preset.baseUrl.ifBlank { it.cloudBaseUrl },
                            cloudModel = preset.model.ifBlank { it.cloudModel }
                        )
                    }
                }

                FieldRow("基础地址 Base URL", settings.cloudBaseUrl, "https://.../v1", enabled = cloudEnabled) { v ->
                    vm.update { it.copy(cloudBaseUrl = v) }
                }
                FieldRow("模型名称", settings.cloudModel, "如 qwen-plus / ep-xxx", enabled = cloudEnabled) { v ->
                    vm.update { it.copy(cloudModel = v) }
                }
                SecretRow("API Key", settings.cloudApiKey, enabled = cloudEnabled) { v ->
                    vm.update { it.copy(cloudApiKey = v) }
                }
                Hint("Key 用 Android Keystore 加密保存在本机，不会写进 APK 或仓库。")

                SliderRow("请求超时（秒）", settings.cloudTimeoutSec.toFloat(), 5f..60f, enabled = cloudEnabled) { v ->
                    vm.update { it.copy(cloudTimeoutSec = v.toInt()) }
                }
                SwitchRow("启用强制 JSON", "服务商支持 response_format 时携带", settings.cloudForceJson, cloudEnabled) { v ->
                    vm.update { it.copy(cloudForceJson = v) }
                }
                SwitchRow("自动降级", "云端失败后再试一次本地规则", settings.cloudAutoFallback, cloudEnabled) { v ->
                    vm.update { it.copy(cloudAutoFallback = v) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.testCloud { _, msg -> cloudTestResult = msg } },
                        enabled = cloudEnabled
                    ) { Text("测试连接") }
                }
                Hint(ProviderPresets.of(settings.cloudProvider).hint)
            }

            // ---------------- 分组三 ----------------
            GroupCard("技能管理", "导航 / 微信 / 系统控制 / 播报", expandSkills, onToggle = { expandSkills = !expandSkills }) {
                Text("导航", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                SwitchRow("启用导航技能", "高德地理编码 + 唤起高德 App", settings.navEnabled) { v ->
                    vm.update { it.copy(navEnabled = v) }
                }
                SecretRow("高德 Web API Key", settings.amapKey, enabled = settings.navEnabled) { v ->
                    vm.update { it.copy(amapKey = v) }
                }
                SwitchRow("导航前二次确认", "默认关闭", settings.navConfirm, settings.navEnabled) { v ->
                    vm.update { it.copy(navConfirm = v) }
                }
                Hint("客户端直连高德 Web API，Key 泄露与配额风险由用户自担（文档已排除自建后端）。")
                OutlinedButton(
                    onClick = { vm.testGeocode { msg -> infoMessage = msg } },
                    enabled = settings.navEnabled
                ) { Text("测试地点解析（大明湖景区）") }

                Divider(Modifier.padding(vertical = 6.dp))
                Text("微信发送", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                SwitchRow("启用微信发送", "依赖无障碍服务", settings.wechatEnabled) { v ->
                    vm.update { it.copy(wechatEnabled = v) }
                }
                Hint("只要这个技能开启，无论本地匹配还是云端解析，都必须语音二次确认后才会真的发送。")
                OutlinedButton(
                    onClick = { SystemSettings.open(context, SystemSettings.accessibilityIntent()) }
                ) { Text(if (uiState.accessibilityReady) "无障碍已开启" else "去开启无障碍服务") }

                Divider(Modifier.padding(vertical = 6.dp))
                Text("其他应用与系统控制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                SwitchRow("启用系统控制", "音量、截屏、打开应用、播放控制", settings.otherEnabled) { v ->
                    vm.update { it.copy(otherEnabled = v) }
                }
                OutlinedButton(
                    onClick = { SystemSettings.open(context, SystemSettings.accessibilityIntent()) }
                ) { Text("跳转无障碍设置") }

                Divider(Modifier.padding(vertical = 6.dp))
                Text("语音反馈 TTS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                SwitchRow("启用语音播报", "", settings.ttsEnabled) { v ->
                    vm.update { it.copy(ttsEnabled = v) }
                }
                RadioRow("系统内置 TTS", settings.ttsEngine == TtsEngine.SYSTEM, settings.ttsEnabled) {
                    vm.update { it.copy(ttsEngine = TtsEngine.SYSTEM) }
                }
                RadioRow("内置离线引擎（sherpa-onnx）", settings.ttsEngine == TtsEngine.SHERPA, settings.ttsEnabled) {
                    vm.update { it.copy(ttsEngine = TtsEngine.SHERPA) }
                }

                if (settings.ttsEngine == TtsEngine.SHERPA) {
                    PathRow(
                        "TTS 模型目录",
                        settings.ttsModelDir,
                        onPick = { pick(ModelKind.TTS) },
                        onDownload = { downloadKind = ModelKind.TTS }
                    )
                    OutlinedButton(onClick = {
                        vm.testSpeak { msg -> infoMessage = msg }
                    }) { Text("测试发音：你好，我是本地语音助手") }
                    SliderRow("语速", settings.ttsSpeed, 0.5f..2.0f, enabled = settings.ttsEnabled) { v ->
                        vm.update { it.copy(ttsSpeed = v) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent("com.android.settings.TTS_SETTINGS")
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        ) { Text("检查中文语音包") }
                    }
                }
                SliderRow("播报音量", settings.ttsVolume, 0f..1f, enabled = settings.ttsEnabled) { v ->
                    vm.update { it.copy(ttsVolume = v) }
                }
            }

            // ---------------- 分组四 ----------------
            GroupCard("系统与调试", "权限、日志、重置", expandSystem, onToggle = { expandSystem = !expandSystem }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { SystemSettings.open(context, SystemSettings.overlayIntent(context)) }) {
                        Text("悬浮窗权限")
                    }
                    OutlinedButton(onClick = { SystemSettings.open(context, SystemSettings.notificationIntent(context)) }) {
                        Text("通知权限")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { SystemSettings.open(context, SystemSettings.batteryOptimizationListIntent()) }) {
                        Text("电池优化")
                    }
                    OutlinedButton(onClick = { SystemSettings.open(context, SystemSettings.appDetailsIntent(context)) }) {
                        Text("应用详情")
                    }
                }

                SwitchRow("悬浮小球", "常驻监听入口，也是 Android 14 后台启动麦克风的豁免条件", settings.floatBallEnabled) { v ->
                    vm.update { it.copy(floatBallEnabled = v) }
                }
                SwitchRow("开机自启", "有悬浮窗权限时会尝试自启，失败静默", settings.bootAutoStart) { v ->
                    vm.update { it.copy(bootAutoStart = v) }
                }

                Divider(Modifier.padding(vertical = 6.dp))
                SwitchRow("调试模式", "首页展示原始识别文本、云端返回、清洗结果", settings.debugMode) { v ->
                    vm.update { it.copy(debugMode = v) }
                }
                OutlinedButton(onClick = {
                    vm.exportLogs { uri -> if (uri != null) vm.shareLogs(uri) }
                }) { Text("导出运行日志") }

                Divider(Modifier.padding(vertical = 6.dp))
                OutlinedButton(onClick = { showReset = true }) { Text("重置全部配置") }
                Hint("重置只恢复设置，不删除已下载或导入的模型文件。")
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("重置全部配置？") },
            text = { Text("所有开关、密钥、模型路径都会恢复默认；模型文件本身不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetAll()
                    showReset = false
                }) { Text("确认重置") }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("取消") } }
        )
    }

    cloudTestResult?.let { msg ->
        AlertDialog(
            onDismissRequest = { cloudTestResult = null },
            title = { Text("测试连接结果") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { cloudTestResult = null }) { Text("关闭") } }
        )
    }

    downloadKind?.let { kind ->
        ModelDownloadDialog(
            kind = kind,
            progress = downloadState,
            onDownload = { entry -> vm.downloadModel(entry) },
            onCancelDownload = { vm.cancelDownload() },
            onDismiss = {
                vm.dismissDownloadMessage()
                downloadKind = null
            }
        )
    }

    infoMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { infoMessage = null },
            title = { Text("测试结果") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { infoMessage = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun ModelDownloadDialog(
    kind: ModelKind,
    progress: AppViewModel.DownloadState,
    onDownload: (com.voiceassistant.model.ModelCatalog.Entry) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val entries = remember(kind) { com.voiceassistant.model.ModelCatalog.byKind(kind) }

    AlertDialog(
        onDismissRequest = { if (!progress.running) onDismiss() },
        title = { Text("下载${kind.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "从 sherpa-onnx 官方 Releases 下载，解压到应用私有目录；仓库和 APK 里不含模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                entries.forEach { entry ->
                    Column {
                        Text("${entry.title} · 约 ${entry.approxMb} MB", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            entry.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (progress.running && progress.entryId == entry.id) {
                            Text("下载中 ${progress.percent}%", style = MaterialTheme.typography.bodySmall)
                        } else {
                            TextButton(
                                onClick = { onDownload(entry) },
                                enabled = !progress.running
                            ) { Text("下载这个") }
                        }
                    }
                    Divider()
                }
                if (progress.message != null) {
                    Text(progress.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (progress.running) {
                TextButton(onClick = onCancelDownload) { Text("取消下载") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

// ---------------- 通用组件 ----------------

@Composable
private fun GroupCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(Modifier.height(4.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc.isNotBlank()) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    placeholder: String,
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SecretRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChange: (Float) -> Unit
) {
    Column {
        Text(
            "$label：${if (range.endInclusive <= 2f) "%.2f".format(value) else value.toInt().toString()}",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled
        )
    }
}

@Composable
private fun PathRow(
    label: String,
    path: String,
    onPick: () -> Unit,
    onDownload: (() -> Unit)? = null
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            path.ifBlank { "未选择" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick) { Text("导入本地文件") }
            if (onDownload != null) {
                OutlinedButton(onClick = onDownload) { Text("应用内下载") }
            }
        }
    }
}

@Composable
private fun ProviderSelector(provider: CloudProvider, enabled: Boolean, onSelect: (CloudProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("服务商", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(provider.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CloudProvider.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = {
                        expanded = false
                        onSelect(p)
                    }
                )
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
