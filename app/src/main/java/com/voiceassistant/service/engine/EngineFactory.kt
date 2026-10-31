package com.voiceassistant.service.engine

import android.content.Context
import com.voiceassistant.data.AppSettings
import com.voiceassistant.data.TtsEngine
import com.voiceassistant.log.DebugLog
import com.voiceassistant.service.asr.SherpaAsrEngine
import com.voiceassistant.service.tts.SherpaTtsEngine
import com.voiceassistant.service.tts.SystemTtsEngine
import com.voiceassistant.service.VadEngine
import com.voiceassistant.service.EnergyVad
import com.voiceassistant.service.vad.SileroVadEngine
import com.voiceassistant.service.wake.KwsEngine
import java.io.File

/**
 * 引擎装配：按当前配置创建三个引擎，缺模型时降级而不是崩溃。
 *
 * 原则：任何一项缺失都要让 App 还能跑——只是那一环失效，并留下明确的日志。
 */
object EngineFactory {

    private const val TAG = "EngineFactory"

    fun createWake(context: Context, settings: AppSettings): WakeEngine {
        val dir = settings.wakeModelDir
        if (!settings.wakeEnabled) {
            DebugLog.d(TAG, "唤醒未启用")
            return NoopWakeEngine("唤醒未启用")
        }
        if (dir.isBlank() || !File(dir).exists()) {
            DebugLog.w(TAG, "唤醒模型目录无效: $dir")
            return NoopWakeEngine("未导入唤醒模型")
        }
        return try {
            KwsEngine(context, dir, settings.wakeThreshold, settings.wakeKeywordLine)
        } catch (e: Exception) {
            DebugLog.e(TAG, "创建唤醒引擎失败", e)
            NoopWakeEngine("唤醒引擎初始化失败")
        }
    }

    fun createAsr(context: Context, settings: AppSettings): AsrEngine {
        val dir = settings.asrModelDir
        if (!settings.asrEnabled) {
            DebugLog.d(TAG, "本地识别未启用")
            return NoopAsrEngine("本地识别未启用")
        }
        if (dir.isBlank() || !File(dir).exists()) {
            DebugLog.w(TAG, "识别模型目录无效: $dir")
            return NoopAsrEngine("未导入识别模型")
        }
        return try {
            SherpaAsrEngine(context, dir)
        } catch (e: Exception) {
            DebugLog.e(TAG, "创建识别引擎失败", e)
            NoopAsrEngine("识别引擎初始化失败")
        }
    }

    /** VAD：有 Silero 模型就用模型，没有就退回能量 VAD（保证任何情况下都能截句） */
    fun createVad(context: Context, settings: AppSettings): VadEngine {
        val path = settings.vadModelPath
        if (path.isNotBlank() && File(path).exists()) {
            try {
                val engine = SileroVadEngine(path, settings.vadSilenceMs)
                if (engine.isReady) {
                    DebugLog.d(TAG, "使用 Silero VAD")
                    return engine
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "Silero VAD 初始化失败，回退能量 VAD", e)
            }
        }
        DebugLog.d(TAG, "使用能量 VAD")
        return EnergyVad(silenceMs = settings.vadSilenceMs)
    }

    fun createSpeak(context: Context, settings: AppSettings): SpeakEngine {
        if (!settings.ttsEnabled) return NoopSpeakEngine()

        if (settings.ttsEngine == TtsEngine.SHERPA) {
            val dir = settings.ttsModelDir
            if (dir.isNotBlank() && File(dir).exists()) {
                try {
                    val engine = SherpaTtsEngine(
                        context = context,
                        modelDir = dir,
                        speakerId = settings.ttsSpeakerId,
                        speed = settings.ttsSpeed,
                        volume = settings.ttsVolume
                    )
                    if (engine.isReady()) return engine
                    DebugLog.w(TAG, "离线 TTS 未就绪，回退系统 TTS")
                } catch (e: Exception) {
                    DebugLog.e(TAG, "创建离线 TTS 失败，回退系统 TTS", e)
                }
            } else {
                DebugLog.w(TAG, "TTS 模型目录无效: $dir，回退系统 TTS")
            }
        }

        return SystemTtsEngine(context, settings.ttsSpeed)
    }
}
