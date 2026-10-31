package com.voiceassistant.service.vad

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.voiceassistant.log.DebugLog
import com.voiceassistant.service.VadEngine
import java.io.File

/**
 * Silero VAD（sherpa-onnx 内置）。
 *
 * 相比能量 VAD，它对环境噪音、空调声、键盘声更稳，不会把背景噪声当成说话。
 * 模型文件（silero_vad.onnx）由用户导入；缺失时自动退回能量 VAD。
 *
 * API 事实（v2.0.1）：
 * - `Vad(config = VadModelConfig(sileroVadModelConfig = ..., sampleRate = 16000))`
 * - `acceptWaveform(samples)` → `isSpeechDetected()`；复位用 `clear()`
 * - `SileroVadModelConfig` 没有 sampleRate / bufferSizeInSeconds 字段
 */
class SileroVadEngine(
    modelPath: String,
    private val silenceMs: Int = 600,
    private val threshold: Float = 0.5f
) : VadEngine {

    override val name: String = "silero-vad"

    private companion object {
        const val TAG = "SileroVad"
        const val SAMPLE_RATE = 16_000
    }

    private var vad: Vad? = null
    private var lastVoiceAt = 0L
    private var sawVoice = false

    init {
        try {
            if (!File(modelPath).exists()) {
                DebugLog.w(TAG, "VAD 模型不存在: $modelPath")
            } else {
                val config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = modelPath,
                        threshold = threshold,
                        minSilenceDuration = silenceMs / 1000f,
                        minSpeechDuration = 0.2f,
                        maxSpeechDuration = 15.0f,
                        windowSize = 512
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
                vad = Vad(config = config)
                DebugLog.d(TAG, "Silero VAD 就绪: $modelPath")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化 Silero VAD 失败", e)
            vad = null
        }
    }

    val isReady: Boolean get() = vad != null

    override fun accept(samples: FloatArray): Boolean {
        val engine = vad ?: return false
        return try {
            engine.acceptWaveform(samples)
            val speech = engine.isSpeechDetected()
            if (speech) {
                sawVoice = true
                lastVoiceAt = System.currentTimeMillis()
            }
            speech
        } catch (e: Exception) {
            DebugLog.e(TAG, "VAD 推理异常", e)
            false
        }
    }

    override fun isUtteranceEnd(): Boolean {
        if (!sawVoice) return false
        return System.currentTimeMillis() - lastVoiceAt > silenceMs
    }

    override fun reset() {
        sawVoice = false
        lastVoiceAt = 0
        try {
            vad?.clear()
        } catch (e: Exception) {
            DebugLog.w(TAG, "复位失败: ${e.message}")
        }
    }

    override fun release() {
        try {
            vad?.release()
        } catch (e: Exception) {
            DebugLog.w(TAG, "释放失败: ${e.message}")
        }
        vad = null
    }
}
