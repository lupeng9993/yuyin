package com.voiceassistant.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

    // 标签式手风琴
    var activeTag by remember { mutableStateOf(0) }
    // 添加唤醒词弹窗
    var showAddKeyword by remember { mutableStateOf(false) }
    var newKeywordDisplay by remember { mutableStateOf("") }
    var newKeywordPinyin by remember { mutableStateOf("") }
    var keywordMsg by remember { mutableStateOf<String?>(null) }
    // 欢迎词下拉
    var ackMenuOpen by remember { mutableStateOf(false) }
    // 模型唤醒词列表折叠
    var showKeywords by remember { mutableStateOf(false) }
    // 唤醒词选择弹窗
    var showKeywordPicker by remember { mutableStateOf(false) }

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

    val wide = isWideLayout()

    Scaffold { padding ->
        // 宽屏：左边固定菜单 + 右边内容，一屏能看全；窄屏保持原来的折叠卡片
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
        Row(modifier = Modifier.widthIn(max = 1400.dp).fillMaxSize()) {
            if (wide) {
                Column(
                    modifier = Modifier
                        .width(230.dp)
                        .fillMaxHeight()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                        Text(
                            "设置",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    listOf("唤醒与音频", "云端解析", "技能管理", "触发", "系统与调试")
                        .forEachIndexed { index, label ->
                            NavItem(label, selected = activeTag == index) { activeTag = index }
                        }
                }
            }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .widthIn(max = 1000.dp)
                .swipeBackToDismiss(onBack)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (wide) 24.dp else 16.dp,
                    vertical = if (wide) 24.dp else 8.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp)
        ) {
            if (!wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }

            if (downloadState.running) {
                Text(
                    "后台${downloadState.stage}：${downloadState.percent}%  （可关掉弹窗，下载会继续）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 窄屏才需要标签行；宽屏用左侧菜单
            if (!wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("唤醒", "云端", "技能", "触发", "系统").forEachIndexed { index, label ->
                        val selected = activeTag == index
                        val onClick: () -> Unit = {
                            activeTag = index
                            expandWake = index == 0
                            expandCloud = index == 1
                            expandSkills = index == 2
                            expandSystem = index == 4
                        }
                        if (selected) {
                            Button(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            OutlinedButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ---------------- 分组一 ----------------
            GroupCard(
                "唤醒与音频",
                "唤醒词、模型、音频",
                if (wide) activeTag == 0 else expandWake,
                onToggle = { if (wide) activeTag = 0 else expandWake = !expandWake }
            ) {
                SwitchRow(
                    "启用唤醒",
                    "总开关，关闭后前台服务不采集音频",
                    settings.wakeEnabled
                ) { v -> vm.update { it.copy(wakeEnabled = v) } }

                SwitchRow(
                    "唤醒后语音应答",
                    "说「我在」「有什么吩咐」之类的随机应答，然后再听指令",
                    settings.wakeAckEnabled,
                    settings.wakeEnabled
                ) { v -> vm.update { it.copy(wakeAckEnabled = v) } }
                Hint("如果你习惯连着说「唤醒词+指令」，可以关掉它，否则开头一两个字可能被应答声盖过去。")

                SwitchRow(
                    "结束对话应答",
                    "说「再见」「结束对话」「不用了」时随机回一句告别",
                    settings.farewellEnabled,
                    settings.wakeEnabled
                ) { v -> vm.update { it.copy(farewellEnabled = v) } }

                Divider(Modifier.padding(vertical = 6.dp))
                Text("欢迎词（唤醒后先说这句）", style = MaterialTheme.typography.bodyMedium)
                Column {
                    OutlinedButton(onClick = { ackMenuOpen = true }) {
                        Text(
                            if (settings.wakeAckMode == "fixed" && settings.wakeAckFixed.isNotBlank())
                                "固定：${settings.wakeAckFixed}"
                            else "随机（一半概率带时间问候）"
                        )
                    }
                    DropdownMenu(expanded = ackMenuOpen, onDismissRequest = { ackMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("随机") },
                            onClick = {
                                ackMenuOpen = false
                                vm.update { it.copy(wakeAckMode = "random") }
                            }
                        )
                        phraseChoices(settings.wakeAckPhrases).forEach { p ->
                            DropdownMenuItem(
                                text = { Text("固定：$p") },
                                onClick = {
                                    ackMenuOpen = false
                                    vm.update { it.copy(wakeAckMode = "fixed", wakeAckFixed = p) }
                                }
                            )
                        }
                    }
                }
                TextAreaRow(
                    "自定义欢迎词列表",
                    settings.wakeAckPhrases,
                    "一行一句，留空用内置 6 句；例如：老板我在"
                ) { v -> vm.update { it.copy(wakeAckPhrases = v) } }

                TextAreaRow(
                    "自定义结束语列表",
                    settings.farewellPhrases,
                    "一行一句，留空用内置 6 句；例如：那我先去忙了"
                ) { v -> vm.update { it.copy(farewellPhrases = v) } }

                FieldRow(
                    "唤醒词",
                    settings.wakeKeyword,
                    "例如：你好小助手",
                    enabled = settings.wakeEnabled
                ) { v -> vm.update { it.copy(wakeKeyword = v) } }
                Hint("唤醒词由模型的 keywords.txt 决定。下面列出这个模型真正支持的词，点一下即生效。")

                if (settings.wakeModelDir.isBlank()) {
                    Hint("还没选唤醒模型，先「应用内下载」或「导入本地文件」。")
                } else {
                    val keywords = remember(settings.wakeModelDir) {
                        com.voiceassistant.model.KwsKeywords.read(settings.wakeModelDir)
                    }
                    if (keywords.isEmpty()) {
                        Hint("⚠ 这个模型包里没有 keywords.txt，唤醒不会工作。请重新下载模型，或手动放入该文件。")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "模型支持 ${keywords.size} 个唤醒词",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(10.dp))
                            OutlinedButton(onClick = { showKeywordPicker = true }) {
                                Text("选择")
                            }
                        }
                    }
                }

                TextAreaRow(
                    "自定义关键词行（高级）",
                    settings.wakeKeywordLines,
                    "每行一个，格式：token token @显示词\n留空 = 只用模型自带 keywords.txt"
                ) { v -> vm.update { it.copy(wakeKeywordLines = v) } }

                // 自定义词里如果出现中文（@ 前面），KWS 必然建流失败 —— 提前警告
                val customHasChinese = settings.wakeKeywordLines.split('\n').any { line ->
                    line.substringBefore('@').any { it.code > 127 }
                }
                if (customHasChinese) {
                    Hint("⚠ 关键词里出现了中文。KWS 只认模型词表的拼写（如 x iǎo ài），写中文会导致唤醒失效。点上面的「选择」用模型自带的词更稳。")
                }

                OutlinedButton(onClick = { showAddKeyword = true }) {
                    Text("添加唤醒词（按模型格式生成）")
                }

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
            GroupCard(
                "云端解析（大模型）",
                "关闭后全部指令只走本地",
                if (wide) activeTag == 1 else expandCloud,
                onToggle = { if (wide) activeTag = 1 else expandCloud = !expandCloud }
            ) {
                CloudProfileBar(settings) { transform -> vm.update(transform) }
                Divider(Modifier.padding(vertical = 6.dp))

                SwitchRow(
                    "自动学习",
                    "云端解析成功后记住句式，下次本地直接执行",
                    settings.autoLearn
                ) { v -> vm.update { it.copy(autoLearn = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        infoMessage = "已学习 ${com.voiceassistant.nlu.LearnedRules.count(context)} 条：\n" +
                            com.voiceassistant.nlu.LearnedRules.summary(context)
                                .take(10)
                                .joinToString("\n")
                                .ifBlank { "（还没有学习到任何句式）" }
                    }) { Text("查看已学句式") }
                    OutlinedButton(onClick = {
                        com.voiceassistant.nlu.LearnedRules.clear(context)
                        infoMessage = "已清空全部学习记录"
                    }) { Text("清空学习记录") }
                }

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
                            cloudModel = preset.model.ifBlank { it.cloudModel },
                            // 书生·浦语不支持 response_format，选中它时自动关掉强制 JSON
                            cloudForceJson = if (p == CloudProvider.INTERNLM) false else it.cloudForceJson
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
                SwitchRow(
                    "聊天与查询",
                    "匹配不到指令时，让大模型直接回答（只播报，不执行动作）",
                    settings.chatFallbackEnabled,
                    cloudEnabled
                ) { v -> vm.update { it.copy(chatFallbackEnabled = v) } }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.testCloud { _, msg -> cloudTestResult = msg } },
                        enabled = cloudEnabled
                    ) { Text("测试连接") }
                }
                Hint(ProviderPresets.of(settings.cloudProvider).hint)
            }

            // ---------------- 分组三 ----------------
            GroupCard(
                "技能管理",
                "导航 / 微信 / 系统控制 / 播报",
                if (wide) activeTag == 2 else expandSkills,
                onToggle = { if (wide) activeTag = 2 else expandSkills = !expandSkills }
            ) {
                Text("导航", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                SwitchRow("启用导航技能", "高德地理编码 + 唤起高德 App", settings.navEnabled) { v ->
                    vm.update { it.copy(navEnabled = v) }
                }
                SecretRow("高德 Web API Key", settings.amapKey, enabled = settings.navEnabled) { v ->
                    vm.update { it.copy(amapKey = v) }
                }
                SecretRow("高德安全密钥（jscode）", settings.amapSecurityKey, enabled = settings.navEnabled) { v ->
                    vm.update { it.copy(amapSecurityKey = v) }
                }
                Hint("2021-12-02 之后申请的 Key 必须同时填「安全密钥」，否则高德会返回 INVALID_USER_SCODE（代码 10008）。")
                SwitchRow("导航前二次确认", "默认关闭", settings.navConfirm, settings.navEnabled) { v ->
                    vm.update { it.copy(navConfirm = v) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "auto" to "自动（优先车机版）",
                        "amapauto" to "车机版",
                        "amap" to "手机版",
                        "baidu" to "百度"
                    ).forEach { (value, label) ->
                        OutlinedButton(onClick = { vm.update { it.copy(navApp = value) } }) {
                            Text(if (settings.navApp == value) "● $label" else label)
                        }
                    }
                }
                Hint("「自动」= 装了高德车机版就优先用它。")

                Hint("客户端直连高德 Web API，Key 泄露与配额风险由用户自担（文档已排除自建后端）。")
                OutlinedButton(
                    onClick = { vm.testGeocode { msg -> infoMessage = msg } },
                    enabled = settings.navEnabled
                ) { Text("测试地点解析（大明湖景区）") }

                FieldRow(
                    "家所在城市（天气用）",
                    settings.homeCity,
                    "泰安"
                ) { v -> vm.update { it.copy(homeCity = v) } }
                Hint("说「今天天气」时：定位城市和这里不同 → 两个都播；相同 → 只播一次。天气复用上面这把高德 Key。")

                Divider(Modifier.padding(vertical = 6.dp))

                OutlinedButton(onClick = {
                    com.voiceassistant.exec.A11yBridge.dumpTree()
                    infoMessage = "节点树已写入日志，导出日志发出来即可"
                }) { Text("导出当前界面节点树（临时测试，后期删除）") }
                Hint("先停在出问题的界面（例如微信聊天界面），再点这个按钮。")

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

            // ---------------- 触发（音乐/电影/倒车） ----------------
            if (!wide || activeTag == 3) {
                TriggerGroupCard(settings) { transform -> vm.update(transform) }
                Spacer(Modifier.size(2.dp))
            }

            // ---------------- 分组五 ----------------
            GroupCard(
                "系统与调试",
                "权限、日志、重置",
                if (wide) activeTag == 4 else expandSystem,
                onToggle = { if (wide) activeTag = 4 else expandSystem = !expandSystem }
            ) {
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
                OutlinedButton(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } else {
                        infoMessage = "这个系统版本默认支持精确提醒，不用额外设置"
                    }
                }) { Text("允许精确提醒（Android 12+）") }
                Hint("不打开的话，语音提醒可能晚几分钟。")

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        val r = com.voiceassistant.reminder.Reminder(
                            id = com.voiceassistant.reminder.ReminderStore.nextId(context),
                            triggerAt = System.currentTimeMillis() + 10_000,
                            text = "测试提醒",
                            label = "10 秒后"
                        )
                        val exact = com.voiceassistant.reminder.ReminderScheduler.add(context, r)
                        infoMessage = if (exact) {
                            "已设置 10 秒后的测试提醒（精确闹钟）"
                        } else {
                            "已设置，但没有精确闹钟权限，可能晚几分钟"
                        }
                    }) { Text("测试提醒（10 秒后）") }

                    OutlinedButton(onClick = {
                        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE)
                            as android.media.AudioManager
                        val before = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        repeat(2) {
                            am.adjustStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.ADJUST_LOWER,
                                android.media.AudioManager.FLAG_SHOW_UI
                            )
                        }
                        val after = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        infoMessage = "媒体音量：$before → $after（最大 $max）\n" +
                            if (before == after) "数字没变：可能是系统限制了本应用改音量"
                            else "数字变了：如果耳朵听不出来，说明是车机功放独立控制"
                    }) { Text("音量诊断") }
                }

                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("开启通知使用权（控制音乐播放）") }
                Hint("开启后「暂停 / 继续播放 / 上一首 / 下一首」才真的能控制其它音乐 App。")

                Divider(Modifier.padding(vertical = 6.dp))
                Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "下面几项是临时测试功能（日志上传、模型服务端、节点树、发送键偏移），稳定后会删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val pkgInfo = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                }
                val versionName = pkgInfo?.versionName ?: "未知"
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    pkgInfo?.longVersionCode ?: 0L
                } else {
                    @Suppress("DEPRECATION")
                    (pkgInfo?.versionCode ?: 0).toLong()
                }
                Text(
                    "版本：$versionName（build $versionCode）",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "包名：${context.packageName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "离线优先的语音助手：唤醒 → 识别 → 本地规则优先 → 云端兜底",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Divider(Modifier.padding(vertical = 6.dp))
                Text("Shizuku（临时测试，后期删除）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        val granted = com.voiceassistant.shell.ShizukuShell.requestPermission()
                        infoMessage = if (granted) {
                            "Shizuku 已授权"
                        } else {
                            "如果弹出授权框请点允许；没弹出说明 Shizuku 没在运行"
                        }
                    }) { Text("授权 Shizuku") }

                    OutlinedButton(onClick = {
                        val running = com.voiceassistant.shell.ShizukuShell.isAvailable()
                        val granted = com.voiceassistant.shell.ShizukuShell.hasPermission()
                        infoMessage = "Shizuku 服务：${if (running) "运行中" else "未运行"}；权限：${if (granted) "已授权" else "未授权"}"
                    }) { Text("检查状态") }
                }
                Hint("授权后说「执行命令」可以跑一次 uiautomator dump，用来验证能否读到微信界面。")

                Divider(Modifier.padding(vertical = 6.dp))
                Divider(Modifier.padding(vertical = 6.dp))
                Divider(Modifier.padding(vertical = 6.dp))
                Text("调试控制台（临时测试）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                CommandConsole(settings) { msg -> infoMessage = msg }
                Text("模型与配置（临时测试）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                SwitchRow(
                    "卸载不删除模型",
                    "开：模型放 /sdcard/VA-Models，需要「所有文件访问」；关：放私有目录",
                    settings.keepModelsOnUninstall
                ) { v -> vm.update { it.copy(keepModelsOnUninstall = v) } }
                Text(
                    "当前模型目录：" + com.voiceassistant.model.ModelRepository.rootPath(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FieldRow("GitHub 配置地址", settings.githubConfigUrl, "https://raw.githubusercontent.com/.../va_config.json") { v ->
                    vm.update { it.copy(githubConfigUrl = v) }
                }
                SwitchRow("启动时从 GitHub 拉配置", "只读，需要能访问 raw.githubusercontent.com", settings.autoPullGithub) { v ->
                    vm.update { it.copy(autoPullGithub = v) }
                }
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(com.voiceassistant.ui.PermissionGuideActivity.intent(context)) }
                }) { Text("重新打开权限引导") }
                Text("日志上传（临时测试，后期删除）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FieldRow("上传地址", settings.logUploadUrl, "https://你的服务器/upload") { v ->
                    vm.update { it.copy(logUploadUrl = v) }
                }
                SwitchRow(
                    "自动上传日志",
                    "执行失败时自动把日志包 POST 到这个地址",
                    settings.autoUploadLog
                ) { v -> vm.update { it.copy(autoUploadLog = v) } }
                Hint("说一句「上传日志」也能手动上传。服务端收到的是 zip 日志包（application/zip）。")

                Divider(Modifier.padding(vertical = 6.dp))
                Text("模型服务端（临时测试，后期删除）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FieldRow("模型下载地址", settings.modelServerUrl, "http://192.168.51.10:8099/models") { v ->
                    vm.update { it.copy(modelServerUrl = v) }
                }
                Hint("填了就走局域网下载，不再连 GitHub；留空走官方地址。目录结构与 GitHub 一致（kws-models/、asr-models/、tts-models/）。")

                Divider(Modifier.padding(vertical = 6.dp))
                Text("云端配置（临时测试，后期删除）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FieldRow("同步地址", settings.syncUrl, "http://192.168.51.10:8099/config") { v ->
                    vm.update { it.copy(syncUrl = v) }
                }
                FieldRow("同步口令（可选）", settings.syncPass, "不填则不迁移 API Key") { v ->
                    vm.update { it.copy(syncPass = v) }
                }
                CloudSyncBar(
                    settings = settings,
                    onUpdate = { transform -> vm.update(transform) },
                    onMessage = { infoMessage = it }
                )

                SwitchRow("悬浮小球", "常驻监听入口，也是 Android 14 后台启动麦克风的豁免条件", settings.floatBallEnabled) { v ->
                    vm.update { it.copy(floatBallEnabled = v) }
                }
                SliderRow(
                    "悬浮球透明度",
                    settings.floatBallAlpha,
                    0.2f..1f,
                    enabled = settings.floatBallEnabled
                ) { v -> vm.update { it.copy(floatBallAlpha = v) } }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.testFloatBall() }) { Text("测试显示小球") }
                    Text(
                        if (uiState.floatBallVisible) "当前：已显示" else "当前：未显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.floatBallVisible) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (uiState.floatBallError.isNotBlank()) {
                    Hint("⚠ ${uiState.floatBallError}")
                }
                if (!SystemSettings.hasOverlay(context)) {
                    Hint("⚠ 还没有悬浮窗权限，小球不会显示。")
                    OutlinedButton(
                        onClick = { SystemSettings.open(context, SystemSettings.overlayIntent(context)) }
                    ) { Text("去授予悬浮窗权限") }
                } else if (!uiState.serviceRunning) {
                    Hint("权限已授予。小球只在监听服务运行时出现——回首页点「开始监听」。")
                } else {
                    Hint("权限已授予、服务在运行，小球应出现在屏幕右侧边缘。")
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
                Text(
                    "关闭应用方式：${com.voiceassistant.exec.ShellExecutor.stateText()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Hint("关闭应用走三级兜底：回桌面 → 结束后台进程 → 在「应用详情」点「强行停止」。需要开启无障碍。")

                Divider(Modifier.padding(vertical = 6.dp))
                OutlinedButton(onClick = { showReset = true }) { Text("重置全部配置") }
                Hint("重置只恢复设置，不删除已下载或导入的模型文件。")
            }

            Spacer(Modifier.height(24.dp))
        }
        }
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
            onDownload = { entry, url -> vm.downloadModel(entry, url) },
            onSelect = { entry -> vm.selectModel(entry) },
            onDelete = { entry -> vm.deleteModel(entry) },
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

    if (showKeywordPicker) {
        val keywords = remember(settings.wakeModelDir) {
            com.voiceassistant.model.KwsKeywords.read(settings.wakeModelDir)
        }
        AlertDialog(
            onDismissRequest = { showKeywordPicker = false },
            title = { Text("选择唤醒词") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (keywords.isEmpty()) {
                        Text("这个模型包里没有 keywords.txt", style = MaterialTheme.typography.bodySmall)
                    }
                    keywords.forEach { entry ->
                        val selected = entry.rawLine == settings.wakeKeywordLines
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.update {
                                        it.copy(
                                            wakeKeyword = entry.display,
                                            wakeKeywordLines = entry.rawLine
                                        )
                                    }
                                    showKeywordPicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.size(10.dp))
                            Text(entry.display, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKeywordPicker = false }) { Text("关闭") }
            }
        )
    }

    if (showAddKeyword) {
        AlertDialog(
            onDismissRequest = {
                showAddKeyword = false
                keywordMsg = null
            },
            title = { Text("添加唤醒词") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newKeywordDisplay,
                        onValueChange = { newKeywordDisplay = it },
                        label = { Text("显示文本") },
                        placeholder = { Text("例如 你好小助手") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newKeywordPinyin,
                        onValueChange = { newKeywordPinyin = it },
                        label = { Text("拼音音节（空格分开）") },
                        placeholder = { Text("ni hao xiao zhu shou") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Hint("会用模型的 tokens.txt 自动拼成 keywords.txt 的格式；哪个音节映射不上会直接告诉你。")
                    keywordMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = com.voiceassistant.model.KwsKeywordBuilder.build(
                        modelDir = settings.wakeModelDir,
                        display = newKeywordDisplay,
                        pinyinInput = newKeywordPinyin
                    )
                    if (result.line != null) {
                        val merged = (settings.wakeKeywordLines.lines().filter { it.isNotBlank() } + result.line)
                            .joinToString("\n")
                        vm.update {
                            it.copy(
                                wakeKeywordLines = merged,
                                wakeKeyword = newKeywordDisplay.trim()
                            )
                        }
                        keywordMsg = "已添加：${result.line}"
                        newKeywordDisplay = ""
                        newKeywordPinyin = ""
                    } else {
                        keywordMsg = result.message
                    }
                }) { Text("生成并添加") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddKeyword = false
                    keywordMsg = null
                }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun ModelDownloadDialog(
    kind: ModelKind,
    progress: AppViewModel.DownloadState,
    onDownload: (com.voiceassistant.model.ModelCatalog.Entry, String) -> Unit,
    onSelect: (com.voiceassistant.model.ModelCatalog.Entry) -> Unit,
    onDelete: (com.voiceassistant.model.ModelCatalog.Entry) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val entries = remember(kind) { com.voiceassistant.model.ModelCatalog.byKind(kind) }
    var customUrl by remember(kind) { mutableStateOf("") }
    // 进度或开关变化时刷新「已安装」状态
    val installed = remember(kind, progress.running, progress.percent, progress.entryId) {
        ModelRepository.installedNames(context, kind)
    }

    AlertDialog(
        // 允许直接关掉弹窗，下载在后台继续跑
        onDismissRequest = onDismiss,
        title = { Text(kind.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("自定义下载地址（可选）") },
                    placeholder = { Text("https://…/model.int8.onnx 或 .tar.bz2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Hint("GitHub 连不上时，这里填镜像或自己架的直链；下载前会自动清空旧文件，避免残缺模型。")
                Divider()
                entries.forEach { entry ->
                    val isCurrent = progress.running && progress.entryId == entry.id
                    val isInstalled = installed.contains(entry.id)
                    Column {
                        Text(
                            "${entry.title} · ${entry.approxMb} MB",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            entry.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when {
                            isCurrent -> Text(
                                if (progress.stage == "解压中") "解压中…（模型越大越久，请稍等）"
                                else "${progress.stage} ${progress.percent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onSelect(entry) }) {
                                    Text("已安装 · 点此使用")
                                }
                                TextButton(
                                    onClick = { onDelete(entry) },
                                    enabled = !progress.running
                                ) { Text("删除重下") }
                            }
                            else -> TextButton(
                                onClick = { onDownload(entry, customUrl) },
                                enabled = !progress.running
                            ) { Text("下载") }
                        }
                    }
                    Divider()
                }
                progress.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
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

/** 宽屏左侧菜单项 */
@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

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
            ModelRepository.nameOf(path),
            style = MaterialTheme.typography.bodySmall,
            color = if (path.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.secondary
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
private fun TextAreaRow(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 欢迎词下拉候选：自定义列表 + 内置默认 */
private fun phraseChoices(raw: String): List<String> {
    val custom = raw.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
    val base = listOf("我在", "嗯，你说", "听着呢", "有什么吩咐", "在呢，请讲", "来啦，请说")
    return (custom + base).distinct()
}

/** 从左边缘往右滑 → 返回上一页 */
private fun Modifier.swipeBackToDismiss(onBack: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        val edgePx = 96.dp.toPx()
        val thresholdPx = 140.dp.toPx()
        var startX = 0f
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                startX = offset.x
                total = 0f
            },
            onDragEnd = {
                if (total > thresholdPx) onBack()
                total = 0f
            },
            onDragCancel = { total = 0f },
            onHorizontalDrag = { _, dragAmount ->
                if (startX <= edgePx) total += dragAmount
            }
        )
    }

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
