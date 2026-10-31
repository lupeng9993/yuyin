package com.voiceassistant.model

import android.content.Context
import android.net.Uri
import com.voiceassistant.log.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 模型类别：唤醒 / 识别 / 合成 */
enum class ModelKind(val dir: String, val label: String) {
    KWS("kws", "唤醒模型"),
    ASR("asr", "识别模型"),
    VAD("vad", "端点检测模型"),
    TTS("tts", "合成模型")
}

/**
 * 模型文件仓库。
 *
 * 三条硬约束：
 * 1. 模型一律放在应用私有目录，绝不打包进 APK；
 * 2. 目录里的 model.onnx / tokens.txt / lexicon.txt 等按文件实际名字动态识别，不写死；
 * 3. 用户可以用「导入」放任何自己训练/下载的模型进来。
 */
object ModelRepository {

    private const val TAG = "ModelRepo"

    fun root(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun dir(context: Context, kind: ModelKind, name: String = ""): File {
        val base = File(root(context), kind.dir).apply { mkdirs() }
        return if (name.isBlank()) base else File(base, name).apply { mkdirs() }
    }

    /** 列出某类下已安装的模型目录 */
    fun installed(context: Context, kind: ModelKind): List<File> {
        val base = dir(context, kind)
        return base.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }

    /** 判断一个模型目录是否至少含有一个 onnx 文件 */
    fun looksLikeModel(dir: File): Boolean {
        return dir.isDirectory && dir.listFiles()?.any { it.name.endsWith(".onnx", true) } == true
    }

    /**
     * 从系统文件选择器导入单个文件到模型目录。
     * 用户也可以选择把整个模型目录解压后手动放到 filesDir/models/<kind>/<name>/。
     */
    suspend fun importFile(
        context: Context,
        uri: Uri,
        kind: ModelKind,
        targetName: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            val targetDir = dir(context, kind, targetName)
            val fileName = queryDisplayName(context, uri) ?: "imported.bin"
            val target = File(targetDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            DebugLog.d(TAG, "导入模型文件: ${target.absolutePath}")
            target
        } catch (e: Exception) {
            DebugLog.e(TAG, "导入模型失败", e)
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 删除一个模型目录（重置配置时不删模型，只有用户主动删除才调用） */
    fun delete(context: Context, kind: ModelKind, name: String): Boolean {
        val target = File(dir(context, kind), name)
        return target.deleteRecursively()
    }
}
