package com.voiceassistant.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.voiceassistant.data.AppSettings
import com.voiceassistant.log.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 天气查询（高德 Web 服务，复用导航那把 Key）。
 *
 * 城市确定顺序：GPS 定位 → IP 定位 → 设置里的默认城市。
 * 定位结果缓存 10 分钟，避免每问一次天气就唤醒 GPS。
 */
class WeatherService(
    private val context: Context,
    private val settings: AppSettings
) {

    companion object {
        private const val TAG = "Weather"
        private const val CACHE_MS = 10 * 60 * 1000L
    }

    data class City(val name: String, val adcode: String)

    data class Day(
        val dayWeather: String,
        val nightWeather: String,
        val low: Int,
        val high: Int
    ) {
        /** 白天和夜间一致就只说一个词，否则「晴转多云」 */
        fun weatherText(): String =
            if (dayWeather == nightWeather || nightWeather.isBlank()) dayWeather
            else "${dayWeather}转$nightWeather"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedCity: City? = null

    @Volatile
    private var cachedAt = 0L

    private val adcodeByName = HashMap<String, City>()

    /** GPS → IP → 设置城市 */
    suspend fun currentCity(): City? {
        val cached = cachedCity
        if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) return cached

        val gps = withTimeoutOrNull(8_000) { locate() }
        val fromGps = gps?.let { regeo(it) }
        val city = fromGps ?: ipCity() ?: settings.homeCity.trim().takeIf { it.isNotEmpty() }?.let { c ->
            cityByKeyword(c)
        }

        if (city != null) {
            cachedCity = city
            cachedAt = System.currentTimeMillis()
            DebugLog.d(TAG, "当前城市: ${city.name} (${city.adcode})，来源=${if (fromGps != null) "GPS" else "IP/设置"}")
        }
        return city
    }

    /** 按名字查城市（设置里的默认城市） */
    suspend fun cityByKeyword(name: String): City? {
        adcodeByName[name]?.let { return it }
        val url = amap(
            "https://restapi.amap.com/v3/config/district",
            mapOf("keywords" to name, "subdistrict" to "0")
        )
        val body = get(url) ?: return null
        return try {
            val root = JSONObject(body)
            val districts = root.optJSONArray("districts") ?: return null
            val first = districts.optJSONObject(0) ?: return null
            val code = first.optString("adcode").orEmpty()
            val cityName = first.optString("name").orEmpty().ifBlank { name }
            if (code.length >= 6) City(cityName, code).also { adcodeByName[name] = it } else null
        } catch (e: Exception) {
            DebugLog.e(TAG, "城市查询失败", e)
            null
        }
    }

    /** 取未来 3 天（0=今天 1=明天 2=后天） */
    suspend fun forecast(city: City): List<Day>? {
        val url = amap(
            "https://restapi.amap.com/v3/weather/weatherInfo",
            mapOf("city" to city.adcode, "extensions" to "all")
        )
        val body = get(url) ?: return null
        return try {
            val root = JSONObject(body)
            val forecasts = root.optJSONArray("forecasts") ?: return null
            val first = forecasts.optJSONObject(0) ?: return null
            val casts = first.optJSONArray("casts") ?: return null
            val days = ArrayList<Day>()
            for (i in 0 until minOf(casts.length(), 3)) {
                val c = casts.optJSONObject(i) ?: continue
                days.add(
                    Day(
                        dayWeather = c.optString("dayweather").orEmpty(),
                        nightWeather = c.optString("nightweather").orEmpty(),
                        low = c.optString("nighttemp").toIntOrNull() ?: 0,
                        high = c.optString("daytemp").toIntOrNull() ?: 0
                    )
                )
            }
            if (days.isEmpty()) null else days
        } catch (e: Exception) {
            DebugLog.e(TAG, "天气解析失败", e)
            null
        }
    }

    // ---------------- 定位与逆地理 ----------------

    @SuppressLint("MissingPermission")
    private suspend fun locate(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // 先用上次已知位置（省电、几乎瞬时）
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            try {
                lm.getLastKnownLocation(provider)?.let { return it }
            } catch (e: SecurityException) {
                DebugLog.w(TAG, "没有定位权限")
                return null
            } catch (e: Exception) {
                // 继续试下一个
            }
        }
        // 没有历史位置就请求一次
        return try {
            suspendCancellableCoroutine { cont ->
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (provider == null) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        } catch (e: SecurityException) {
            DebugLog.w(TAG, "定位被拒绝: ${e.message}")
            null
        } catch (e: Exception) {
            DebugLog.w(TAG, "定位失败: ${e.message}")
            null
        }
    }

    private suspend fun regeo(location: Location): City? {
        val url = amap(
            "https://restapi.amap.com/v3/geocode/regeo",
            mapOf("location" to "${location.longitude},${location.latitude}", "extensions" to "base")
        )
        val body = get(url) ?: return null
        return try {
            val root = JSONObject(body)
            val comp = root.optJSONObject("regeocode")?.optJSONObject("addressComponent") ?: return null
            var name = comp.optString("city").orEmpty()
            if (name.isBlank() || name == "[]") name = comp.optString("province").orEmpty()
            val districtCode = comp.optString("adcode").orEmpty()
            if (name.isBlank() || districtCode.length < 6) return null
            // 区级 adcode 归一到市级：370102 → 370100
            val cityCode = districtCode.substring(0, 4) + "00"
            City(name.removeSuffix("市"), cityCode)
        } catch (e: Exception) {
            DebugLog.e(TAG, "逆地理失败", e)
            null
        }
    }

    private suspend fun ipCity(): City? {
        val body = get(amap("https://restapi.amap.com/v3/ip", emptyMap())) ?: return null
        return try {
            val root = JSONObject(body)
            var name = root.optString("city").orEmpty()
            if (name.isBlank() || name == "[]") name = root.optString("province").orEmpty()
            val code = root.optString("adcode").orEmpty()
            if (name.isBlank() || code.length < 6) null else City(name.removeSuffix("市"), code.substring(0, 4) + "00")
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- HTTP ----------------

    private fun amap(base: String, params: Map<String, String>): String {
        val sb = StringBuilder(base)
        sb.append('?')
        for ((k, v) in params) {
            sb.append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8")).append('&')
        }
        sb.append("key=").append(settings.amapKey.trim())
        if (settings.amapSecurityKey.isNotBlank()) {
            sb.append("&jscode=").append(settings.amapSecurityKey.trim())
        }
        return sb.toString()
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    DebugLog.w(TAG, "HTTP ${response.code}: ${body.take(160)}")
                    return@withContext null
                }
                val root = JSONObject(body)
                if (root.optString("status") != "1") {
                    DebugLog.w(TAG, "高德返回 ${root.optString("info")}/${root.optString("infocode")}")
                    return@withContext null
                }
                body
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "请求失败", e)
            null
        }
    }
}
