package com.voiceassistant.log

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.concurrent.TimeUnit

/**
 * 把日志上传到用户自己填的地址。
 *
 * 约定很简单：POST 原始字节（默认 application/zip，导出的是日志打包文件），
 * 服务端收到即存。不依赖任何第三方服务，地址由用户自己提供。
 *
 * 成功判定：HTTP 2xx。
 */
object LogUploader {

    private const val TAG = "LogUpload"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 上传并返回给用户看的结果文本 */
    suspend fun upload(context: Context, url: String): String = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (target.isEmpty()) return@withContext "没有填写上传地址"
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return@withContext "上传地址必须以 http:// 或 https:// 开头"
        }

        val file = DebugLog.export(context)
            ?: return@withContext "没有可导出的日志文件"

        val body = try {
            file.asRequestBody("application/zip".toMediaTypeOrNull())
        } catch (e: Exception) {
            return@withContext "读取日志失败：${e.message}"
        }

        val request = Request.Builder()
            .url(target)
            .post(body)
            .header("X-Log-File", file.name)
            .build()

        return@withContext try {
            val started = System.currentTimeMillis()
            client.newCall(request).execute().use { resp ->
                val ms = System.currentTimeMillis() - started
                val text = if (resp.isSuccessful) {
                    "日志已上传（${resp.code}，${file.length() / 1024}KB，${ms}ms）"
                } else {
                    "上传失败 HTTP ${resp.code}（${ms}ms）"
                }
                DebugLog.d(TAG, text)
                text
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "上传异常: ${e.message}")
            "上传失败：${e.message}"
        }
    }
}
