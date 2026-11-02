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
import kotlinx.coroutines.withTimeoutOrNull
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
        const val ACTION_SHOW_BALL = "com.voiceassistant.action.SHOW_BALL"
        const val ACTION_HIDE_BALL = "com.voiceassistant.action.HIDE_BALL"

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
    private var rebuildJob: Job? = null
    private val cloud = CloudIntentService()

    /** 最近一次「听不懂」的具体原因，写到结果卡上方便排查 */
    private var lastUnderstandError: String = ""

    @Volatile
    private var state: State = State.LISTENING

    @Volatile
    private var paused = false

    private val running = AtomicBoolean(false)

    /** 内置默认应答语（用户可在设置里覆盖） */
    private val defaultAcks = listOf(
        "我在",
        "嗯，你说",
        "听着呢",
        "有什么吩咐",
        "在呢，请讲",
        "来啦，请说"
    )

    /** 内置默认结束语（用户可在设置里覆盖） */
    private val defaultFarewells = listOf(
        "再见",
        "拜拜",
        "好的，有事再叫我",
        "那我先歇着了",
        "回头见",
        "需要我就喊我"
    )

    private var lastAckIndex = -1
    private var lastFarewellIndex = -1

    private fun parseList(raw: String): List<String> =
        raw.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }

    private fun ackPhrases(): List<String> =
        parseList(settings.wakeAckPhrases).ifEmpty { defaultAcks }

    private fun farewellPhrases(): List<String> =
        parseList(settings.farewellPhrases).ifEmpty { defaultFarewells }

    /** 按当前时间给一句问候 */
    private fun timeGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> "早上好"
            in 11..12 -> "中午好"
            in 13..17 -> "下午好"
            else -> "晚上好"
        }
    }

    private fun nextWakeAck(): String {
        // 固定模式：直接用用户选定的那一句
        if (settings.wakeAckMode == "fixed") {
            val fixed = settings.wakeAckFixed.trim()
            if (fixed.isNotEmpty()) return fixed
        }

        val list = ackPhrases()
        if (list.size == 1) return list.first()

        var index: Int
        do {
            index = kotlin.random.Random.nextInt(list.size)
        } while (index == lastAckIndex)
        lastAckIndex = index
        val ack = list[index]
        // 一半概率带上「早上好/中午好/下午好/晚上好」
        return if (kotlin.random.Random.nextInt(100) < 50) "${timeGreeting()}，$ack" else ack
    }

    private fun nextFarewell(): String {
        val list = farewellPhrases()
        if (list.size <= 1) return list.firstOrNull() ?: "再见"
        var index: Int
        do {
            index = kotlin.random.Random.nextInt(list.size)
        } while (index == lastFarewellIndex)
        lastFarewellIndex = index
        return list[index]
    }

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
            ACTION_SHOW_BALL -> {
                showFloatBallForced()
                return START_STICKY
            }
            ACTION_HIDE_BALL -> {
                hideFloatBall()
                AppStateBus.setFloatBallError("")
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
            rebuildEnginesNow()
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
                    updated.wakeKeyword != settings.wakeKeyword ||
                    updated.wakeKeywordLines != settings.wakeKeywordLines
                settings = updated
                if (enginesChanged) {
                    DebugLog.d(TAG, "配置变化，重建引擎")
                    rebuildEngines()
                }
                syncFloatBall()
                floatWindow?.setAlpha(updated.floatBallAlpha)
                updateNotification()
            }
        }
    }

    /** 悬浮球开关变化时立即生效，不用重启服务 */
    private fun syncFloatBall() {
        if (settings.floatBallEnabled) {
            if (floatWindow?.isVisible != true) showFloatBall()
            floatWindow?.setListening(!paused)
        } else {
            hideFloatBall()
        }
    }

    private fun rebuildEngines() {
        // 防抖：用户连续调设置（拖滑块、点唤醒词）时不要每次都重建引擎，
        // 否则会在几百毫秒里反复加载模型，既卡又费电
        rebuildJob?.cancel()
        rebuildJob = scope.launch {
            delay(600)
            rebuildEnginesNow()
        }
    }

    private suspend fun rebuildEnginesNow() {
        withContext(Dispatchers.IO) {
            releaseEngines()
            wake = EngineFactory.createWake(this@WakeService, settings)
            asr = EngineFactory.createAsr(this@WakeService, settings)
            speak = EngineFactory.createSpeak(this@WakeService, settings)
            vad = EngineFactory.createVad(this@WakeService, settings)
            DebugLog.d(TAG, "引擎就绪: wake=${wake?.name} asr=${asr?.name} tts=${speak?.name} vad=${vad?.name}")
        }
        publishWakeInfo()
    }

    /** 把唤醒引擎的真实状态（引擎名、可用关键词、错误）推给界面 */
    private fun publishWakeInfo() {
        val engine = wake
        val kws = engine as? com.voiceassistant.service.wake.KwsEngine
        val keywords = kws?.keywordLines?.map { line ->
            val display = line.substringAfterLast('@', "").trim()
            if (display.isNotEmpty()) display else line
        } ?: emptyList()
        val error = kws?.error
            ?: (engine as? com.voiceassistant.service.engine.NoopWakeEngine)?.reason()
            ?: ""
        AppStateBus.setWakeInfo(engine?.name ?: "none", keywords, error)
        DebugLog.d(TAG, "唤醒状态: engine=${engine?.name} keywords=$keywords error=$error")

        val asrEngine = asr
        val asrError = when (asrEngine) {
            is com.voiceassistant.service.asr.SherpaAsrEngine -> asrEngine.initError ?: ""
            is com.voiceassistant.service.engine.NoopAsrEngine -> asrEngine.reason()
            else -> ""
        }
        AppStateBus.setAsrInfo(asrEngine?.name ?: "none", asrError)
        DebugLog.d(TAG, "识别状态: engine=${asrEngine?.name} error=$asrError")
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
                // 开始听指令：小球边缘亮起极光
                floatWindow?.setAurora(true)
                // 先把别的 App 的播放暂停，避免 TTS 和音乐混在一起、也减少回声
                val pausedMedia = com.voiceassistant.exec.MediaHelper.pauseIfPlaying(this)
                try {
                    handleUtterance()
                } catch (e: Exception) {
                    DebugLog.e(TAG, "处理指令失败", e)
                    AppStateBus.onResult("处理失败：${e.message}")
                } finally {
                    drainAudio()
                    wake?.reset()
                    state = State.LISTENING
                    // 指令处理完，极光熄灭、媒体恢复播放
                    floatWindow?.setAurora(false)
                    if (pausedMedia) com.voiceassistant.exec.MediaHelper.resume(this)
                }
            }
        }
    }

    /** 唤醒后：先随机应答一句，再 VAD 截句 → ASR → 意图 → 执行 → 播报 */
    private suspend fun handleUtterance() {
        if (settings.wakeAckEnabled && settings.ttsEnabled) {
            speakSafely(nextWakeAck())
            delay(120)
            // 丢掉应答的回声，避免把自己说的话当成指令
            drainAudio()
        }

        val samples = captureUtterance(MAX_UTTERANCE_MS)
        if (samples.size < AudioCapture.SAMPLE_RATE * MIN_UTTERANCE_MS / 1000) {
            DebugLog.d(TAG, "语音过短，忽略")
            return
        }

        state = State.PROCESSING
        val recognized = recognize(samples)
        if (recognized.isBlank()) {
            DebugLog.d(TAG, "ASR 结果为空")
            speakSafely(getString(com.voiceassistant.R.string.tts_asr_empty))
            return
        }
        // 用户常常一口气把唤醒词和指令连在一起说，ASR 会把唤醒词也带进来
        val text = stripWakeWord(recognized)

        // 结束对话类说法：不执行任何动作，回一句告别后继续待命
        if (settings.farewellEnabled && com.voiceassistant.nlu.DialogControl.isEndOfConversation(text)) {
            val bye = nextFarewell()
            AppStateBus.onResult("会话结束：$bye")
            speakSafely(bye)
            return
        }

        AppStateBus.onDebug(asr = text, parseState = "ASR 完成")
        DebugLog.d(TAG, "识别文本: $text")

        val plan = understand(text)
        if (plan == null) {
            // 兜底：让模型直接回答（聊天/查询类），只播报，不执行任何动作
            if (settings.cloudEnabled && settings.chatFallbackEnabled) {
                val answer = try {
                    cloud.chat(text, settings)
                } catch (e: Exception) {
                    DebugLog.e(TAG, "闲聊失败", e)
                    null
                }
                if (!answer.isNullOrBlank()) {
                    AppStateBus.onDebug(cloudRaw = answer, parseState = "闲聊回答")
                    AppStateBus.onResult("对话回答：$answer")
                    speakSafely(answer)
                    return
                }
            }
            speakSafely(getString(com.voiceassistant.R.string.tts_unknown_command))
            val reason = lastUnderstandError
            AppStateBus.onResult(
                "无法识别该指令（听到：$text）" + if (reason.isNotBlank()) "｜原因：$reason" else ""
            )
            return
        }

        AppStateBus.onRecognized(text, plan)
        execute(plan)
    }

    /** 本地规则优先；未命中且云端已开才请求云端；云端失败按设置决定是否回退 */
    private suspend fun understand(text: String): ActionPlan? {
        lastUnderstandError = ""
        RuleEngine.matchLocal(text)?.let { match ->
            DebugLog.d(TAG, "本地规则命中: ${match.plan.actions.size} 个动作")
            AppStateBus.onDebug(parseState = "本地规则命中")
            return match.plan
        }

        if (!settings.cloudEnabled) {
            AppStateBus.onDebug(parseState = "本地未命中，云端已关闭")
            DebugLog.d(TAG, "本地未命中且云端关闭")
            lastUnderstandError = "本地规则没匹配上，云端未启用"
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
            is ParseOutcome.Failure -> {
                DebugLog.w(TAG, "云端解析失败: ${outcome.reason}")
                lastUnderstandError = "云端返回解析失败：${outcome.reason}"
            }
            null -> {
                DebugLog.w(TAG, "云端调用失败: ${result.error?.kind} ${result.error?.detail}")
                lastUnderstandError = "云端调用失败：${result.error?.kind?.userMessage ?: "未知"}"
            }
        }

        // 降级：再走一次本地规则（云端已失败，这里给用户一次本地兜底的机会）
        if (settings.cloudAutoFallback) {
            RuleEngine.matchLocal(text)?.let {
                AppStateBus.onDebug(parseState = "云端失败，已降级到本地规则")
                return it.plan
            }
        }
        if (lastUnderstandError.isBlank()) lastUnderstandError = "云端没有给出可执行的动作"
        return null
    }

    private suspend fun execute(plan: ActionPlan) {
        val outcome = Dispatcher(
            context = applicationContext,
            settings = settings,
            confirm = { question -> askQuestion(question) },
            choosePlace = { places -> askPlaceChoice(places) },
            chooseOption = { options -> askChoice(options) }
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

    // ---------------- 导航候选选择 ----------------

    /** 导航有多个候选地点：界面点选和语音选择同时进行，谁先给结果用谁 */
    private suspend fun askPlaceChoice(
        places: List<com.voiceassistant.exec.NavigationExecutor.Place>
    ): com.voiceassistant.exec.NavigationExecutor.Place? {
        if (places.isEmpty()) return null
        if (places.size == 1) return places.first()

        val result = kotlinx.coroutines.CompletableDeferred<Int?>()
        val index = kotlinx.coroutines.coroutineScope {
            val uiJob = launch {
                val picked = com.voiceassistant.exec.PlaceChoiceBridge.ask(this@WakeService, places)
                // 只有界面真的弹出来了才把 null 当成"用户取消"
                if (com.voiceassistant.exec.PlaceChoiceBridge.startedOk && !result.isCompleted) {
                    result.complete(picked)
                }
            }
            val voiceJob = launch {
                val picked = listenForPlace(places)
                if (picked != null && !result.isCompleted) {
                    result.complete(picked)
                    // 语音已经选完了，把弹出的界面关掉
                    com.voiceassistant.exec.PlaceChoiceBridge.requestClose()
                }
            }
            val idx = withTimeoutOrNull(30_000L) { result.await() }
            uiJob.cancel()
            voiceJob.cancel()
            com.voiceassistant.exec.PlaceChoiceBridge.requestClose()
            idx
        }
        return index?.let { places.getOrNull(it) }
    }

    /** 只问"第几个"，不念地名；返回序号，没听清返回 null */
    private suspend fun listenForPlace(
        places: List<com.voiceassistant.exec.NavigationExecutor.Place>
    ): Int? {
        val question = "找到${places.size}个结果，请问要去第几个？"
        state = State.SPEAKING
        speakSafely(question)
        delay(150)
        drainAudio()

        state = State.CONFIRMING
        val samples = captureUtterance(15_000, requireSpeech = false)
        if (samples.size < AudioCapture.SAMPLE_RATE * 300 / 1000) return null
        val answer = recognize(samples)
        DebugLog.d(TAG, "地点选择回答: $answer")

        Regex("[1-9]").find(answer)?.value?.toIntOrNull()?.let { n ->
            if (n in places.indices) return n
        }
        val chineseNumbers = mapOf('一' to 0, '二' to 1, '两' to 1, '三' to 2, '四' to 3, '五' to 4)
        answer.firstOrNull { chineseNumbers.containsKey(it) }?.let { ch ->
            val n = chineseNumbers[ch] ?: return@let
            if (n in places.indices) return n
        }
        places.indexOfFirst { p ->
            answer.contains(p.name) || (p.name.length >= 2 && answer.contains(p.name.take(2)))
        }.let { if (it >= 0) return it }
        return null
    }

    /** 通用「说第几个」选择（微信同名联系人等）；超时返回 null */
    private suspend fun askChoice(options: List<String>): Int? {
        if (options.isEmpty()) return null
        if (options.size == 1) return 0

        state = State.SPEAKING
        speakSafely("有 ${options.size} 个匹配，说第几个？")
        delay(150)
        drainAudio()

        state = State.CONFIRMING
        val samples = captureUtterance(10_000, requireSpeech = false)
        if (samples.size < AudioCapture.SAMPLE_RATE * 300 / 1000) return null
        val answer = recognize(samples)
        DebugLog.d(TAG, "选择回答: $answer")

        Regex("[1-9]").find(answer)?.value?.toIntOrNull()?.let { n ->
            if (n in 1..options.size) return n - 1
        }
        val chineseNumbers = mapOf('一' to 0, '二' to 1, '两' to 1, '三' to 2, '四' to 3, '五' to 4)
        answer.firstOrNull { chineseNumbers.containsKey(it) }?.let { ch ->
            chineseNumbers[ch]?.let { i -> if (i in options.indices) return i }
        }
        options.indexOfFirst { it.isNotBlank() && answer.contains(it) }
            .let { if (it >= 0) return it }
        return null
    }

    // ---------------- 二次确认（语音） ----------------

    private suspend fun askQuestion(question: String): Boolean {
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

    /** 去掉开头混进来的唤醒词以及它后面的标点 */
    private fun stripWakeWord(text: String): String {
        val candidates = buildList {
            add(settings.wakeKeyword)
            addAll(AppStateBus.state.value.wakeKeywords)
        }.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }

        var result = text
        for (word in candidates) {
            if (result.startsWith(word)) {
                result = result.removePrefix(word)
                break
            }
        }
        return result.trimStart('，', ',', '。', ' ', '、', '！', '!', '？', '?')
    }

    private suspend fun recognize(samples: FloatArray): String {        val engine = asr ?: return ""
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
        // 关键：WindowManager 加 View 必须在主线程，否则抛
        // "Can't create handler inside thread that has not called Looper.prepare()"
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (floatWindow?.isVisible == true) return@post
            val window = FloatWindow(
                context = this,
                alpha = settings.floatBallAlpha,
                onSingleTap = {
                    // 单击：回桌面
                    com.voiceassistant.exec.A11yBridge.home()
                },
                onDoubleTap = {
                    // 双击：暂停/继续 + 打开语音助手
                    togglePause()
                    startActivity(
                        Intent(this, com.voiceassistant.ui.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
            if (window.show()) {
                window.setListening(!paused)
                floatWindow = window
                AppStateBus.setFloatBallError("")
            } else {
                AppStateBus.setFloatBallError(window.lastError ?: "未知原因，悬浮窗没加上")
                DebugLog.w(TAG, "悬浮球显示失败: ${window.lastError}")
            }
        }
    }

    /** 设置页「测试显示小球」用：重新读配置再试一次，把结果写回界面 */
    private fun showFloatBallForced() {
        scope.launch {
            settings = repo.current()
            hideFloatBall()
            showFloatBall()
            if (floatWindow?.isVisible != true && AppStateBus.state.value.floatBallError.isBlank()) {
                AppStateBus.setFloatBallError("没能显示出来：请确认已授予悬浮窗权限，且服务在运行")
            }
        }
    }

    private fun hideFloatBall() {
        val window = floatWindow ?: return
        floatWindow = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            window.hide()
        }
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
