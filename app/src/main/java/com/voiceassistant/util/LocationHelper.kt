package com.voiceassistant.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.voiceassistant.log.DebugLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 一次性定位工具：优先用上次已知位置（几乎不耗电），没有才真正请求一次。
 *
 * 天气要用它拿"当前城市"，导航要用它算候选地点的直线距离。
 */
object LocationHelper {

    private const val TAG = "Location"

    @SuppressLint("MissingPermission")
    suspend fun lastLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            try {
                lm.getLastKnownLocation(provider)?.let { return it }
            } catch (e: SecurityException) {
                DebugLog.w(TAG, "没有定位权限")
                return null
            } catch (e: Exception) {
                // 换下一个 provider
            }
        }

        return withTimeoutOrNull(8_000) {
            try {
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
                null
            } catch (e: Exception) {
                DebugLog.w(TAG, "定位失败: ${e.message}")
                null
            }
        }
    }

    /** 两点直线距离（米） */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    /** 「1.2 公里」/「350 米」 */
    fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1f 公里".format(meters / 1000) else "${meters.toInt()} 米"
}
