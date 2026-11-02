package com.voiceassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceassistant.data.AppSettings
import com.voiceassistant.data.CloudProfile
import com.voiceassistant.data.CloudProfiles
import com.voiceassistant.data.CloudProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端"多套配置"工具条：下拉切换 + 另存为 + 删除。
 *
 * 实现思路：当前生效的仍是 AppSettings 里那几个 cloud* 字段（主链路不用改），
 * 这里只是把它们的快照存进 cloudProfilesJson，方便以后一键切回。
 * API Key 在快照里也是加密的，界面上永远不回显。
 */
@Composable
fun CloudProfileBar(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val profiles = remember(settings.cloudProfilesJson) { CloudProfiles.parse(settings.cloudProfilesJson) }
    var menuOpen by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val currentName = profiles.firstOrNull { it.id == settings.activeProfileId }?.name ?: "未保存的配置"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("当前配置", style = MaterialTheme.typography.bodyMedium)
                Text(
                    currentName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            OutlinedButton(onClick = { menuOpen = true }) { Text("切换") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                newName = settings.cloudModel.ifBlank { "配置 ${profiles.size + 1}" }
                askName = true
            }) { Text("另存为配置") }

            if (settings.activeProfileId.isNotBlank()) {
                OutlinedButton(onClick = {
                    val rest = profiles.filterNot { it.id == settings.activeProfileId }
                    onUpdate { it.copy(cloudProfilesJson = CloudProfiles.toJson(rest), activeProfileId = "") }
                }) { Text("删除当前配置") }
            }
        }

        Text(
            "切换配置会把它保存的地址/模型/Key 一起应用过来；Key 不回显，只在后台使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (profiles.isEmpty()) {
                DropdownMenuItem(text = { Text("还没有保存过配置") }, onClick = { menuOpen = false })
            }
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text("${p.name} · ${p.model.ifBlank { "未设模型" }}") },
                    onClick = {
                        menuOpen = false
                        onUpdate { s ->
                            s.copy(
                                cloudProvider = CloudProvider.of(p.providerId),
                                cloudBaseUrl = p.baseUrl,
                                cloudModel = p.model,
                                cloudApiKey = p.apiKey,
                                cloudTimeoutSec = p.timeoutSec,
                                cloudForceJson = p.forceJson,
                                activeProfileId = p.id
                            )
                        }
                    }
                )
            }
        }
    }

    if (askName) {
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("保存为配置") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("配置名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val profile = CloudProfile(
                        id = System.currentTimeMillis().toString(),
                        name = newName.ifBlank { "配置" },
                        providerId = settings.cloudProvider.id,
                        baseUrl = settings.cloudBaseUrl,
                        model = settings.cloudModel,
                        apiKey = settings.cloudApiKey,
                        timeoutSec = settings.cloudTimeoutSec,
                        forceJson = settings.cloudForceJson
                    )
                    val updated = profiles.filterNot { it.name == profile.name } + profile
                    onUpdate {
                        it.copy(
                            cloudProfilesJson = CloudProfiles.toJson(updated),
                            activeProfileId = profile.id
                        )
                    }
                    askName = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("取消") } }
        )
    }
}
