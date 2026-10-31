package com.voiceassistant.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.voiceassistant.log.DebugLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 极薄的 HTTP 层：只负责发请求、把异常翻译成 [CloudErrorKind]，并把耗时写进日志。
 */
object HttpEngine {

    private const val TAG = "CloudHttp"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun postJson(
        url: String,
        apiKey: String,
        body: String,
        timeoutSec: Int
    ): CloudCall = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toRequestBody(jsonMedia))
            .build()

        val call = client.newCall(request)
        call.timeout().timeout(timeoutSec.toLong().coerceIn(5, 120), TimeUnit.SECONDS)

        val path = url.substringBefore('?')
        val startedAt = System.currentTimeMillis()
        DebugLog.d(TAG, "POST $path timeout=${timeoutSec}s body=${body.length}B")

        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                val cost = System.currentTimeMillis() - startedAt
                DebugLog.d(TAG, "← HTTP ${response.code} ${cost}ms ${text.length}B")
                if (response.isSuccessful) {
                    CloudCall(content = text, httpCode = response.code, rawBody = text)
                } else {
                    CloudCall(
                        error = CloudError(kindOf(response.code), snippet(text), response.code),
                        httpCode = response.code,
                        rawBody = text
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            CloudCall(error = CloudError(CloudErrorKind.TIMEOUT, "${timeoutSec}s 内没收到响应"))
        } catch (e: InterruptedIOException) {
            // OkHttp 的 call timeout 抛的是 InterruptedIOException("timeout")
            val cost = System.currentTimeMillis() - startedAt
            CloudCall(error = CloudError(CloudErrorKind.TIMEOUT, "请求超时（${cost}ms，上限 ${timeoutSec}s）"))
        } catch (e: UnknownHostException) {
            CloudCall(error = CloudError(CloudErrorKind.NETWORK, "无法解析域名 ${e.message.orEmpty()}"))
        } catch (e: ConnectException) {
            CloudCall(error = CloudError(CloudErrorKind.NETWORK, "无法连接服务器"))
        } catch (e: IOException) {
            CloudCall(error = CloudError(CloudErrorKind.NETWORK, e.message ?: "网络异常"))
        } catch (e: Exception) {
            CloudCall(error = CloudError(CloudErrorKind.UNKNOWN, e.message ?: e.javaClass.simpleName))
        } finally {
            call.cancel()
        }
    }

    private fun kindOf(code: Int): CloudErrorKind = when {
        code == 401 -> CloudErrorKind.AUTH
        code == 403 -> CloudErrorKind.FORBIDDEN
        code == 429 -> CloudErrorKind.RATE_LIMIT
        code in 500..599 -> CloudErrorKind.SERVER
        code == 400 -> CloudErrorKind.BAD_RESPONSE
        else -> CloudErrorKind.UNKNOWN
    }

    private fun snippet(text: String, max: Int = 300): String =
        if (text.length <= max) text else text.substring(0, max) + "…"
}
