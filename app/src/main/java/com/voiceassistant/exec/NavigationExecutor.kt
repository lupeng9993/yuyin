package com.voiceassistant.exec

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voiceassistant.data.AppSettings
import com.voiceassistant.log.DebugLog
import com.voiceassistant.nlu.VaAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 导航执行器：高德 Web API 地理编码 → 唤起高德 App 导航。
 *
 * 说明：文档明确排除自建后端，所以这里是客户端直连高德 Web API，
 * Key 由用户自己申请并保存在本机（Key 泄露与配额风险由用户自担）。
 */
class NavigationExecutor(
    private val context: Context,
    private val settings: AppSettings
) {

    private companion object {
        const val TAG = "Navigation"
        const val AMAP_PKG = "com.autonavi.minimap"
        const val AMAP_AUTO_PKG = "com.autonavi.amapauto"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun execute(action: VaAction): ActionResult = withContext(Dispatchers.IO) {
        val dest = action.destination.trim()
        if (dest.isEmpty()) {
            return@withContext ActionResult(action, false, "没有听到目的地", "destination 为空")
        }
        if (!settings.hasAmapKey) {
            return@withContext ActionResult(action, false, "还没有配置高德地图的 Key", "amapKey 为空")
        }

        val coordinate = geocode(dest)
        if (coordinate == null) {
            return@withContext ActionResult(
                action, false, "地图服务没有解析到「$dest」", "地理编码失败"
            )
        }

        val (lng, lat) = coordinate
        val launched = launchAmap(dest, lng, lat)
        if (!launched) {
            return@withContext ActionResult(
                action, false, "没有安装高德地图，无法导航", "未找到 $AMAP_PKG"
            )
        }
        ActionResult(action, true, "好的，为你导航到$dest", "lat=$lat lon=$lng")
    }

    /** 设置页「测试地点解析」用：只做地理编码，不唤起高德 */
    suspend fun testGeocode(address: String): String? = withContext(Dispatchers.IO) {
        val coordinate = geocode(address) ?: return@withContext null
        "经纬度：${coordinate.second}, ${coordinate.first}"
    }

    /** @return (经度, 纬度) */
    private fun geocode(address: String): Pair<Double, Double>? {
        val url = "https://restapi.amap.com/v3/geocode/geo" +
            "?key=${settings.amapKey}" +
            "&address=${URLEncoder.encode(address, "UTF-8")}" +
            "&output=JSON"

        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.w(TAG, "地理编码 HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                DebugLog.d(TAG, "geocode 返回: ${body.take(300)}")
                val root = JSONObject(body)
                if (root.optString("status") != "1") {
                    DebugLog.w(TAG, "高德返回错误: ${root.optString("info")}")
                    return null
                }
                val geocodes = root.optJSONArray("geocodes") ?: return null
                if (geocodes.length() == 0) return null
                val location = geocodes.optJSONObject(0)?.optString("location").orEmpty()
                val parts = location.split(',')
                if (parts.size != 2) return null
                val lng = parts[0].toDoubleOrNull() ?: return null
                val lat = parts[1].toDoubleOrNull() ?: return null
                lng to lat
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "地理编码异常", e)
            null
        }
    }

    private fun launchAmap(dest: String, lng: Double, lat: Double): Boolean {
        val encodedName = URLEncoder.encode(dest, "UTF-8")
        val naviUri = Uri.parse(
            "androidamap://navi?sourceApplication=voiceassistant&poiname=$encodedName" +
                "&lat=$lat&lon=$lng&dev=0&style=2"
        )
        val intent = Intent(Intent.ACTION_VIEW, naviUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val target = when {
            isInstalled(AMAP_PKG) -> AMAP_PKG
            isInstalled(AMAP_AUTO_PKG) -> AMAP_AUTO_PKG
            else -> null
        } ?: return false

        intent.setPackage(target)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "唤起高德失败", e)
            false
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }
}
