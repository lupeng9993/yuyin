package com.voiceassistant.service.engine

/**
 * 唤醒引擎抽象：输入 16 kHz float 音频帧，命中返回 true。
 *
 * 当前唯一实现是 sherpa-onnx 的 Keyword Spotter（关键词是纯文本，改词不用重训）。
 * 保留这个接口是为了以后能换成 OpenWakeWord 等其它后端，服务层不用改。
 */
interface WakeEngine {
    val name: String

    /** 送入一帧音频（16 kHz 单声道 float），返回是否命中唤醒词 */
    fun accept(samples: FloatArray, sampleRate: Int): Boolean

    /** 命中后复位内部状态，准备下一轮 */
    fun reset()

    fun release()
}

/** 模型缺失时的占位实现：永远不命中，但不会崩 */
class NoopWakeEngine(private val reason: String = "唤醒模型未加载") : WakeEngine {
    override val name: String = "none"
    override fun accept(samples: FloatArray, sampleRate: Int): Boolean = false
    override fun reset() {}
    override fun release() {}

    fun reason(): String = reason
}

/** 离线语音识别抽象 */
interface AsrEngine {
    val name: String

    /** 对一段 16 kHz float 音频做离线识别，返回文本（可能为空） */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int): String

    fun release()
}

class NoopAsrEngine(private val reason: String = "ASR 模型未加载") : AsrEngine {
    override val name: String = "none"
    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): String = ""
    override fun release() {}

    fun reason(): String = reason
}

/** 语音播报抽象（双引擎：系统 TTS / sherpa-onnx 离线 TTS） */
interface SpeakEngine {
    val name: String

    /** 播报文本，挂起到播放结束 */
    suspend fun speak(text: String)

    fun release()
}

class NoopSpeakEngine : SpeakEngine {
    override val name: String = "none"
    override suspend fun speak(text: String) {}
    override fun release() {}
}
