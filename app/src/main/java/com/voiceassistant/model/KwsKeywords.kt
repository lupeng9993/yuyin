package com.voiceassistant.model

import com.voiceassistant.log.DebugLog
import java.io.File

/**
 * 唤醒关键词读取。
 *
 * KWS 的关键词不是「随便写个中文就行」——它必须是模型词表里的 BPE 片段，
 * 所以真正能唤醒哪些词，取决于模型包自带的 `keywords.txt`。
 * 这里把文件读出来给界面展示，让用户看得见「我的模型到底支持哪些唤醒词」。
 */
object KwsKeywords {

    private const val TAG = "KwsKeywords"

    data class Entry(val display: String, val rawLine: String)

    fun fileOf(modelDir: String): File = File(modelDir, "keywords.txt")

    fun read(modelDir: String): List<Entry> {
        if (modelDir.isBlank()) return emptyList()
        val file = fileOf(modelDir)
        if (!file.exists()) {
            DebugLog.w(TAG, "模型里没有 keywords.txt: ${file.absolutePath}")
            return emptyList()
        }
        return try {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val display = line.substringAfterLast('@', "").trim()
                    Entry(if (display.isNotEmpty()) display else line, line)
                }
        } catch (e: Exception) {
            DebugLog.e(TAG, "读取 keywords.txt 失败", e)
            emptyList()
        }
    }

    fun rawLines(modelDir: String): List<String> = read(modelDir).map { it.rawLine }

    /** 把用户自定义的关键词行写入模型目录，保证后续重启也能生效 */
    fun writeCustom(modelDir: String, line: String): Boolean {
        if (modelDir.isBlank() || line.isBlank()) return false
        return try {
            fileOf(modelDir).writeText(line.trim() + "\n")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "写入 keywords.txt 失败", e)
            false
        }
    }
}
