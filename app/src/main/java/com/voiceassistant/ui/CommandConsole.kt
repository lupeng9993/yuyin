package com.voiceassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceassistant.cloud.CloudIntentService
import com.voiceassistant.data.AppSettings
import com.voiceassistant.exec.Dispatcher
import com.voiceassistant.nlu.ParseOutcome
import com.voiceassistant.nlu.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 调试控制台：不想说话的时候，直接把文字打进去执行。
 *
 * 两个入口：
 *   ① 文字指令 —— 走和语音一样的链路（本地规则 → 云端 → 执行）
 *   ② shell 命令 —— 通过 Shizuku 以 shell 身份执行，结果写进日志
 */
@Composable
fun CommandConsole(settings: AppSettings, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var shell by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("指令输入（文字模拟语音）", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("例如：导航到泰安高铁站") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    busy = true
                    val input = text
                    scope.launch {
                        val msg = runText(context, settings, input)
                        onMessage(msg)
                        busy = false
                    }
                }
            ) { Text(if (busy) "执行中…" else "执行") }
        }

        Text("shell 命令（需要 Shizuku 授权）", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = shell,
                onValueChange = { shell = it },
                label = { Text("例如：input tap 100 200") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                enabled = !busy && shell.isNotBlank(),
                onClick = {
                    busy = true
                    val cmd = shell
                    scope.launch {
                        val out = withContext(Dispatchers.IO) {
                            com.voiceassistant.shell.ShizukuShell.exec(cmd, context)
                        }
                        com.voiceassistant.log.DebugLog.d("ShellCmd", "$cmd\n$out")
                        onMessage("已执行，输出写日志（${out.length} 字符）")
                        busy = false
                    }
                }
            ) { Text("执行") }
        }
    }
}

/** 和语音完全相同的解析链路，只是把「说话」换成「打字」 */
private suspend fun runText(
    context: android.content.Context,
    settings: AppSettings,
    input: String
): String = withContext(Dispatchers.IO) {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return@withContext "指令为空"

    val local = RuleEngine.matchLocal(trimmed)
    val plan = local?.plan ?: run {
        if (!settings.cloudEnabled) return@withContext "本地没命中，云端未启用：$trimmed"
        val result = CloudIntentService().parse(trimmed, settings)
        when (val outcome = result.outcome) {
            is ParseOutcome.Success -> outcome.plan
            is ParseOutcome.Failure -> return@withContext "云端解析失败：${outcome.reason}"
            null -> return@withContext "云端调用失败：${result.error?.kind}"
        }
    }

    val outcome = Dispatcher(
        context = context,
        settings = settings,
        confirm = { true },
        choosePlace = { null },
        chooseOption = { null }
    ).dispatch(plan)

    outcome.speech.ifBlank { if (outcome.allOk) "执行完成" else "执行失败" }
}
