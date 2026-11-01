package com.voiceassistant.service.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.voiceassistant.log.DebugLog
import com.voiceassistant.service.engine.AsrEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * sherpa-onnx 离线识别（SenseVoice / Paraformer 等）。
 *
 * 模型文件按实际名字动态查找，不写死：
 * - 优先 `model.int8.onnx`（体积小、手机友好），其次 `model.onnx`
 * - `tokens.txt` 必需
 */
class SherpaAsrEngine(
    context: Context,
    modelDir: String,
    private val numThreads: Int = 2
) : AsrEngine {

    override val name: String = "sherpa-sensevoice"

    private companion object {
        const val TAG = "SherpaAsr"
    }

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private val dir = File(modelDir)

    /** 初始化失败原因（模型损坏、文件缺失等），非空说明识别一定不可用 */
    var initError: String? = null
        private set

    fun ready(): Boolean = recognizer != null

    init {
        try {
            val modelFile = pickFile(dir, listOf("model.int8.onnx", "model.onnx", "model.onnx.quant"))
            val tokensFile = File(dir, "tokens.txt")
            if (modelFile != null && tokensFile.exists()) {
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = modelFile.absolutePath,
                            language = "auto",
                            useInverseTextNormalization = true
                        ),
                        tokens = tokensFile.absolutePath,
                        numThreads = numThreads,
                        debug = false,
                        provider = "cpu"
                    ),
                    decodingMethod = "greedy_search"
                )
                recognizer = OfflineRecognizer(config = config)
                DebugLog.d(TAG, "识别引擎就绪: ${modelFile.name}")
            } else {
                initError = "模型目录里缺 model.onnx / tokens.txt"
                DebugLog.w(TAG, "模型不完整: ${dir.absolutePath}")
            }
        } catch (e: Exception) {
            // 最常见的就是下载不完整：Protobuf parsing failed
            initError = "识别模型损坏或不可读（${e.message?.take(80)}），请删除后重新下载"
            DebugLog.e(TAG, "初始化识别引擎失败", e)
            recognizer = null
        }
    }

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val engine = recognizer ?: return ""
        if (samples.isEmpty()) return ""
        return mutex.withLock {
            var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
            try {
                stream = engine.createStream()
                stream.acceptWaveform(samples, sampleRate)
                engine.decode(stream)
                engine.getResult(stream).text
            } catch (e: Exception) {
                DebugLog.e(TAG, "识别失败", e)
                ""
            } finally {
                stream?.release()
            }
        }
    }

    override fun release() {
        try {
            recognizer?.release()
        } catch (e: Exception) {
            DebugLog.w(TAG, "释放失败: ${e.message}")
        }
        recognizer = null
    }

    private fun pickFile(dir: File, candidates: List<String>): File? {
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists()) return f
        }
        // 兜底：目录里任意 model*.onnx
        return dir.listFiles()?.firstOrNull {
            it.name.startsWith("model") && it.name.endsWith(".onnx")
        }
    }
}
