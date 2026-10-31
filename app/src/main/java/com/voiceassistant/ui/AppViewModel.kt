package com.voiceassistant.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceassistant.cloud.CloudIntentService
import com.voiceassistant.data.AppSettings
import com.voiceassistant.data.SettingsRepository
import com.voiceassistant.log.DebugLog
import com.voiceassistant.service.AppStateBus
import com.voiceassistant.service.WakeService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository.get(app)
    private val cloud = CloudIntentService()

    val settings: StateFlow<AppSettings> = repo.settings
    val uiState: StateFlow<AppStateBus.UiState> = AppStateBus.state

    // ---------------- 模型下载 ----------------

    data class DownloadState(
        val entryId: String? = null,
        val percent: Int = 0,
        val stage: String = "",
        val running: Boolean = false,
        val message: String? = null
    )

    private val _download = kotlinx.coroutines.flow.MutableStateFlow(DownloadState())
    val download: StateFlow<DownloadState> = _download

    private var downloader: com.voiceassistant.model.ModelDownloader? = null

    fun downloadModel(entry: com.voiceassistant.model.ModelCatalog.Entry) {
        if (_download.value.running) return
        val ctx = getApplication<Application>()
        val engine = com.voiceassistant.model.ModelDownloader(ctx)
        downloader = engine
        _download.value = DownloadState(entryId = entry.id, running = true, percent = 0)

        viewModelScope.launch {
            val dir = engine.download(entry) { progress ->
                val stage = when (progress.stage) {
                    com.voiceassistant.model.ModelDownloader.Progress.Stage.DOWNLOAD -> "下载中"
                    com.voiceassistant.model.ModelDownloader.Progress.Stage.EXTRACT -> "解压中"
                }
                _download.value = _download.value.copy(percent = progress.percent, stage = stage)
            }
            downloader = null
            if (dir == null) {
                _download.value = DownloadState(
                    entryId = entry.id, running = false, message = "下载失败，请检查网络或换用本地导入"
                )
                return@launch
            }
            val path = dir.absolutePath
            repo.update { s ->
                when (entry.kind) {
                    com.voiceassistant.model.ModelKind.KWS -> s.copy(wakeModelDir = path)
                    com.voiceassistant.model.ModelKind.ASR -> s.copy(asrModelDir = path)
                    com.voiceassistant.model.ModelKind.VAD -> s.copy(vadModelPath = path)
                    com.voiceassistant.model.ModelKind.TTS -> s.copy(ttsModelDir = path)
                }
            }
            _download.value = DownloadState(
                entryId = entry.id, running = false, percent = 100, message = "已安装：${entry.title}"
            )
        }
    }

    fun cancelDownload() {
        downloader?.cancel()
        downloader = null
        _download.value = _download.value.copy(running = false, message = "已取消下载")
    }

    /** 点"已安装"的模型时，把它设为当前使用 */
    fun selectModel(entry: com.voiceassistant.model.ModelCatalog.Entry) {
        val ctx = getApplication<Application>()
        val path = com.voiceassistant.model.ModelRepository.dir(ctx, entry.kind, entry.id).absolutePath
        viewModelScope.launch {
            repo.update { s ->
                when (entry.kind) {
                    com.voiceassistant.model.ModelKind.KWS -> s.copy(wakeModelDir = path)
                    com.voiceassistant.model.ModelKind.ASR -> s.copy(asrModelDir = path)
                    com.voiceassistant.model.ModelKind.VAD -> s.copy(vadModelPath = path)
                    com.voiceassistant.model.ModelKind.TTS -> s.copy(ttsModelDir = path)
                }
            }
            _download.value = DownloadState(message = "已切换：${entry.title}", percent = 100)
        }
    }

    fun dismissDownloadMessage() {
        _download.value = _download.value.copy(message = null)
    }

    // ---------------- 测试 ----------------

    fun testSpeak(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val s = repo.current()
            val engine = com.voiceassistant.service.engine.EngineFactory.createSpeak(ctx, s)
            try {
                engine.speak("你好，我是本地语音助手")
                onResult("已用 ${engine.name} 播报")
            } catch (e: Exception) {
                onResult("播报失败：${e.message}")
            } finally {
                engine.release()
            }
        }
    }

    fun testGeocode(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val s = repo.current()
            if (!s.hasAmapKey) {
                onResult("还没有填高德 Key")
                return@launch
            }
            val result = com.voiceassistant.exec.NavigationExecutor(ctx, s).testGeocode("大明湖景区")
            onResult(result ?: "解析失败：检查 Key、配额或网络")
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun resetAll() {
        viewModelScope.launch { repo.resetAll() }
    }

    // ---------------- 服务控制 ----------------

    fun startListening() {
        val ctx = getApplication<Application>()
        try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, WakeService::class.java).setAction(WakeService.ACTION_START)
            )
            DebugLog.d("VM", "请求启动监听")
        } catch (e: Exception) {
            DebugLog.e("VM", "启动监听失败", e)
            AppStateBus.onResult("启动失败：${e.message}")
        }
    }

    fun stopListening() {
        val ctx = getApplication<Application>()
        try {
            ctx.startService(Intent(ctx, WakeService::class.java).setAction(WakeService.ACTION_STOP))
        } catch (e: Exception) {
            DebugLog.e("VM", "停止监听失败", e)
        }
    }

    fun togglePause() {
        val ctx = getApplication<Application>()
        val action = if (uiState.value.paused) WakeService.ACTION_RESUME else WakeService.ACTION_PAUSE
        try {
            ctx.startService(Intent(ctx, WakeService::class.java).setAction(action))
        } catch (e: Exception) {
            DebugLog.e("VM", "切换暂停失败", e)
        }
    }

    /** 设置页「测试显示小球」：把结果和失败原因都写回状态，方便定位 */
    fun testFloatBall() {
        val ctx = getApplication<Application>()
        if (!com.voiceassistant.util.SystemSettings.hasOverlay(ctx)) {
            AppStateBus.setFloatBallError("没有悬浮窗权限，请先授予「显示在其他应用上层」")
            return
        }
        if (!uiState.value.serviceRunning) {
            AppStateBus.setFloatBallError("监听服务没在运行：先在首页点「开始监听」")
            return
        }
        try {
            AppStateBus.setFloatBallError("正在尝试显示…")
            ctx.startService(
                Intent(ctx, WakeService::class.java).setAction(WakeService.ACTION_SHOW_BALL)
            )
        } catch (e: Exception) {
            AppStateBus.setFloatBallError("请求失败：${e.message}")
        }
    }

    // ---------------- 测试连接 ----------------

    fun testCloud(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val s = repo.current()
            val result = cloud.parse("打开网易云音乐，然后把音量调小一点", s)
            val message = when {
                result.error != null -> "失败：${result.error.kind.userMessage} ${result.error.detail}"
                result.outcome is com.voiceassistant.nlu.ParseOutcome.Success -> {
                    val plan = (result.outcome as com.voiceassistant.nlu.ParseOutcome.Success).plan
                    "成功：${plan.actions.joinToString(" + ") { it.summary() }}"
                }
                result.outcome is com.voiceassistant.nlu.ParseOutcome.Failure ->
                    "返回无法解析：${(result.outcome as com.voiceassistant.nlu.ParseOutcome.Failure).reason}"
                else -> "失败：未知错误"
            }
            AppStateBus.onDebug(
                cloudRaw = result.rawContent,
                cleaned = result.cleaned,
                parseState = message
            )
            onResult(result.error == null, message)
        }
    }

    // ---------------- 日志导出 ----------------

    fun exportLogs(onReady: (Uri?) -> Unit) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val file = DebugLog.export(ctx)
            if (file == null) {
                onReady(null)
                return@launch
            }
            val uri = try {
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            } catch (e: Exception) {
                DebugLog.e("VM", "生成分享链接失败", e)
                null
            }
            onReady(uri)
        }
    }

    fun shareLogs(uri: Uri) {
        val ctx = getApplication<Application>()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            DebugLog.e("VM", "分享日志失败", e)
        }
    }
}
