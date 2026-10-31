package com.voiceassistant.exec

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.voiceassistant.log.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把截屏 Bitmap 存入系统相册。Android 10+ 走 MediaStore，10 以下写公共 Pictures 目录。
 * 只写图片，不申请任何存储权限（10+ 沙盒内合法）。
 */
object ScreenshotSaver {

    private const val TAG = "Screenshot"
    private const val ALBUM = "VoiceAssistant"

    suspend fun save(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val name = "VA_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return@withContext null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                "相册/$ALBUM/$name"
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
                if (!dir.exists() && !dir.mkdirs()) return@withContext null
                val file = File(dir, name)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                file.absolutePath
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "保存截图失败", e)
            null
        } finally {
            bitmap.recycle()
        }
    }
}
