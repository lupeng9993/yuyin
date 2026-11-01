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
 * 应用内模型下载器。
 *
 * 支持两种来源：
 * 1. 官方 tar.bz2 包（默认），下载后解压；
 * 2. **自定义直链**：指向单个 .onnx / .txt 文件（用于 GitHub 连不上时走镜像或自己搭的地址）。
 *
 * 每次都先清空目标目录，避免上一次的残缺文件混进来——
 * "Protobuf parsing failed" 基本都是这么来的。
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
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
     * @param urlOverride 自定义直链；为空用 [ModelCatalog.Entry.url]
     * @return 模型目录；失败返回 null
     */
    suspend fun download(
        entry: ModelCatalog.Entry,
        urlOverride: String = "",
        onProgress: (Progress) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        cancelled = false
        val url = urlOverride.trim().ifBlank { entry.url }
        val targetDir = ModelRepository.dir(context, entry.kind, entry.id)

        try {
            // 干净重装：先清空，避免残缺文件残留
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
                DebugLog.d(TAG, "清空旧模型目录 ${targetDir.absolutePath}")
            }
            targetDir.mkdirs()

            val singleFile = url.endsWith(".onnx", true) || url.endsWith(".txt", true)
            if (singleFile) {
                val name = url.substringBefore('?').substringAfterLast('/')
                val dest = File(targetDir, name)
                DebugLog.d(TAG, "直链下载单个文件 $url → ${dest.name}")
                if (!downloadTo(url, dest, onProgress)) {
                    targetDir.deleteRecursively()
                    return@withContext null
                }
                onProgress(Progress(Progress.Stage.EXTRACT, 0, 0))
                return@withContext targetDir
            }

            val archive = File(context.cacheDir, "${entry.id}.tar.bz2")
            DebugLog.d(TAG, "开始下载 $url")
            if (!downloadTo(url, archive, onProgress)) {
                archive.delete()
                targetDir.deleteRecursively()
                return@withContext null
            }
            if (cancelled) {
                archive.delete()
                return@withContext null
            }

            onProgress(Progress(Progress.Stage.EXTRACT, 0, 0))
            DebugLog.d(TAG, "下载完成，开始解压到 ${targetDir.absolutePath}")
            val ok = extractBz2Tar(archive, targetDir)
            archive.delete()

            if (!ok) {
                targetDir.deleteRecursively()
                return@withContext null
            }

            // 完整性校验：没有任何 onnx，或最大 onnx 太小 → 判定失败
            val onnxFiles = targetDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
                .toList()
            if (onnxFiles.isEmpty()) {
                DebugLog.w(TAG, "解压后没有找到任何 .onnx 文件")
                targetDir.deleteRecursively()
                return@withContext null
            }
            val biggest = onnxFiles.maxOf { it.length() }
            if (biggest < 1_000_000L) {
                DebugLog.w(TAG, "模型文件过小（最大 ${biggest}B），判定为下载不完整")
                targetDir.deleteRecursively()
                return@withContext null
            }

            val flattened = flattenSingleDir(targetDir)
            DebugLog.d(TAG, "模型就绪: ${flattened.absolutePath}")
            flattened
        } catch (e: Exception) {
            DebugLog.e(TAG, "下载或解压失败", e)
            targetDir.deleteRecursively()
            null
        }
    }

    /** 下载到指定文件；失败返回 false */
    private fun downloadTo(
        url: String,
        dest: File,
        onProgress: (Progress) -> Unit
    ): Boolean {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                DebugLog.w(TAG, "下载失败 HTTP ${response.code}")
                return false
            }
            val total = response.body?.contentLength() ?: -1L
            val input = response.body?.byteStream() ?: return false
            var downloaded = 0L
            var lastReport = 0L

            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled) {
                        DebugLog.w(TAG, "下载被取消")
                        return false
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

            // 长度对不上 = 传输被截断，直接判失败，别让坏文件进模型目录
            if (total > 0 && downloaded != total) {
                DebugLog.w(TAG, "下载长度不符：$downloaded / $total")
                return false
            }
            return true
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

    /** 解压后如果只剩一层同名目录，把内容提上来 */
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
