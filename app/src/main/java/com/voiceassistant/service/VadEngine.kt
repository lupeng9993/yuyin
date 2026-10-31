package com.voiceassistant.service

import kotlin.math.max
import kotlin.math.sqrt

/**
 * VAD 抽象：喂 16 kHz float 帧，判断「有没有在说话」，并判断「这句话说完了没有」。
 *
 * 两个实现：
 * - [EnergyVad]：能量阈值，零依赖，永远可用（默认兜底）
 * - com.voiceassistant.service.vad.SileroVadEngine：模型推理，嘈杂环境更稳
 */
interface VadEngine {
    val name: String

    /** 送入一帧，返回 true 表示「有语音」 */
    fun accept(samples: FloatArray): Boolean

    /** 已经听到过声音，且静音超过阈值 → 一句话结束 */
    fun isUtteranceEnd(): Boolean

    fun reset()

    fun release() {}
}

class EnergyVad(
    /** 绝对能量门槛，低于此值一律当静音 */
    private val threshold: Float = 0.012f,
    /** 静音多少毫秒算一句话结束 */
    private val silenceMs: Int = 600
) : VadEngine {

    override val name: String = "energy-vad"

    private var lastVoiceAt = 0L
    private var sawVoice = false
    private var noiseFloor = 0.002f

    override fun accept(samples: FloatArray): Boolean {
        val rms = rms(samples)
        // 噪声底自适应：静音时缓慢跟随环境噪声，避免安静房间被误触发、嘈杂环境漏检
        if (rms < threshold) {
            noiseFloor = (noiseFloor * 0.98f) + (rms * 0.02f)
        }
        val effective = max(threshold, noiseFloor * 2.5f)
        val voiced = rms > effective

        if (voiced) {
            sawVoice = true
            lastVoiceAt = System.currentTimeMillis()
        }
        return voiced
    }

    override fun isUtteranceEnd(): Boolean {
        if (!sawVoice) return false
        return System.currentTimeMillis() - lastVoiceAt > silenceMs
    }

    override fun reset() {
        sawVoice = false
        lastVoiceAt = 0
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (v in samples) sum += (v * v).toDouble()
        return sqrt(sum / samples.size).toFloat()
    }
}
