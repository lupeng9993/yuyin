package com.voiceassistant.exec

import android.content.Context
import com.voiceassistant.log.DebugLog
import com.voiceassistant.shell.OcrHelper
import com.voiceassistant.shell.ShizukuShell
import com.voiceassistant.service.tts.SystemTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天消息朗读（实验）。
 *
 * 车机版微信读不到节点，只能截图 + OCR：把右栏里的文字行按纵向顺序排好，
 * 过滤掉时间戳、输入框提示等噪声，念给对方听。
 *
 * 目前实现「朗读当前聊天的最近几条」；自动检测新消息、语音回复在下一版接。
 */
class ChatReader(private val context: Context) {

    private companion object {
        const val TAG = "ChatReader"
        const val SCREEN_W = 1920
        const val SCREEN_H = 1200
    }

    /** 时间戳、状态栏这类噪声行 */
    private val noisePatterns = listOf(
        Regex("^\\d{1,2}:\\d{2}$"),
        Regex("^(上午|下午|中午|晚上|凌晨)\\d{1,2}:\\d{2}$"),
        Regex("^(昨天|前天|星期[一二三四五六日])$"),
        Regex("^\\d{1,2}月\\d{1,2}日.*$"),
        Regex("^[+＋]?\\d{1,3}$"),
        Regex("^(发送|按住说话|表情|更多|返回)$")
    )

    data class Message(val text: String, val cy: Int)

    /** 截屏 + OCR + 过滤，返回右栏的聊天文字行（从上到下） */
    suspend fun readMessages(): List<Message> = withContext(Dispatchers.IO) {
        if (!ShizukuShell.hasPermission()) {
            DebugLog.w(TAG, "没有 Shizuku 权限")
            return@withContext emptyList()
        }
        val file = OcrHelper.capture(context) ?: return@withContext emptyList()
        val items = OcrHelper.recognize(file)
        file.delete()

        items.asSequence()
            .filter { it.cx > SCREEN_W / 2 }             // 只看右栏
            .filter { it.cy > SCREEN_H / 10 }            // 去掉顶部标题栏
            .filter { it.cy < SCREEN_H * 0.86 }          // 去掉底部输入栏
            .filter { item -> noisePatterns.none { it.matches(item.text.trim()) } }
            .filter { it.text.trim().length >= 2 }
            .map { Message(it.text.trim(), it.cy) }
            .sortedBy { it.cy }
            .toList()
    }

    /** 基线文件：上次朗读到的最后一行，存外部目录，重启也记得 */
    private fun baselineFile(): java.io.File = java.io.File("/sdcard/VA-Models/read_baseline.txt")

    private fun loadBaseline(): String = try {
        val f = baselineFile()
        if (f.isFile) f.readText().trim() else ""
    } catch (e: Exception) {
        ""
    }

    private fun saveBaseline(line: String) {
        try {
            val f = baselineFile()
            f.parentFile?.mkdirs()
            f.writeText(line)
        } catch (e: Exception) {
            DebugLog.w(TAG, "写基线失败: ${e.message}")
        }
    }

    /** 朗读最近几条（增量：只念上次之后新增的行），返回实际念出来的内容 */
    suspend fun readAloud(limit: Int = 5): String {
        val messages = readMessages()
        if (messages.isEmpty()) {
            return "没读到消息，可能不在聊天界面"
        }

        val baseline = loadBaseline()
        val newOnes = if (baseline.isBlank()) {
            messages.takeLast(limit)
        } else {
            val idx = messages.indexOfLast { it.text == baseline }
            if (idx >= 0 && idx < messages.size - 1) messages.drop(idx + 1) else messages.takeLast(limit)
        }
        if (newOnes.isEmpty()) {
            return "没有新消息"
        }

        val recent = newOnes.takeLast(limit)
        val text = recent.joinToString("。") { it.text }
        DebugLog.d(TAG, "朗读 ${recent.size} 条（增量）：$text")
        saveBaseline(recent.last().text)

        val tts = SystemTtsEngine(context, speed = 1.0f)
        try {
            tts.speak(text)
        } finally {
            tts.release()
        }
        return "念了 ${recent.size} 条消息"
    }
}
