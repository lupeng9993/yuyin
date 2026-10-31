package com.voiceassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voiceassistant.data.AppSettings
import com.voiceassistant.service.AppStateBus

@Composable
fun HomeScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    var showSkills by remember { mutableStateOf(false) }

    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "本地语音助手",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            StatusCard(
                running = state.serviceRunning,
                paused = state.paused,
                cloudEnabled = settings.cloudEnabled,
                micGranted = micGranted,
                keyword = settings.wakeKeyword
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                contentAlignment = Alignment.Center
            ) {
                ListeningOrb(
                    listening = state.serviceRunning && !state.paused,
                    paused = state.paused,
                    onPauseToggle = { vm.togglePause() }
                )
            }

            ResultCard(state)

            if (settings.debugMode) {
                DebugCard(state)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (state.serviceRunning) vm.stopListening() else vm.startListening()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (state.serviceRunning) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (state.serviceRunning) "停止监听" else "开始监听")
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("设置")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = { showSkills = true }, modifier = Modifier.weight(1f)) {
                    Text("技能状态")
                }
                OutlinedButton(
                    onClick = {
                        vm.exportLogs { uri ->
                            if (uri != null) vm.shareLogs(uri)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出日志")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showSkills) {
        SkillsDialog(settings = settings, state = state, onDismiss = { showSkills = false })
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    paused: Boolean,
    cloudEnabled: Boolean,
    micGranted: Boolean,
    keyword: String
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusRow(
                label = "唤醒服务",
                value = when {
                    !running -> "已停止"
                    paused -> "已暂停"
                    else -> "运行中"
                },
                good = running && !paused
            )
            StatusRow(
                label = "当前模式",
                value = if (cloudEnabled) "云端增强" else "纯本地",
                good = true
            )
            StatusRow(
                label = "录音权限",
                value = if (micGranted) "已授权" else "未授权",
                good = micGranted
            )
            StatusRow(label = "唤醒词", value = keyword, good = true)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, good: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ListeningOrb(listening: Boolean, paused: Boolean, onPauseToggle: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val baseColor = when {
        paused -> MaterialTheme.colorScheme.onSurfaceVariant
        listening -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .scale(if (listening) pulse else 1f)
                .alpha(0.18f)
                .background(baseColor, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(baseColor)
                .clickable(onClick = onPauseToggle),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "麦克风",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        paused -> "已暂停"
                        listening -> "正在监听"
                        else -> "未启动"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ResultCard(state: AppStateBus.UiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近一次识别", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                state.lastAsrText.ifBlank { "还没有识别过内容" },
                style = MaterialTheme.typography.bodyLarge
            )
            if (state.lastIntent.isNotBlank()) {
                Text("意图：${state.lastIntent}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.lastResult.isNotBlank()) {
                Text("结果：${state.lastResult}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.lastSource.isNotBlank()) {
                Text("来源：${state.lastSource}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DebugCard(state: AppStateBus.UiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("调试信息", style = MaterialTheme.typography.labelLarge)
            DebugLine("原始 ASR", state.debugAsrText)
            DebugLine("云端原始返回", state.debugCloudRaw)
            DebugLine("清洗后 JSON", state.debugCleaned)
            DebugLine("解析状态", state.debugParseState)
        }
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Text(
        "$label：${value.ifBlank { "—" }}",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SkillsDialog(
    settings: AppSettings,
    state: AppStateBus.UiState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("技能状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkillLine("唤醒", settings.wakeEnabled)
                SkillLine("本地识别", settings.asrEnabled)
                SkillLine("云端解析", settings.cloudEnabled)
                SkillLine("导航", settings.navEnabled)
                SkillLine("微信发送", settings.wechatEnabled)
                SkillLine("应用与系统控制", settings.otherEnabled)
                SkillLine("语音播报", settings.ttsEnabled)
                SkillLine("无障碍服务", state.accessibilityReady)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

@Composable
private fun SkillLine(name: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (enabled) "已开启" else "已关闭",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
