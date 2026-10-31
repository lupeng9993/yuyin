package com.voiceassistant.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量日志：Logcat + 内存环形缓冲（供界面展示）+ 滚动文件（供导出）。
 *
 * 滚动策略：单文件 512 KB，保留 4 个历史文件，合计不超过 ~2.5 MB。
 */
object DebugLog {

    data class Entry(val time: Long, val level: String, val tag: String, val message: String) {
        fun format(): String = "${timeFormat.format(Date(time))} $level/$tag: $message"
    }

    private const val MAX_MEMORY_ENTRIES = 500
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_BACKUP_FILES = 4
    private const val LOGCAT_TAG = "VoiceAssistant"

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logDir = dir
    }

    fun d(tag: String, message: String) {
        Log.d(LOGCAT_TAG, "[$tag] $message")
        append("D", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(LOGCAT_TAG, "[$tag] $message")
        append("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(LOGCAT_TAG, "[$tag] $message", throwable)
        append("E", tag, message + (throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private fun append(level: String, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_MEMORY_ENTRIES) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
        writeToFile(entry)
    }

    private fun writeToFile(entry: Entry) {
        val dir = logDir ?: return
        try {
            val file = File(dir, "va.log")
            if (file.exists() && file.length() > MAX_FILE_BYTES) rotate(dir)
            file.appendText(entry.format() + "\n")
        } catch (e: Exception) {
            Log.w(LOGCAT_TAG, "写日志失败", e)
        }
    }

    private fun rotate(dir: File) {
        for (i in MAX_BACKUP_FILES downTo 1) {
            val src = if (i == 1) File(dir, "va.log") else File(dir, "va.$i.log")
            val dst = File(dir, "va.${i + 1}.log")
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
    }

    /** 把当前所有日志文件合并导出成一个文件，返回导出路径 */
    fun export(context: Context): File? {
        val dir = logDir ?: return null
        val target = File(context.cacheDir, "va-log-${System.currentTimeMillis()}.txt")
        return try {
            target.bufferedWriter().use { out ->
                out.write("本地语音助手 运行日志\n")
                out.write("导出时间: ${timeFormat.format(Date())}\n")
                out.write("=".repeat(60) + "\n")
                val files = listOf(
                    File(dir, "va.4.log"), File(dir, "va.3.log"), File(dir, "va.2.log"),
                    File(dir, "va.1.log"), File(dir, "va.log")
                )
                for (f in files) {
                    if (f.exists()) {
                        out.write("\n---- ${f.name} ----\n")
                        f.forEachLine { out.write(it + "\n") }
                    }
                }
                out.write("\n---- 内存缓冲（最近 ${_entries.value.size} 条）----\n")
                _entries.value.forEach { out.write(it.format() + "\n") }
            }
            target
        } catch (e: Exception) {
            Log.e(LOGCAT_TAG, "导出日志失败", e)
            null
        }
    }

    fun clearMemory() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }
}
