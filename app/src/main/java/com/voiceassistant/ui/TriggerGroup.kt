package com.voiceassistant.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceassistant.data.AppSettings

/**
 * 「触发」分组：说「音乐 / 电影 / 倒车」时启动哪个 App，由用户自己挑。
 * 挑的是手机里真实安装的 App，所以换播放器/换视频软件都不用改代码。
 */
private data class TriggerSlot(
    val label: String,
    val hint: String,
    val get: (AppSettings) -> String,
    val set: (AppSettings, String) -> AppSettings
)

@Composable
fun TriggerGroupCard(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val slots = listOf(
        TriggerSlot("音乐", "说「音乐」时打开哪个应用", { it.triggerMusic }) { s, v -> s.copy(triggerMusic = v) },
        TriggerSlot("电影", "说「电影」时打开哪个应用", { it.triggerMovie }) { s, v -> s.copy(triggerMovie = v) },
        TriggerSlot("倒车", "说「倒车」时打开哪个应用", { it.triggerReverse }) { s, v -> s.copy(triggerReverse = v) }
    )

    var picking by remember { mutableStateOf<TriggerSlot?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("触发", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "说出下面这几个词，就直接打开你指定的应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            slots.forEach { slot ->
                val pkg = slot.get(settings)
                val label = if (pkg.isBlank()) "未选择" else (appName(context, pkg) ?: pkg)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(slot.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pkg.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.secondary
                        )
                    }
                    OutlinedButton(onClick = { picking = slot }) { Text("选择") }
                }
            }
        }
    }

    picking?.let { slot ->
        AppPickerDialog(
            title = "选择「${slot.label}」对应的应用",
            onPick = { pkg ->
                onUpdate { slot.set(it, pkg) }
                picking = null
            },
            onDismiss = { picking = null }
        )
    }
}

private fun appName(context: Context, pkg: String): String? = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    null
}

@Composable
private fun AppPickerDialog(title: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                pkg to info.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                apps.forEach { (pkg, label) ->
                    TextButton(
                        onClick = { onPick(pkg) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("$label", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
