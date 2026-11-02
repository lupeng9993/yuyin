package com.voiceassistant.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceassistant.exec.PlaceChoiceBridge
import com.voiceassistant.ui.theme.VoiceAssistantTheme

/**
 * 导航候选选择界面：说出目的地后弹出，按数字或直接点选，
 * 选中后立即进入导航；点「取消」或返回则不导航。
 */
class PlaceChoiceActivity : ComponentActivity() {

    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val places = PlaceChoiceBridge.current?.places
        if (places.isNullOrEmpty()) {
            finish()
            return
        }

        PlaceChoiceBridge.clearClose()
        setContent {
            VoiceAssistantTheme {
                // 语音那边选完了就自己关掉
                LaunchedEffect(Unit) {
                    while (true) {
                        if (PlaceChoiceBridge.closeRequested) {
                            answered = true
                            finish()
                            break
                        }
                        kotlinx.coroutines.delay(150)
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        ChoiceList(
                            places = places.map { Triple(it.name, it.address, it.distanceText) },
                            onPick = { index ->
                                answered = true
                                PlaceChoiceBridge.answer(index)
                                finish()
                            },
                            onCancel = {
                                answered = true
                                PlaceChoiceBridge.answer(null)
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // 用户直接返回时也当作取消，避免服务一直等
        if (!answered) PlaceChoiceBridge.answer(null)
        super.onDestroy()
    }
}

@Composable
private fun ChoiceList(
    places: List<Triple<String, String, String>>,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 900.dp)
            .padding(if (isWideLayout()) 28.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("选择目的地", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "找到 ${places.size} 个结果，点一下直接开始导航；也可以直接说出序号或地名",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.size(6.dp))

        places.forEachIndexed { index, (name, address, distance) ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(index) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (address.isNotBlank()) {
                            Text(
                                address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (distance.isNotBlank()) {
                        Text(
                            distance,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.size(10.dp))
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("取消")
        }
    }
}
