package com.voiceassistant.ui

import android.Manifest
import android.content.Intent
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

    /** 语音里"去设置选一下"会带 screen=settings 直接把设置页拉起来 */
    private val screenState = mutableStateOf(Screen.HOME)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val mic = result[Manifest.permission.RECORD_AUDIO] ?: false
            if (!mic) {
                Toast.makeText(this, "没有录音权限，唤醒和识别都用不了", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        if (!PermissionGuideActivity.isDone(this)) {
            runCatching { startActivity(PermissionGuideActivity.intent(this)) }
        }
        requestBasePermissions()

        setContent {
            VoiceAssistantTheme {
                VaApp2(screen = screenState.value, onScreen = { screenState.value = it })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra("screen") == "settings") {
            screenState.value = Screen.SETTINGS
        }
    }

    private fun requestBasePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 天气要用定位拿当前城市；手电筒要用相机。拒绝也不影响其它功能。
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        permissions.add(Manifest.permission.CAMERA)
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
private fun VaApp2(
    screen: Screen,
    onScreen: (Screen) -> Unit,
    vm: AppViewModel = viewModel()
) {
    when (screen) {
        Screen.HOME -> HomeScreen(
            vm = vm,
            onOpenSettings = { onScreen(Screen.SETTINGS) }
        )
        Screen.SETTINGS -> SettingsScreen(
            vm = vm,
            onBack = { onScreen(Screen.HOME) }
        )
    }
}
