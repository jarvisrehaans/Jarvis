package com.jarvis.assistant.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Provides system info (battery, date/time) and weather forecasts.
 */
object SystemInfoManager {

    fun getBatteryInfo(context: Context): String {
        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, iFilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val plugType = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "via USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "via AC charger"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "via wireless charger"
            else -> ""
        }

        return if (isCharging) {
            "Battery is at $batteryPct% (Charging $plugType)."
        } else {
            "Battery is at $batteryPct%."
        }
    }

    fun getDateTimeInfo(): String {
        val now = Date()
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        val tzFormat = SimpleDateFormat("z", Locale.getDefault())

        val timeStr = timeFormat.format(now)
        val dateStr = dateFormat.format(now)
        val tzStr = tzFormat.format(now)

        return "Current time: $timeStr ($tzStr)\nDate: $dateStr"
    }

    suspend fun getWeatherForecast(city: String = ""): String {
        val query = if (city.isNotBlank()) "weather forecast for $city" else "current local weather forecast"
        return BuiltInChromeEngine.searchAndExtract(query)
    }

    suspend fun getSystemSummary(context: Context, queryType: String, city: String = ""): String {
        val type = queryType.lowercase().trim()
        val sb = StringBuilder()

        if (type.contains("battery") || type == "all") {
            sb.append(getBatteryInfo(context)).append("\n")
        }
        if (type.contains("date") || type.contains("time") || type == "all") {
            sb.append(getDateTimeInfo()).append("\n")
        }
        if (type.contains("weather") || type == "all" || city.isNotBlank()) {
            val weatherInfo = getWeatherForecast(city)
            sb.append("Weather Info:\n").append(weatherInfo).append("\n")
        }

        return sb.toString().trim().ifBlank { getDateTimeInfo() }
    }
}
