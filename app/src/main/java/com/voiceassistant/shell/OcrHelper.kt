package com.voiceassistant.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.voiceassistant.log.DebugLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * 截图 + OCR。
 *
 * 车机版微信是自绘界面，无障碍和 uiautomator 都读不到节点，
 * 只能「拍屏 → 认出文字和坐标 → 按坐标点」。
 *
 * 截图用 shell 的 screencap，存到应用自己的外部目录（shell 可写、应用可读，不需要存储权限）。
 */
object OcrHelper {

    private const val TAG = "Ocr"

    data class Item(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
        val label: String get() = "'$text' [$left,$top][$right,$bottom] 中心($cx,$cy)"
    }

    /** 截屏并存到应用目录，返回文件；失败返回 null */
    fun capture(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "shot.png")
        if (file.exists()) file.delete()
        val out = ShizukuShell.exec("screencap -p ${file.absolutePath}", context)
        if (!file.exists() || file.length() < 1000) {
            DebugLog.w(TAG, "截图失败：${out.take(120)} (file=${file.absolutePath})")
            return null
        }
        DebugLog.d(TAG, "截图成功 ${file.length() / 1024}KB → ${file.absolutePath}")
        return file
    }

    /** 识别截图里的文字 */
    suspend fun recognize(file: File): List<Item> = suspendCancellableCoroutine { cont ->
        val bitmap: Bitmap? = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) {
            if (cont.isActive) cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val items = ArrayList<Item>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val r = line.boundingBox ?: continue
                        items.add(Item(line.text, r.left, r.top, r.right, r.bottom))
                    }
                }
                recognizer.close()
                if (cont.isActive) cont.resume(items)
            }
            .addOnFailureListener { e ->
                DebugLog.w(TAG, "OCR 失败: ${e.message}")
                recognizer.close()
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    /** 截屏 + 识别+日志输出，供调试指令使用 */
    suspend fun captureAndLog(context: Context): Int {
        val file = capture(context) ?: return -1
        val items = recognize(file)
        DebugLog.d(TAG, "===== OCR 结果 ${items.size} 行 =====")
        items.forEach { DebugLog.d(TAG, "  " + it.label) }
        DebugLog.d(TAG, "===== OCR 结束 =====")
        file.delete()
        return items.size
    }
}
