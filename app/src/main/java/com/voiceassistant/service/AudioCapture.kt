package com.voiceassistant.service

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voiceassistant.log.DebugLog
import kotlin.math.sqrt

/**
 * 麦克风采集：固定 16 kHz / 单声道 / PCM16（文档硬性要求，全链路不再重采样）。
 *
 * 用 VOICE_RECOGNITION 源：各家 ROM 对它的回声消除和降噪更适合识别；
 * 不可用时回退到 MIC。
 */
class AudioCapture(
    private val onPcm: (ShortArray) -> Unit
) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioCapture"

        /** 计算 16 位 PCM 的 RMS（0..1），用于能量 VAD */
        fun rms(samples: ShortArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (s in samples) {
                val v = s / 32768.0
                sum += v * v
            }
            return sqrt(sum / samples.size).toFloat()
        }
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            DebugLog.e(TAG, "无法获取最小缓冲区大小: $minBuffer")
            return false
        }
        val bufferSize = minBuffer * 2

        record = createRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferSize)
            ?: createRecord(MediaRecorder.AudioSource.MIC, bufferSize)
            ?: return false

        return try {
            record!!.startRecording()
            running = true
            thread = Thread({ readLoop(bufferSize) }, "audio-capture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            DebugLog.d(TAG, "录音已启动 16kHz 单声道 buffer=$bufferSize")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "启动录音失败", e)
            release()
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(source: Int, bufferSize: Int): AudioRecord? {
        return try {
            val r = AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                r.release()
                null
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "创建 AudioRecord 失败 source=$source: ${e.message}")
            null
        }
    }

    private fun readLoop(bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2)
        while (running) {
            val read = try {
                record?.read(buffer, 0, buffer.size) ?: -1
            } catch (e: Exception) {
                DebugLog.e(TAG, "读取失败", e)
                -1
            }
            if (read > 0) {
                val chunk = buffer.copyOf(read)
                try {
                    onPcm(chunk)
                } catch (e: Exception) {
                    DebugLog.e(TAG, "回调异常", e)
                }
            } else if (read < 0) {
                DebugLog.w(TAG, "read 返回 $read，结束采集")
                break
            }
        }
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (e: Exception) {
            // stop 在未启动时会抛，忽略
        }
        thread?.join(300)
        thread = null
        release()
    }

    private fun release() {
        try {
            record?.release()
        } catch (e: Exception) {
            // ignore
        }
        record = null
    }
}

/** ShortArray → FloatArray（-1..1），sherpa-onnx 统一吃 float 样本 */
fun ShortArray.toFloatSamples(): FloatArray = FloatArray(size) { this[it] / 32768f }
