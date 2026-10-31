package com.voiceassistant.exec

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.voiceassistant.log.DebugLog

/**
 * 应用查找：先查内置常见应用表，再按应用名 / 包名做模糊匹配。
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
        "QQ音乐" to "com.tencent.qqmusic",
        "酷狗" to "com.kugou.android",
        "哔哩哔哩" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        "B站" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme",
        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "设置" to "com.android.settings",
        "相机" to "com.android.camera",
        "计算器" to "com.android.calculator2",
        "浏览器" to "com.android.browser"
    )

    fun resolve(context: Context, query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null

        builtin[q]?.let { pkg -> if (isInstalled(context, pkg)) return pkg }
        builtin.entries.firstOrNull { it.key.contains(q) || q.contains(it.key) }
            ?.value?.let { pkg -> if (isInstalled(context, pkg)) return pkg }

        val pm = context.packageManager
        val launchers = launcherPackages(pm)
        DebugLog.d(TAG, "已安装可启动应用 ${launchers.size} 个，查找「$q」")

        // 包名直接命中
        launchers.firstOrNull { it.packageName.equals(q, ignoreCase = true) }?.let { return it.packageName }
        // 包名包含
        launchers.firstOrNull { it.packageName.contains(q, ignoreCase = true) }?.let { return it.packageName }
        // 应用名精确 → 包含
        launchers.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let { return it.packageName }
        launchers.firstOrNull { it.label.contains(q, ignoreCase = true) }?.let { return it.packageName }
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
            }
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
}
