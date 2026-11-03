package com.voiceassistant.calib

import android.content.Context
import com.voiceassistant.log.DebugLog
import org.json.JSONObject
import java.io.File

/**
 * 校准点位存储（通用）。
 *
 * 每个点按名字存（search / input / send / 其它软件的自定义点），
 * 存两处：
 *   1) /sdcard/VA-Models/calib.json —— 卸载重装不丢
 *   2) 应用私有 SharedPreferences —— 没有外部权限时兜底
 *
 * 坐标按「设计分辨率 1920x1200」存比例，换分辨率时自动换算。
 */
object CalibStore {

    private const val TAG = "Calib"
    private const val PREFS = "va_calib"
    private const val KEY_JSON = "points_json"

    const val DESIGN_W = 1920f
    const val DESIGN_H = 1200f

    const val SEARCH = "search"
    const val INPUT = "input"
    const val SEND = "send"

    /** 出厂默认：00:16 在 1920x1200 车机上校准得到的搜索图标位置 */
    private val defaults = mapOf(
        SEARCH to (849f to 151f),
        INPUT to (1440f to 1056f)
    )

    data class Point(val x: Int, val y: Int)

    private fun externalFile(): File = File("/sdcard/VA-Models/calib.json")

    private fun loadRaw(context: Context): MutableMap<String, Pair<Float, Float>> {
        val map = HashMap<String, Pair<Float, Float>>()
        // 外部文件优先
        try {
            val f = externalFile()
            if (f.isFile) {
                val o = JSONObject(f.readText())
                o.keys().forEach { k ->
                    val p = o.optJSONArray(k) ?: return@forEach
                    if (p.length() >= 2) map[k] = p.getDouble(0).toFloat() to p.getDouble(1).toFloat()
                }
                if (map.isNotEmpty()) return map
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "读外部校准失败: ${e.message}")
        }
        // 私有兜底
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JSON, "").orEmpty()
            if (raw.isNotBlank()) {
                val o = JSONObject(raw)
                o.keys().forEach { k ->
                    val p = o.optJSONArray(k) ?: return@forEach
                    if (p.length() >= 2) map[k] = p.getDouble(0).toFloat() to p.getDouble(1).toFloat()
                }
            }
            map
        } catch (e: Exception) {
            map
        }
    }

    private fun saveRaw(context: Context, map: Map<String, Pair<Float, Float>>) {
        val o = JSONObject()
        map.forEach { (k, v) ->
            o.put(k, org.json.JSONArray().put(v.first.toDouble()).put(v.second.toDouble()))
        }
        val text = o.toString()
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, text).apply()
        } catch (e: Exception) {
            DebugLog.w(TAG, "写私有校准失败: ${e.message}")
        }
        try {
            val f = externalFile()
            f.parentFile?.mkdirs()
            f.writeText(text)
            DebugLog.d(TAG, "校准已写入 ${f.absolutePath}")
        } catch (e: Exception) {
            DebugLog.w(TAG, "写外部校准失败: ${e.message}")
        }
    }

    /** 存一个点（按比例存） */
    fun save(context: Context, name: String, x: Int, y: Int) {
        val map = loadRaw(context)
        map[name] = (x / DESIGN_W) to (y / DESIGN_H)
        saveRaw(context, map)
        DebugLog.d(TAG, "保存点位 $name = ($x, $y)")
    }

    /** 取一个点，换算到当前分辨率；没有校准则用出厂默认；都没有返回 null */
    fun get(context: Context, name: String, screenW: Int = 1920, screenH: Int = 1200): Point? {
        val map = loadRaw(context)
        val ratio = map[name] ?: defaults[name]?.let { it.first / DESIGN_W to it.second / DESIGN_H }
            ?: return null
        return Point((ratio.first * screenW).toInt(), (ratio.second * screenH).toInt())
    }

    fun has(context: Context, name: String): Boolean = loadRaw(context).containsKey(name)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            externalFile().delete()
        } catch (e: Exception) {
            // 忽略
        }
    }
}
