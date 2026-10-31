package com.voiceassistant.service.wake

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.voiceassistant.log.DebugLog
import com.voiceassistant.service.engine.WakeEngine
import java.io.File

/**
 * sherpa-onnx 关键词唤醒（KWS）。
 *
 * 唤醒词是纯文本：模型目录里的 `keywords.txt` 每行一个关键词，
 * 形如 `<BPE token> ... @<显示文本>`。改唤醒词只要改这个文件，不需要重新训练。
 *
 * 关键 API 事实（v2.0.1）：
 * - 没有 `stream.isDetected()`，用 `isReady / decode / getResult().keyword`
 * - `acceptWaveform(samples, sampleRate)`，samples 在前
 * - 检出后必须 `kws.reset(stream)` 才能继续检测同一个词
 */
class KwsEngine(
    private val context: Context,
    private val modelDir: String,
    private val threshold: Float
) : WakeEngine {

    override val name: String = "sherpa-kws"

    private companion object {
        const val TAG = "KwsEngine"
        const val SAMPLE_RATE = 16_000
    }

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var keywordsText: String = ""

    init {
        try {
            val dir = File(modelDir)
            val encoder = pick(dir, "encoder")
            val decoder = pick(dir, "decoder")
            val joiner = pick(dir, "joiner")
            val tokens = File(dir, "tokens.txt")

            if (encoder == null || decoder == null || joiner == null || !tokens.exists()) {
                DebugLog.w(TAG, "唤醒模型不完整: ${dir.absolutePath}")
            } else {
                keywordsText = readKeywords(dir)
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
                    keywordsFile = File(dir, "keywords.txt").absolutePath,
                    keywordsThreshold = threshold,
                    keywordsScore = 1.5f,
                    numTrailingBlanks = 2
                )
                val kw = KeywordSpotter(config = config)
                spotter = kw
                createStream()
                DebugLog.d(TAG, "唤醒引擎就绪，关键词: ${keywordsText.take(60)}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化唤醒引擎失败", e)
            spotter = null
        }
    }

    private fun createStream() {
        val kw = spotter ?: return
        try {
            stream?.release()
            val s = kw.createStream(keywordsText)
            stream = if (s.ptr == 0L) {
                DebugLog.e(TAG, "创建 KWS stream 失败（关键词格式可能不对）")
                null
            } else {
                s
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "创建 stream 异常", e)
            stream = null
        }
    }

    override fun accept(samples: FloatArray, sampleRate: Int): Boolean {
        val kw = spotter ?: return false
        val s = stream ?: return false
        return try {
            s.acceptWaveform(samples, SAMPLE_RATE)
            var hit = false
            // 一帧里可能同时命中多个，循环直到解码器不再 ready
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
            DebugLog.w(TAG, "复位失败，重建 stream: ${e.message}")
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
        // 优先 int8（手机 CPU 更合适）
        return files.firstOrNull { it.name.contains("int8") } ?: files.firstOrNull()
    }

    /** 读取 keywords.txt；多行用 `/` 连接（sherpa-onnx 的多关键词分隔符） */
    private fun readKeywords(dir: File): String {
        val file = File(dir, "keywords.txt")
        return try {
            if (!file.exists()) return ""
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .joinToString("/")
        } catch (e: Exception) {
            DebugLog.w(TAG, "读取 keywords.txt 失败: ${e.message}")
            ""
        }
    }
}
