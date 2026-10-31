package com.voiceassistant.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceassistant.ui.theme.VoiceAssistantTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val mic = result[Manifest.permission.RECORD_AUDIO] ?: false
            if (!mic) {
                Toast.makeText(this, "没有录音权限，唤醒和识别都用不了", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBasePermissions()

        setContent {
            VoiceAssistantTheme {
                VaApp()
            }
        }
    }

    private fun requestBasePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
private fun VaApp(vm: AppViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.HOME) }

    when (screen) {
        Screen.HOME -> HomeScreen(
            vm = vm,
            onOpenSettings = { screen = Screen.SETTINGS }
        )
        Screen.SETTINGS -> SettingsScreen(
            vm = vm,
            onBack = { screen = Screen.HOME }
        )
    }
}
