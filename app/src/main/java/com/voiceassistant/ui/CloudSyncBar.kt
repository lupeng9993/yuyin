package com.voiceassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceassistant.data.AppSettings
import com.voiceassistant.sync.ConfigSync
import kotlinx.coroutines.launch

/**
 * 云端配置：上传 / 下载。
 * 下载成功后把整个设置对象写回，等同于"新机一键配好"。
 */
@Composable
fun CloudSyncBar(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val msg = ConfigSync.upload(context, settings)
                        onMessage(msg)
                        busy = false
                    }
                }
            ) { Text(if (busy) "处理中…" else "上传配置到云端") }

            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val (msg, applied) = ConfigSync.download(context, settings)
                        if (applied != null) {
                            onUpdate { applied }
                        }
                        onMessage(msg)
                        busy = false
                    }
                }
            ) { Text("从云端恢复配置") }
        }
        Text(
            "只同步开关、地址、模型选择等；API Key 仅在填了同步口令时才会加密上传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
