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

    /** 由设置里的「卸载不删模型」开关控制 */
    @Volatile
    var allowExternal: Boolean = false

    private const val TAG = "ModelRepo"

    /** 外部模型目录：卸载重装后仍然保留（需要「所有文件访问权限」） */
    private const val EXTERNAL_ROOT = "/sdcard/VA-Models"

    /** 是否已经拿到「所有文件访问权限」 */
    fun hasExternalPermission(): Boolean = try {
        android.os.Environment.isExternalStorageManager()
    } catch (e: Throwable) {
        false
    }

    /**
     * 模型根目录。
     *
     * 有「所有文件访问权限」时用 /sdcard/VA-Models —— 卸载重装模型还在；
     * 没有权限就退回私有目录（卸载会丢），保证功能不受影响。
     */
    fun root(context: Context): File {
        val external = File(EXTERNAL_ROOT)
        val usable = allowExternal && hasExternalPermission() &&
            (external.exists() || external.mkdirs())
        val base = if (usable) external else File(context.filesDir, "models")
        base.mkdirs()
        if (usable) DebugLog.d(TAG, "模型根目录（外部，卸载不丢）: ${base.absolutePath}")
        return base
    }

    /** 当前实际使用的模型根目录，供界面显示 */
    fun rootPath(context: Context): String = root(context).absolutePath

    fun dir(context: Context, kind: ModelKind, name: String = ""): File {
        val base = File(root(context), kind.dir).apply { mkdirs() }
        return if (name.isBlank()) base else File(base, name).apply { mkdirs() }
    }

    /** 列出某类下已安装的模型目录 */
    fun installed(context: Context, kind: ModelKind): List<File> {
        val base = dir(context, kind)
        return base.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }

    /** 已安装模型的名字集合（目录名 == 模型 id） */
    fun installedNames(context: Context, kind: ModelKind): Set<String> =
        installed(context, kind).mapNotNull { it.name }.toSet()

    /** 界面上只显示模型名，不暴露长路径 */
    fun nameOf(path: String): String =
        if (path.isBlank()) "未选择" else File(path).name.ifBlank { path }

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
