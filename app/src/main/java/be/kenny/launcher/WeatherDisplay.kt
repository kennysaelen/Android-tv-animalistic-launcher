package be.kenny.launcher

import kotlin.math.roundToInt

object WeatherDisplay {
    fun temperature(value: Double?) = value?.let { "${it.roundToInt()}°" } ?: "--°"
    fun percent(value: Double?) = value?.let { "${it.roundToInt()}%" } ?: "--"

    fun direction(degrees: Double?): String = degrees?.let {
        val directions = listOf("N", "NNO", "NO", "ONO", "O", "OZO", "ZO", "ZZO",
            "Z", "ZZW", "ZW", "WZW", "W", "WNW", "NW", "NNW")
        directions[(it / 22.5).roundToInt() % directions.size]
    } ?: "--"

    fun icon(code: Int?, isDay: Boolean? = true) = when (code) {
        0 -> if (isDay == false) R.drawable.ic_moon else R.drawable.ic_sun
        1, 2 -> if (isDay == false) R.drawable.ic_moon else R.drawable.ic_partly
        3 -> R.drawable.ic_cloud
        45, 48 -> R.drawable.ic_fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_snow
        95, 96, 99 -> R.drawable.ic_thunder
        else -> R.drawable.ic_cloud
    }

    fun description(code: Int?, isDay: Boolean? = true) = when (code) {
        null -> R.string.weather_unknown
        0 -> if (isDay == false) R.string.weather_clear else R.string.weather_sunny
        1, 2 -> R.string.weather_partly_cloudy
        3 -> R.string.weather_cloudy
        45, 48 -> R.string.weather_fog
        51, 53, 55 -> R.string.weather_drizzle
        56, 57, 66, 67 -> R.string.weather_freezing_rain
        61, 63, 65 -> R.string.weather_rain
        71, 73, 75, 77 -> R.string.weather_snow
        80, 81, 82 -> R.string.weather_showers
        85, 86 -> R.string.weather_snow_showers
        95, 96, 99 -> R.string.weather_thunder
        else -> R.string.weather_unknown_code
    }
}
