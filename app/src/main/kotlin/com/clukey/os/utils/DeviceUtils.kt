package com.clukey.os.utils

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceUtils {

    fun getBatteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    fun isCharging(context: Context): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.isCharging ?: false
    }

    fun getRamInfo(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val usedMb = (info.totalMem - info.availMem) / (1024 * 1024)
        val totalMb = info.totalMem / (1024 * 1024)
        return Pair(usedMb, totalMb)
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("DeviceUtils", "getLocalIpAddress failed", e)
        }
        return ""
    }

    fun getDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun getAndroidVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun getSimSerialNumber(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION") tm.simSerialNumber ?: ""
        } catch (e: Exception) { "" }
    }

    // ── Foreground App ────────────────────────────────────────────────────────

    fun getForegroundApp(context: Context): String {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                now - 10_000L, now
            )
            stats?.filter { it.lastTimeUsed > 0 }
                 ?.maxByOrNull { it.lastTimeUsed }
                 ?.packageName ?: "unknown"
        } catch (e: Exception) { "unknown" }
    }

    // ── Network State ─────────────────────────────────────────────────────────

    fun getNetworkState(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return "offline"
                val caps = cm.getNetworkCapabilities(net) ?: return "offline"
                when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "online"
                }
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.typeName?.lowercase() ?: "offline"
            }
        } catch (e: Exception) { "unknown" }
    }
}
