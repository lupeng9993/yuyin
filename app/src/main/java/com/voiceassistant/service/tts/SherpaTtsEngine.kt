package com.voiceassistant.service.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.voiceassistant.log.DebugLog
import com.voiceassistant.service.engine.SpeakEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * sherpa-onnx 离线 TTS（VITS / Matcha 等）。
 *
 * 三条播放约束（文档要求）：
 * 1. 播放采样率 = 模型输出采样率，全程不重采样；
 * 2. 长文本按标点分段，边合成边播，降低首包延迟；
 * 3. 推理线程 2。
 */
class SherpaTtsEngine(
    context: Context,
    modelDir: String,
    private val speakerId: Int = 0,
    private val speed: Float = 1.0f,
    private val volume: Float = 1.0f
) : SpeakEngine {

    override val name: String = "sherpa-tts"

    private companion object {
        const val TAG = "SherpaTts"
        const val MAX_CHUNK = 60
        const val NUM_THREADS = 2
    }

    private val mutex = Mutex()
    private var tts: OfflineTts? = null
    private var sampleRate: Int = 22_050

    init {
        try {
            val dir = File(modelDir)
            val model = pickModel(dir)
            val tokens = File(dir, "tokens.txt")
            val lexicon = File(dir, "lexicon.txt")

            if (model == null || !tokens.exists()) {
                DebugLog.w(TAG, "TTS 模型不完整: ${dir.absolutePath}")
            } else {
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = model.absolutePath,
                            tokens = tokens.absolutePath,
                            lexicon = if (lexicon.exists()) lexicon.absolutePath else ""
                        ),
                        numThreads = NUM_THREADS,
                        debug = false,
                        provider = "cpu"
                    ),
                    maxNumSentences = 1
                )
                val engine = OfflineTts(config = config)
                tts = engine
                sampleRate = engine.sampleRate()
                DebugLog.d(TAG, "TTS 就绪: ${model.name} @ ${sampleRate}Hz 发音人=${engine.numSpeakers()}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化 TTS 失败", e)
            tts = null
        }
    }

    fun isReady(): Boolean = tts != null

    override suspend fun speak(text: String) {
        val engine = tts ?: return
        if (text.isBlank()) return

        val chunks = splitText(text)
        mutex.withLock {
            for (chunk in chunks) {
                try {
                    val audio = withContext(Dispatchers.Default) {
                        engine.generate(chunk, speakerId, speed)
                    }
                    if (audio.samples.isNotEmpty()) {
                        play(audio.samples, audio.sampleRate)
                    }
                } catch (e: Exception) {
                    DebugLog.e(TAG, "合成/播放失败: $chunk", e)
                }
            }
        }
    }

    /** 按中英文标点分段，控制单段长度，边合成边播 */
    private fun splitText(text: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()

        fun flush() {
            val piece = sb.toString().trim()
            if (piece.isNotEmpty()) result.add(piece)
            sb.setLength(0)
        }

        for (ch in text) {
            sb.append(ch)
            val boundary = ch in "。！？!?；;\n"
            if (boundary || sb.length >= MAX_CHUNK) flush()
        }
        flush()
        return if (result.isEmpty()) listOf(text) else result
    }

    private fun play(samples: FloatArray, rate: Int) {
        val minBuffer = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferSize = max(minBuffer, samples.size * 4)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            DebugLog.e(TAG, "创建 AudioTrack 失败", e)
            return
        }

        try {
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            // 等待缓冲播完再释放，否则尾音会被截掉
            track.stop()
        } catch (e: Exception) {
            DebugLog.e(TAG, "播放失败", e)
        } finally {
            try {
                track.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun pickModel(dir: File): File? {
        val candidates = listOf("model.onnx", "model.int8.onnx", "model_fp16.onnx")
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists()) return f
        }
        return dir.listFiles()?.firstOrNull { it.name.endsWith(".onnx", true) }
    }

    override fun release() {
        try {
            tts?.release()
        } catch (e: Exception) {
            DebugLog.w(TAG, "释放失败: ${e.message}")
        }
        tts = null
    }
}
