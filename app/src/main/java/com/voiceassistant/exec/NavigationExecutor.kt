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
 * 两个坑记在这里：
 * 1. Key 的平台类型必须是「Web服务」，Android 平台的 Key 调 REST 会返回 10009。
 * 2. 2021-12-02 之后申请的 Key 必须同时带「安全密钥」(jscode)，否则返回 10008。
 *
 * 说明：文档明确排除自建后端，所以是客户端直连高德 Web API，
 * Key 与安全密钥都由用户自己申请并加密保存在本机。
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

    data class Geo(val lng: Double, val lat: Double)

    private sealed interface GeoOutcome {
        data class Ok(val geo: Geo, val formatted: String) : GeoOutcome
        data class Err(val message: String, val detail: String) : GeoOutcome
    }

    suspend fun execute(action: VaAction): ActionResult = withContext(Dispatchers.IO) {
        val dest = action.destination.trim()
        if (dest.isEmpty()) {
            return@withContext ActionResult(action, false, "没有听到目的地", "destination 为空")
        }
        if (!settings.hasAmapKey) {
            return@withContext ActionResult(action, false, "还没有配置高德地图的 Key", "amapKey 为空")
        }

        when (val outcome = geocode(dest)) {
            is GeoOutcome.Err -> ActionResult(action, false, "导航失败，${outcome.message}", outcome.detail)
            is GeoOutcome.Ok -> {
                val launched = launchAmap(dest, outcome.geo.lng, outcome.geo.lat)
                if (!launched) {
                    ActionResult(action, false, "没有安装高德地图，无法导航", "未找到 $AMAP_PKG")
                } else {
                    ActionResult(
                        action, true, "好的，为你导航到$dest",
                        "lat=${outcome.geo.lat} lon=${outcome.geo.lng} addr=${outcome.formatted}"
                    )
                }
            }
        }
    }

    /** 设置页「测试地点解析」用：只做地理编码，不唤起高德，失败时把原因说清楚 */
    suspend fun testGeocode(address: String): String = withContext(Dispatchers.IO) {
        if (!settings.hasAmapKey) return@withContext "还没有填高德 Key"
        when (val outcome = geocode(address)) {
            is GeoOutcome.Err -> "失败：${outcome.message}\n原始信息：${outcome.detail}"
            is GeoOutcome.Ok ->
                "成功：${outcome.formatted}\n经纬度：${outcome.geo.lng}, ${outcome.geo.lat}"
        }
    }

    private fun geocode(address: String): GeoOutcome {
        val url = buildString {
            append("https://restapi.amap.com/v3/geocode/geo")
            append("?key=").append(settings.amapKey.trim())
            append("&address=").append(URLEncoder.encode(address, "UTF-8"))
            append("&output=JSON")
            // 新申请的 Key 必须带安全密钥，否则 10008
            if (settings.amapSecurityKey.isNotBlank()) {
                append("&jscode=").append(settings.amapSecurityKey.trim())
            }
        }

        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    DebugLog.w(TAG, "地理编码 HTTP ${response.code}: $body")
                    return GeoOutcome.Err("地图服务返回 HTTP ${response.code}", body.take(200))
                }
                DebugLog.d(TAG, "geocode 返回: ${body.take(300)}")

                val root = JSONObject(body)
                if (root.optString("status") != "1") {
                    val info = root.optString("info", "未知错误")
                    val infocode = root.optString("infocode", "")
                    return GeoOutcome.Err(friendly(info, infocode), "$info/$infocode")
                }

                val geocodes = root.optJSONArray("geocodes") ?: return GeoOutcome.Err(
                    "地图服务没有返回坐标", "geocodes 缺失"
                )
                if (geocodes.length() == 0) {
                    return GeoOutcome.Err("没有解析到「$address」这个地点", "geocodes 为空")
                }
                val first = geocodes.optJSONObject(0) ?: return GeoOutcome.Err("返回数据异常", "geocodes[0] 为空")
                val location = first.optString("location").orEmpty()
                val parts = location.split(',')
                if (parts.size != 2) return GeoOutcome.Err("地图返回的坐标格式异常", location)

                val lng = parts[0].toDoubleOrNull() ?: return GeoOutcome.Err("经度解析失败", location)
                val lat = parts[1].toDoubleOrNull() ?: return GeoOutcome.Err("纬度解析失败", location)
                val formatted = first.optString("formatted_address", address)
                GeoOutcome.Ok(Geo(lng, lat), formatted)
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "地理编码异常", e)
            GeoOutcome.Err("网络请求失败（${e.javaClass.simpleName}）", e.message.orEmpty())
        }
    }

    /** 把高德错误码翻译成用户能看懂的话 */
    private fun friendly(info: String, infocode: String): String = when {
        infocode == "10001" || info == "INVALID_USER_KEY" -> "Key 无效，请检查是否复制完整"
        infocode == "10008" || info == "INVALID_USER_SCODE" ->
            "缺少或不匹配「安全密钥」：去高德控制台同一个 Key 下复制安全密钥填进设置"
        infocode == "10009" || info == "USERKEY_PLAT_NOMATCH" ->
            "Key 的平台类型不对：必须选「Web服务」，Android 平台的 Key 用不了"
        infocode == "10007" || info == "INVALID_USER_SIGNATURE" -> "数字签名不匹配"
        infocode == "10003" || info == "DAILY_QUERY_OVER_LIMIT" -> "超出今天的调用配额，明天再试或去控制台提高配额"
        infocode == "10044" || info == "USER_DAILY_QUERY_OVER_LIMIT" -> "超出今天的调用配额"
        infocode == "10004" || info == "ACCESS_TOO_FREQUENT" -> "请求太频繁，稍后再试"
        infocode == "10012" || info == "INSUFFICIENT_PRIVILEGES" -> "这个 Key 没有地理编码权限"
        infocode == "10002" || info == "SERVICE_NOT_AVAILABLE" -> "高德服务暂时不可用"
        info == "INVALID_PARAMS" -> "请求参数错误（地址可能为空）"
        else -> "高德返回错误：$info"
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
