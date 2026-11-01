package com.voiceassistant.service.wake

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.voiceassistant.log.DebugLog
import com.voiceassistant.model.KwsKeywords
import com.voiceassistant.service.engine.WakeEngine
import java.io.File

/**
 * sherpa-onnx 关键词唤醒（KWS）。
 *
 * 唤醒词必须是模型词表里的 BPE 片段，手写中文是无效的
 * （日志里 `Failed to create native OnlineStream` 就是这个原因）。
 * 所以这里做两层保险：
 * 1. 用户自定义的关键词先试；建流失败就自动回退到模型自带的 keywords.txt，
 *    绝不因为一个错误的自定义词让整个唤醒彻底失效；
 * 2. 状态里带上 error 文案，界面能看出到底是哪一层出的问题。
 */
class KwsEngine(
    private val context: Context,
    private val modelDir: String,
    private val threshold: Float,
    /** 用户自定义的关键词行（多行）；空 = 只用模型自带的 keywords.txt */
    private val customLines: String = ""
) : WakeEngine {

    override val name: String = "sherpa-kws"

    private companion object {
        const val TAG = "KwsEngine"
        const val SAMPLE_RATE = 16_000
    }

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var keywordsText: String = ""

    /** 当前真正生效的关键词行 */
    var keywordLines: List<String> = emptyList()
        private set

    val keywordCount: Int get() = keywordLines.size

    /** 初始化过程中的告警/错误，界面会显示 */
    var error: String? = null
        private set

    init {
        initialize()
    }

    /** 初始化逻辑放在普通函数里：init 块里不允许 return */
    private fun initialize() {
        try {
            val dir = File(modelDir)
            val encoder = pick(dir, "encoder")
            val decoder = pick(dir, "decoder")
            val joiner = pick(dir, "joiner")
            val tokens = File(dir, "tokens.txt")

            if (encoder == null || decoder == null || joiner == null || !tokens.exists()) {
                error = "模型不完整：缺 encoder/decoder/joiner/tokens.txt"
                DebugLog.e(TAG, error!!)
                return
            }

            val modelLines = KwsKeywords.rawLines(modelDir)
            val custom = customLines.split('\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val keywordFile = KwsKeywords.fileOf(modelDir)
            if (!keywordFile.exists() && modelLines.isNotEmpty()) {
                keywordFile.writeText(modelLines.joinToString("\n") + "\n")
            }

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tokens.absolutePath,
                    modelType = "zipformer2"
                ),
                keywordsFile = keywordFile.absolutePath,
                keywordsThreshold = threshold,
                keywordsScore = 1.5f,
                numTrailingBlanks = 2
            )
            spotter = KeywordSpotter(config = config)

            // 先试自定义词，失败就回退模型自带词表
            when {
                custom.isNotEmpty() && tryStream(custom) -> {
                    keywordLines = custom
                    DebugLog.d(TAG, "使用自定义关键词 ${custom.size} 个")
                }
                modelLines.isNotEmpty() && tryStream(modelLines) -> {
                    keywordLines = modelLines
                    if (custom.isNotEmpty()) {
                        error = "自定义关键词无效（不是模型词表里的拼写），已回退到模型自带词表"
                        DebugLog.w(TAG, error!!)
                    }
                    DebugLog.d(TAG, "使用模型自带关键词 ${modelLines.size} 个")
                }
                else -> {
                    error = "关键词为空：模型包里没有 keywords.txt，自定义词也无效"
                    DebugLog.e(TAG, error!!)
                }
            }
        } catch (e: Exception) {
            error = "唤醒引擎初始化异常：${e.message}"
            DebugLog.e(TAG, "初始化唤醒引擎失败", e)
            spotter = null
        }
    }

    /** 用一组关键词试着建流，成功返回 true */
    private fun tryStream(lines: List<String>): Boolean {
        val kw = spotter ?: return false
        val text = lines.joinToString("/")
        if (text.isBlank()) return false
        return try {
            val s = kw.createStream(text)
            if (s.ptr == 0L) {
                DebugLog.w(TAG, "关键词无效，建流失败: ${text.take(60)}")
                false
            } else {
                stream?.release()
                stream = s
                keywordsText = text
                true
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "建流异常: ${e.message}（关键词 ${text.take(60)}）")
            false
        }
    }

    private fun createStream() {
        if (keywordsText.isNotBlank()) tryStream(keywordsText.split('/'))
    }

    override fun accept(samples: FloatArray, sampleRate: Int): Boolean {
        val kw = spotter ?: return false
        val s = stream ?: return false
        return try {
            s.acceptWaveform(samples, SAMPLE_RATE)
            var hit = false
            while (kw.isReady(s)) {
                kw.decode(s)
                val keyword = kw.getResult(s).keyword
                if (keyword.isNotBlank()) {
                    DebugLog.d(TAG, "命中唤醒词: $keyword")
                    kw.reset(s)
                    hit = true
                    break
                }
            }
            hit
        } catch (e: Exception) {
            DebugLog.e(TAG, "唤醒检测异常，重建 stream", e)
            createStream()
            false
        }
    }

    override fun reset() {
        val kw = spotter ?: return
        val s = stream ?: run {
            createStream()
            return
        }
        try {
            kw.reset(s)
        } catch (e: Exception) {
            DebugLog.w(TAG, "复位失败: ${e.message}")
            createStream()
        }
    }

    override fun release() {
        try {
            stream?.release()
        } catch (e: Exception) {
            // ignore
        }
        stream = null
        try {
            spotter?.release()
        } catch (e: Exception) {
            // ignore
        }
        spotter = null
    }

    private fun pick(dir: File, prefix: String): File? {
        val files = dir.listFiles()?.filter {
            it.name.startsWith(prefix) && it.name.endsWith(".onnx", true)
        } ?: return null
        return files.firstOrNull { it.name.contains("int8") } ?: files.firstOrNull()
    }
}
