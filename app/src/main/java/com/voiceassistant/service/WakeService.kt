package com.voiceassistant.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.voiceassistant.data.AppSettings
import com.voiceassistant.data.SettingsRepository
import com.voiceassistant.exec.Dispatcher
import com.voiceassistant.exec.DispatchOutcome
import com.voiceassistant.log.DebugLog
import com.voiceassistant.nlu.ActionPlan
import com.voiceassistant.nlu.RuleEngine
import com.voiceassistant.nlu.VaAction
import com.voiceassistant.service.engine.AsrEngine
import com.voiceassistant.service.engine.EngineFactory
import com.voiceassistant.service.engine.SpeakEngine
import com.voiceassistant.service.engine.WakeEngine
import com.voiceassistant.cloud.CloudIntentService
import com.voiceassistant.nlu.ParseOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 常驻唤醒服务：整条链路的大脑。
 *
 * 状态机（严格按文档的执行顺序）：
 *   LISTENING → 命中唤醒词 → CAPTURING（VAD 截句） → PROCESSING（ASR → 本地规则 → 云端）
 *   → 执行（需要时进入 CONFIRMING 二次确认） → 播报 → LISTENING
 */
class WakeService : Service() {

    companion object {
        private const val TAG = "WakeService"
        const val ACTION_START = "com.voiceassistant.action.START"
        const val ACTION_STOP = "com.voiceassistant.action.STOP"
        const val ACTION_PAUSE = "com.voiceassistant.action.PAUSE"
        const val ACTION_RESUME = "com.voiceassistant.action.RESUME"

        private const val MAX_UTTERANCE_MS = 12_000L
        private const val CONFIRM_TIMEOUT_MS = 10_000L
        private const val MIN_UTTERANCE_MS = 400L
    }

    private enum class State { LISTENING, CAPTURING, PROCESSING, CONFIRMING, SPEAKING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioChannel = Channel<ShortArray>(capacity = 64)

    private lateinit var repo: SettingsRepository
    private var settings: AppSettings = AppSettings()

    private var audio: AudioCapture? = null
    private var wake: WakeEngine? = null
    private var asr: AsrEngine? = null
    private var speak: SpeakEngine? = null
    private var vad: VadEngine? = null
    private var floatWindow: FloatWindow? = null
    private var loopJob: Job? = null
    private val cloud = CloudIntentService()

    @Volatile
    private var state: State = State.LISTENING

    @Volatile
    private var paused = false

    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository.get(this)
        DebugLog.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pause()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resume()
                return START_STICKY
            }
            else -> Unit
        }

        if (!running.get()) startServiceInternal()
        return START_STICKY
    }

    // ---------------- 生命周期 ----------------

    private fun startServiceInternal() {
        running.set(true)
        DebugLog.d(TAG, "启动监听服务")

        try {
            startForegroundCompat()
        } catch (e: Exception) {
            DebugLog.e(TAG, "启动前台服务失败（可能是后台启动限制）", e)
            running.set(false)
            stopSelf()
            return
        }

        AppStateBus.setServiceRunning(true)
        scope.launch {
            settings = repo.current()
            rebuildEngines()
            startAudio()
            if (settings.floatBallEnabled) showFloatBall()
            observeSettings()
        }
    }

    private fun startForegroundCompat() {
        NotificationHelper.ensureChannel(this)
        val notification = NotificationHelper.build(this, settings.wakeKeyword, paused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.build(this, settings.wakeKeyword, paused)
            )
        } catch (e: Exception) {
            DebugLog.w(TAG, "更新通知失败: ${e.message}")
        }
    }

    private fun observeSettings() {
        scope.launch {
            repo.settings.collect { updated ->
                val enginesChanged = updated.wakeModelDir != settings.wakeModelDir ||
                    updated.asrModelDir != settings.asrModelDir ||
                    updated.ttsEngine != settings.ttsEngine ||
                    updated.ttsModelDir != settings.ttsModelDir ||
                    updated.wakeKeyword != settings.wakeKeyword
                settings = updated
                if (enginesChanged) {
                    DebugLog.d(TAG, "配置变化，重建引擎")
                    rebuildEngines()
                }
                updateNotification()
            }
        }
    }

    private fun rebuildEngines() {
        scope.launch {
            withContext(Dispatchers.IO) {
                releaseEngines()
                wake = EngineFactory.createWake(this@WakeService, settings)
                asr = EngineFactory.createAsr(this@WakeService, settings)
                speak = EngineFactory.createSpeak(this@WakeService, settings)
                vad = EngineFactory.createVad(this@WakeService, settings)
                DebugLog.d(TAG, "引擎就绪: wake=${wake?.name} asr=${asr?.name} tts=${speak?.name} vad=${vad?.name}")
            }
        }
    }

    private fun startAudio() {
        if (loopJob?.isActive == true) return
        val capture = AudioCapture { pcm -> audioChannel.trySend(pcm) }
        if (!capture.start()) {
            DebugLog.e(TAG, "麦克风启动失败（检查权限）")
            AppStateBus.onResult("麦克风不可用，请检查录音权限")
            return
        }
        audio = capture

        loopJob = scope.launch { listenLoop() }
    }

    // ---------------- 主循环 ----------------

    private suspend fun listenLoop() {
        DebugLog.d(TAG, "进入监听循环")
        while (scope.isActive) {
            val chunk = audioChannel.receive()
            if (paused) continue
            if (state != State.LISTENING) continue

            val samples = chunk.toFloatSamples()
            val w = wake ?: continue
            if (w.accept(samples, AudioCapture.SAMPLE_RATE)) {
                DebugLog.d(TAG, "唤醒命中")
                state = State.CAPTURING
                try {
                    handleUtterance()
                } catch (e: Exception) {
                    DebugLog.e(TAG, "处理指令失败", e)
                    AppStateBus.onResult("处理失败：${e.message}")
                } finally {
                    drainAudio()
                    wake?.reset()
                    state = State.LISTENING
                }
            }
        }
    }

    /** 唤醒后：VAD 截句 → ASR → 意图 → 执行 → 播报 */
    private suspend fun handleUtterance() {
        val samples = captureUtterance(MAX_UTTERANCE_MS)
        if (samples.size < AudioCapture.SAMPLE_RATE * MIN_UTTERANCE_MS / 1000) {
            DebugLog.d(TAG, "语音过短，忽略")
            return
        }

        state = State.PROCESSING
        val text = recognize(samples)
        if (text.isBlank()) {
            DebugLog.d(TAG, "ASR 结果为空")
            speakSafely(getString(com.voiceassistant.R.string.tts_asr_empty))
            return
        }

        AppStateBus.onDebug(asr = text, parseState = "ASR 完成")
        DebugLog.d(TAG, "识别文本: $text")

        val plan = understand(text) ?: run {
            speakSafely(getString(com.voiceassistant.R.string.tts_unknown_command))
            AppStateBus.onResult("无法识别该指令")
            return
        }

        AppStateBus.onRecognized(text, plan)
        execute(plan)
    }

    /** 本地规则优先；未命中且云端已开才请求云端；云端失败按设置决定是否回退 */
    private suspend fun understand(text: String): ActionPlan? {
        RuleEngine.matchLocal(text)?.let { match ->
            DebugLog.d(TAG, "本地规则命中: ${match.plan.actions.size} 个动作")
            AppStateBus.onDebug(parseState = "本地规则命中")
            return match.plan
        }

        if (!settings.cloudEnabled) {
            AppStateBus.onDebug(parseState = "本地未命中，云端已关闭")
            DebugLog.d(TAG, "本地未命中且云端关闭")
            return null
        }

        val result = cloud.parse(text, settings)
        AppStateBus.onDebug(
            cloudRaw = result.rawContent,
            cleaned = result.cleaned,
            parseState = if (result.error != null) "云端失败：${result.error.kind}" else "云端解析完成"
        )

        when (val outcome = result.outcome) {
            is ParseOutcome.Success -> return outcome.plan
            is ParseOutcome.Failure -> DebugLog.w(TAG, "云端解析失败: ${outcome.reason}")
            null -> DebugLog.w(TAG, "云端调用失败: ${result.error?.kind} ${result.error?.detail}")
        }

        // 降级：再走一次本地规则（云端已失败，这里给用户一次本地兜底的机会）
        if (settings.cloudAutoFallback) {
            RuleEngine.matchLocal(text)?.let {
                AppStateBus.onDebug(parseState = "云端失败，已降级到本地规则")
                return it.plan
            }
        }
        return null
    }

    private suspend fun execute(plan: ActionPlan) {
        val outcome = Dispatcher(
            context = applicationContext,
            settings = settings,
            confirm = { action -> askConfirm(action) }
        ).dispatch(plan)

        reportOutcome(outcome)
        if (outcome.speech.isNotBlank()) speakSafely(outcome.speech)
    }

    private fun reportOutcome(outcome: DispatchOutcome) {
        val summary = outcome.results.joinToString("；") { r ->
            val status = if (r.ok) "成功" else "失败"
            "${r.action.summary()} → $status${if (r.detail.isNotBlank()) "（${r.detail}）" else ""}"
        }
        AppStateBus.onResult(summary)
        DebugLog.d(TAG, "执行汇总: $summary")
    }

    // ---------------- 二次确认（语音） ----------------

    private suspend fun askConfirm(action: VaAction): Boolean {
        val question = when (action.type) {
            com.voiceassistant.nlu.ActionType.SEND_WECHAT ->
                getString(com.voiceassistant.R.string.tts_confirm_wechat, action.contact, action.message)
            else -> "确认执行吗？说确认或取消。"
        }

        state = State.SPEAKING
        speakSafely(question)
        delay(200)

        state = State.CONFIRMING
        drainAudio()
        val samples = captureUtterance(CONFIRM_TIMEOUT_MS, requireSpeech = false)
        val answer = if (samples.size > AudioCapture.SAMPLE_RATE * 300 / 1000) recognize(samples) else ""
        DebugLog.d(TAG, "确认回答: $answer")

        val confirmWords = listOf("确认", "确定", "发送", "是的", "对", "好")
        val cancelWords = listOf("取消", "不", "算了", "别", "不要")

        return when {
            confirmWords.any { answer.contains(it) } -> true
            cancelWords.any { answer.contains(it) } -> false
            else -> false // 超时或没听清 → 一律不发
        }
    }

    // ---------------- 音频工具 ----------------

    /**
     * 从通道里收集一段话，直到 VAD 判定结束或超时。
     * @param requireSpeech 为 false 时（确认场景）只用超时结束，不强制要有语音
     */
    private suspend fun captureUtterance(maxMs: Long, requireSpeech: Boolean = true): FloatArray {
        val engine = vad ?: EnergyVad(silenceMs = settings.vadSilenceMs)
        engine.reset()
        val out = ArrayList<Float>(AudioCapture.SAMPLE_RATE * 8)
        val startAt = System.currentTimeMillis()
        var frames = 0

        while (scope.isActive) {
            val elapsed = System.currentTimeMillis() - startAt
            if (elapsed > maxMs) break

            val chunk = audioChannel.tryReceive().getOrNull()
            if (chunk == null) {
                delay(20)
                continue
            }
            val samples = chunk.toFloatSamples()
            engine.accept(samples)
            out.addAll(samples.toList())
            frames++

            // 至少要积累 0.3 秒再考虑结束，避免一开场就静音判停
            if (frames > 10 && engine.isUtteranceEnd()) break
        }

        val total = FloatArray(out.size) { out[it] }
        if (requireSpeech && total.size < AudioCapture.SAMPLE_RATE / 4) return FloatArray(0)
        return total
    }

    private fun drainAudio() {
        while (audioChannel.tryReceive().isSuccess) {
            // 丢弃陈旧音频，避免把播报回声当成新指令
        }
    }

    private suspend fun recognize(samples: FloatArray): String {
        val engine = asr ?: return ""
        if (samples.isEmpty()) return ""
        return try {
            val text = withContext(Dispatchers.Default) {
                engine.transcribe(samples, AudioCapture.SAMPLE_RATE)
            }
            text.trim().replace(" ", "")
        } catch (e: Exception) {
            DebugLog.e(TAG, "识别失败", e)
            ""
        }
    }

    private suspend fun speakSafely(text: String) {
        if (!settings.ttsEnabled || text.isBlank()) return
        val previous = state
        state = State.SPEAKING
        try {
            speak?.speak(text)
        } catch (e: Exception) {
            DebugLog.e(TAG, "播报失败", e)
        } finally {
            state = previous
            drainAudio()
        }
    }

    // ---------------- 控制 ----------------

    fun pause() {
        paused = true
        AppStateBus.setPaused(true)
        floatWindow?.setListening(false)
        updateNotification()
        DebugLog.d(TAG, "已暂停监听")
    }

    fun resume() {
        paused = false
        AppStateBus.setPaused(false)
        floatWindow?.setListening(true)
        drainAudio()
        updateNotification()
        DebugLog.d(TAG, "已恢复监听")
    }

    fun togglePause() {
        if (paused) resume() else pause()
    }

    private fun showFloatBall() {
        if (floatWindow?.isVisible == true) return
        val window = FloatWindow(
            context = this,
            onTogglePause = { togglePause() },
            onOpenApp = {
                startActivity(
                    Intent(this, com.voiceassistant.ui.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
        if (window.show()) {
            window.setListening(!paused)
            floatWindow = window
        }
    }

    private fun hideFloatBall() {
        floatWindow?.hide()
        floatWindow = null
    }

    private fun stopSelfSafely() {
        running.set(false)
        DebugLog.d(TAG, "停止监听服务")
        shutDown()
        stopSelf()
    }

    private fun shutDown() {
        try {
            loopJob?.cancel()
            loopJob = null
            audio?.stop()
            audio = null
            hideFloatBall()
            releaseEngines()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            DebugLog.e(TAG, "关闭异常", e)
        }
        AppStateBus.setServiceRunning(false)
    }

    private fun releaseEngines() {
        try {
            wake?.release()
            asr?.release()
            speak?.release()
            vad?.release()
        } catch (e: Exception) {
            DebugLog.w(TAG, "释放引擎失败: ${e.message}")
        }
        wake = null
        asr = null
        speak = null
        vad = null
    }

    override fun onDestroy() {
        shutDown()
        scope.cancel()
        super.onDestroy()
    }
}
