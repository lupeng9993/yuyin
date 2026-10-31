package com.voiceassistant.exec

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.voiceassistant.log.DebugLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 应用查找：先查内置常见应用表，再按应用名 / 包名做模糊匹配。
 *
 * 识别结果经常和真实应用名差一个字（「抖音」被听成「都音」、「哔哩哔哩」被听成「b站」），
 * 所以这里除了包含匹配，还做一次编辑距离兜底，找不到时给出相近候选。
 *
 * 依赖 QUERY_ALL_PACKAGES 权限（本项目只做本地安装，不上架 Google Play）。
 */
object AppLauncher {

    private const val TAG = "AppLauncher"

    /** 常见应用固定映射，命中即用，避免歧义 */
    private val builtin: Map<String, String> = mapOf(
        "微信" to "com.tencent.mm",
        "高德" to "com.autonavi.minimap",
        "高德地图" to "com.autonavi.minimap",
        "网易云" to "com.netease.cloudmusic",
        "网易云音乐" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic",
        "酷狗" to "com.kugou.android",
        "哔哩哔哩" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme",
        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "设置" to "com.android.settings",
        "相机" to "com.android.camera",
        "计算器" to "com.android.calculator2",
        "浏览器" to "com.android.browser",
        "快手" to "com.smile.gifmaker",
        "小红书" to "com.xingin.xhs",
        "知乎" to "com.zhihu.android",
        "微博" to "com.sina.weibo",
        "钉钉" to "com.alibaba.android.rimet",
        "地图" to "com.autonavi.minimap"
    )

    /** 口语里多余的尾巴，匹配前剥掉 */
    private val noiseSuffix = listOf("app", "应用程序", "客户端", "软件", "一下", "吧", "啊", "呀")

    private fun normalize(raw: String): String {
        var q = raw.trim().replace(" ", "").lowercase()
        var changed = true
        while (changed) {
            changed = false
            for (s in noiseSuffix) {
                if (q.length > s.length && q.endsWith(s)) {
                    q = q.removeSuffix(s)
                    changed = true
                }
            }
        }
        return q
    }

    fun resolve(context: Context, rawQuery: String): String? {
        val q = normalize(rawQuery)
        if (q.isEmpty()) return null
        return match(context, q)?.first
    }

    /** 找不到时的相近候选，用于播报 / 结果卡提示 */
    fun suggestions(context: Context, rawQuery: String, limit: Int = 3): List<String> {
        val q = normalize(rawQuery)
        if (q.isEmpty()) return emptyList()
        val apps = launcherPackages(context.packageManager)
        return apps
            .map { it to distance(q, it.label.lowercase()) }
            .filter { (entry, d) -> d <= max(1, q.length / 3) || entry.label.contains(q) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first.label }
    }

    private fun match(context: Context, q: String): Pair<String, Int>? {
        // 1. 内置表：精确 → 互相包含
        builtin[q]?.let { pkg -> if (isInstalled(context, pkg)) return pkg to 0 }
        builtin.entries.firstOrNull { it.key.contains(q) || q.contains(it.key) }
            ?.value?.let { pkg -> if (isInstalled(context, pkg)) return pkg to 0 }

        val pm = context.packageManager
        val apps = launcherPackages(pm)
        DebugLog.d(TAG, "已安装可启动应用 ${apps.size} 个，查找「$q」")

        // 2. 包名 / 应用名精确与包含
        apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) }?.let { return it.packageName to 0 }
        apps.firstOrNull { it.packageName.contains(q, ignoreCase = true) }?.let { return it.packageName to 0 }
        apps.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let { return it.packageName to 0 }
        apps.firstOrNull { it.label.lowercase().contains(q) }?.let { return it.packageName to 0 }
        apps.firstOrNull { q.contains(it.label.lowercase()) && it.label.length >= 2 }?.let { return it.packageName to 0 }

        // 3. 编辑距离兜底：容忍听错一个字
        val tolerance = max(1, q.length / 3)
        val best = apps
            .map { it to distance(q, it.label.lowercase()) }
            .filter { (entry, d) ->
                d <= tolerance &&
                    // 长度差太大就别硬凑了
                    abs(entry.label.length - q.length) <= max(2, q.length / 2)
            }
            .minByOrNull { it.second }

        if (best != null) {
            DebugLog.d(TAG, "模糊匹配「$q」→ ${best.first.label} (距离 ${best.second})")
            return best.first.packageName to best.second
        }
        return null
    }

    fun labelOf(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        null
    }

    private data class AppEntry(val packageName: String, val label: String)

    private fun launcherPackages(pm: PackageManager): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(intent, 0).mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(pkg, info.loadLabel(pm).toString())
            }.distinctBy { it.packageName }
        } catch (e: Exception) {
            DebugLog.w(TAG, "枚举应用失败: ${e.message}")
            emptyList()
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** 编辑距离（只用于短字符串，规模可控） */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
