package com.voiceassistant.model

import android.content.Context
import com.voiceassistant.log.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内模型下载器：下载 tar.bz2 → 解压到 filesDir/models/<kind>/<model>/。
 *
 * 全程不经过公共存储，不需要任何存储权限；失败时清理半成品，避免留下残缺模型。
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    data class Progress(val stage: Stage, val downloadedBytes: Long, val totalBytes: Long) {
        enum class Stage { DOWNLOAD, EXTRACT }

        val percent: Int
            get() = if (totalBytes <= 0) 0 else ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    /**
     * @return 解压后的模型目录；失败返回 null
     */
    suspend fun download(
        entry: ModelCatalog.Entry,
        onProgress: (Progress) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        cancelled = false
        val targetDir = ModelRepository.dir(context, entry.kind, entry.id)
        val archive = File(context.cacheDir, "${entry.id}.tar.bz2")

        try {
            DebugLog.d(TAG, "开始下载 ${entry.id}")
            val request = Request.Builder().url(entry.url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.w(TAG, "下载失败 HTTP ${response.code}")
                    return@withContext null
                }
                val total = response.body?.contentLength() ?: -1L
                val input = response.body?.byteStream() ?: return@withContext null
                var downloaded = 0L
                var lastReport = 0L

                archive.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) {
                            DebugLog.w(TAG, "下载被取消")
                            return@withContext null
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 300) {
                            lastReport = now
                            onProgress(Progress(Progress.Stage.DOWNLOAD, downloaded, total))
                        }
                    }
                }
                onProgress(Progress(Progress.Stage.DOWNLOAD, downloaded, total))
            }

            if (cancelled) return@withContext null

            // 解压阶段可能持续几十秒（230 MB 的模型更久），必须给用户一个明确状态
            onProgress(Progress(Progress.Stage.EXTRACT, 0, 0))
            DebugLog.d(TAG, "下载完成，开始解压到 ${targetDir.absolutePath}")
            val ok = extractBz2Tar(archive, targetDir)
            archive.delete()

            if (!ok) {
                targetDir.deleteRecursively()
                return@withContext null
            }

            // 有些包解压后多一层同名目录，这里把它拍平
            val flattened = flattenSingleDir(targetDir)
            DebugLog.d(TAG, "模型就绪: ${flattened.absolutePath}")
            flattened
        } catch (e: Exception) {
            DebugLog.e(TAG, "下载或解压失败", e)
            archive.delete()
            targetDir.deleteRecursively()
            null
        }
    }

    private fun extractBz2Tar(archive: File, targetDir: File): Boolean {
        targetDir.mkdirs()
        return try {
            BufferedInputStream(archive.inputStream()).use { buffered ->
                BZip2CompressorInputStream(buffered, true).use { bz2 ->
                    TarArchiveInputStream(bz2).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                // 去掉包内最外层目录（模型包统一是 <name>/... 结构）
                                val name = entry.name.substringAfter('/', entry.name)
                                if (name.isNotBlank()) {
                                    val outFile = File(targetDir, name)
                                    // 防目录穿越
                                    if (outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                                        outFile.parentFile?.mkdirs()
                                        outFile.outputStream().use { out -> tar.copyTo(out) }
                                    }
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "解压失败", e)
            false
        }
    }

    /** 如果解压后目录里只有一个子目录，把内容提到上层，方便按目录名识别模型 */
    private fun flattenSingleDir(dir: File): File {
        val children = dir.listFiles() ?: return dir
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            inner.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
            inner.delete()
        }
        return dir
    }
}
