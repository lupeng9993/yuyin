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
 * 导航执行器：高德 Web API → 唤起高德 App。
 *
 * 关键点：
 * 1. **先 POI 搜索**（place/text），按名字找具体地点，比地理编码准得多；
 *    搜不到才退回地理编码（geo）。这就是"位置和想去的不是同一个地方"的修法。
 * 2. 多个候选交给上层挑一个，不瞎猜。
 * 3. Key 必须是「Web服务」类型；2021-12-02 之后的 Key 必须带安全密钥 jscode。
 */
class NavigationExecutor(
    private val context: Context,
    private val settings: AppSettings
) {

    private companion object {
        const val TAG = "Navigation"
        const val AMAP_PKG = "com.autonavi.minimap"
        const val AMAP_AUTO_PKG = "com.autonavi.amapauto"
        const val MAX_CANDIDATES = 4
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class Place(
        val name: String,
        val address: String,
        val lng: Double,
        val lat: Double,
        /** 与当前位置的直线距离（米）；拿不到定位时为 null */
        val distanceMeters: Double? = null
    ) {
        val distanceText: String
            get() = distanceMeters?.let { com.voiceassistant.util.LocationHelper.formatDistance(it) } ?: ""
    }

    /** 一次搜索的结果：要么有地点，要么有错误信息 */
    private data class SearchResult(
        val places: List<Place> = emptyList(),
        val error: String? = null,
        val detail: String = ""
    )

    private data class Fetched(val body: String?, val error: String?, val detail: String)

    /**
     * @param chooser 多个候选时由上层决定选哪个；返回 null 表示取消
     */
    suspend fun execute(
        action: VaAction,
        chooser: suspend (List<Place>) -> Place?
    ): ActionResult = withContext(Dispatchers.IO) {
        val dest = action.destination.trim()
        if (dest.isEmpty()) {
            return@withContext ActionResult(action, false, "没有听到目的地", "destination 为空")
        }
        if (!settings.hasAmapKey) {
            return@withContext ActionResult(action, false, "还没有配置高德地图的 Key", "amapKey 为空")
        }

        val result = search(dest)
        if (result.error != null) {
            return@withContext ActionResult(action, false, "导航失败，${result.error}", result.detail)
        }
        // 补上直线距离并就近排序，方便用户按"第几个"选
        val places = attachDistance(result.places)
        when {
            places.isEmpty() ->
                ActionResult(action, false, "没有找到「$dest」这个地方", "POI 与地理编码都没有结果")

            places.size == 1 -> launch(action, places.first())

            else -> {
                val picked = chooser(places.take(MAX_CANDIDATES))
                if (picked == null) ActionResult(action, false, "已取消导航", "用户没有选择候选地点")
                else launch(action, picked)
            }
        }
    }

    /** 用当前定位给每个候选算直线距离，并按由近到远排序；拿不到定位就原样返回 */
    private suspend fun attachDistance(places: List<Place>): List<Place> {
        if (places.size <= 1) return places
        val loc = com.voiceassistant.util.LocationHelper.lastLocation(context) ?: return places
        return places
            .map { p ->
                p.copy(
                    distanceMeters = com.voiceassistant.util.LocationHelper.distanceMeters(
                        loc.latitude, loc.longitude, p.lat, p.lng
                    )
                )
            }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
    }

    /** 设置页「测试地点解析」用 */
    suspend fun testGeocode(address: String): String = withContext(Dispatchers.IO) {
        if (!settings.hasAmapKey) return@withContext "还没有填高德 Key"
        val result = search(address)
        when {
            result.error != null -> "失败：${result.error}\n原始信息：${result.detail}"
            result.places.isEmpty() -> "连上了，但没有找到「$address」这个地点"
            else -> "找到 ${result.places.size} 个结果：\n" +
                result.places.joinToString("\n") { "• ${it.name} — ${it.address}（${it.lng},${it.lat}）" }
        }
    }

    // ---------------- 搜索 ----------------

    private fun search(dest: String): SearchResult {
        val poi = searchPoi(dest)
        if (poi.error == null && poi.places.isNotEmpty()) return poi

        val geo = geocode(dest)
        if (geo.error == null && geo.places.isNotEmpty()) return geo

        // 优先报 POI 的错误（更能说明 Key/配额问题），否则报地理编码的
        return if (poi.error != null) poi else geo
    }

    private fun searchPoi(dest: String): SearchResult {
        val url = buildString {
            append("https://restapi.amap.com/v3/place/text")
            append("?key=").append(settings.amapKey.trim())
            append("&keywords=").append(URLEncoder.encode(dest, "UTF-8"))
            append("&offset=").append(MAX_CANDIDATES + 2)
            append("&page=1&extensions=base")
            if (settings.amapSecurityKey.isNotBlank()) {
                append("&jscode=").append(settings.amapSecurityKey.trim())
            }
        }

        val fetched = fetch(url)
        if (fetched.body == null) {
            return SearchResult(error = fetched.error ?: "请求失败", detail = fetched.detail)
        }

        return try {
            val root = JSONObject(fetched.body)
            val pois = root.optJSONArray("pois") ?: return SearchResult(places = emptyList())
            val list = ArrayList<Place>(pois.length())
            for (i in 0 until pois.length()) {
                val obj = pois.optJSONObject(i) ?: continue
                val name = obj.optString("name").orEmpty().trim()
                var address = obj.optString("address").orEmpty().trim()
                if (address == "[]") address = ""
                val parts = obj.optString("location").orEmpty().split(',')
                if (parts.size != 2) continue
                val lng = parts[0].toDoubleOrNull() ?: continue
                val lat = parts[1].toDoubleOrNull() ?: continue
                list.add(Place(name.ifBlank { dest }, address, lng, lat))
            }
            DebugLog.d(TAG, "POI 搜索「$dest」得到 ${list.size} 个候选")
            SearchResult(places = list)
        } catch (e: Exception) {
            DebugLog.e(TAG, "POI 解析失败", e)
            SearchResult(error = "返回数据无法解析", detail = e.message.orEmpty())
        }
    }

    private fun geocode(dest: String): SearchResult {
        val url = buildString {
            append("https://restapi.amap.com/v3/geocode/geo")
            append("?key=").append(settings.amapKey.trim())
            append("&address=").append(URLEncoder.encode(dest, "UTF-8"))
            append("&output=JSON")
            if (settings.amapSecurityKey.isNotBlank()) {
                append("&jscode=").append(settings.amapSecurityKey.trim())
            }
        }

        val fetched = fetch(url)
        if (fetched.body == null) {
            return SearchResult(error = fetched.error ?: "请求失败", detail = fetched.detail)
        }

        return try {
            val root = JSONObject(fetched.body)
            val geocodes = root.optJSONArray("geocodes") ?: return SearchResult()
            if (geocodes.length() == 0) return SearchResult()
            val first = geocodes.optJSONObject(0) ?: return SearchResult()
            val parts = first.optString("location").orEmpty().split(',')
            if (parts.size != 2) return SearchResult()
            val lng = parts[0].toDoubleOrNull() ?: return SearchResult()
            val lat = parts[1].toDoubleOrNull() ?: return SearchResult()
            SearchResult(
                places = listOf(
                    Place(dest, first.optString("formatted_address").orEmpty(), lng, lat)
                )
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "地理编码解析失败", e)
            SearchResult(error = "返回数据无法解析", detail = e.message.orEmpty())
        }
    }

    /** 发请求 + 把高德的 status/info 翻译成人话 */
    private fun fetch(url: String): Fetched {
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    DebugLog.w(TAG, "HTTP ${response.code}: ${body.take(200)}")
                    return Fetched(null, "地图服务返回 HTTP ${response.code}", body.take(200))
                }
                val root = JSONObject(body)
                if (root.optString("status") != "1") {
                    val info = root.optString("info", "未知错误")
                    val infocode = root.optString("infocode", "")
                    return Fetched(null, friendly(info, infocode), "$info/$infocode")
                }
                Fetched(body, null, "")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "请求失败", e)
            Fetched(null, "网络请求失败（${e.javaClass.simpleName}）", e.message.orEmpty())
        }
    }

    private fun launch(action: VaAction, place: Place): ActionResult {
        return if (launchAmap(place)) {
            ActionResult(
                action, true, "好的，为你导航到${place.name}",
                "lat=${place.lat} lon=${place.lng} addr=${place.address}"
            )
        } else {
            ActionResult(action, false, "没有安装高德地图，无法导航", "未找到 $AMAP_PKG")
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
        infocode == "10003" || info == "DAILY_QUERY_OVER_LIMIT" -> "超出今天的调用配额，明天再试"
        infocode == "10044" || info == "USER_DAILY_QUERY_OVER_LIMIT" -> "超出今天的调用配额"
        infocode == "10004" || info == "ACCESS_TOO_FREQUENT" -> "请求太频繁，稍后再试"
        infocode == "10012" || info == "INSUFFICIENT_PRIVILEGES" -> "这个 Key 没有该服务权限"
        infocode == "10002" || info == "SERVICE_NOT_AVAILABLE" -> "高德服务暂时不可用"
        info == "INVALID_PARAMS" -> "请求参数错误（地址可能为空）"
        else -> "高德返回错误：$info"
    }

    private fun launchAmap(place: Place): Boolean {
        val encodedName = URLEncoder.encode(place.name, "UTF-8")
        val naviUri = Uri.parse(
            "androidamap://navi?sourceApplication=voiceassistant&poiname=$encodedName" +
                "&lat=${place.lat}&lon=${place.lng}&dev=0&style=2"
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
