package be.kenny.launcher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class CurrentWeather(
    val time: ZonedDateTime,
    val temperature: Double?,
    val feelsLike: Double?,
    val code: Int?,
    val isDay: Boolean?,
    val windSpeed: Double?,
    val windDirection: Double?,
)

data class ForecastDay(
    val date: LocalDate,
    val code: Int?,
    val high: Double?,
    val low: Double?,
    val feelsLikeHigh: Double?,
    val rainChance: Double?,
    val windSpeed: Double?,
    val windDirection: Double?,
    val sunset: LocalTime?,
)

data class WeatherForecast(
    val current: CurrentWeather,
    val days: List<ForecastDay>,
    val fetchedAt: Instant,
) {
    val hasMissingValues: Boolean
        get() = with(current) {
            temperature == null || feelsLike == null || code == null || isDay == null ||
                windSpeed == null || windDirection == null
        } || days.size < Weather.FORECAST_DAYS || days.any {
            it.code == null || it.high == null || it.low == null || it.feelsLikeHigh == null ||
                it.rainChance == null || it.windSpeed == null || it.windDirection == null || it.sunset == null
        }
}

object Weather {
    const val LOCATION_NAME = "Erembodegem"
    const val TIME_ZONE = "Europe/Brussels"
    const val FORECAST_DAYS = 10
    val zone: ZoneId = ZoneId.of(TIME_ZONE)

    val requestUrl = "https://api.open-meteo.com/v1/forecast?latitude=${Sun.LAT}&longitude=${Sun.LON}" +
        "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,wind_direction_10m" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max," +
        "precipitation_probability_max,wind_speed_10m_max,wind_direction_10m_dominant,sunset" +
        "&timezone=${java.net.URLEncoder.encode(TIME_ZONE, "UTF-8")}" +
        "&forecast_days=$FORECAST_DAYS&temperature_unit=celsius&wind_speed_unit=kmh"

    fun load(connection: HttpURLConnection = URL(requestUrl).openConnection() as HttpURLConnection): WeatherForecast {
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Weather service returned HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body)
        } catch (e: JSONException) {
            throw IOException("Invalid weather response", e)
        } catch (e: DateTimeException) {
            throw IOException("Invalid weather timestamp", e)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(body: String, fetchedAt: Instant = Instant.now()): WeatherForecast {
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        if (dates.length() !in 1..FORECAST_DAYS) throw JSONException("Invalid forecast length")

        fun series(name: String): JSONArray = daily.getJSONArray(name).also {
            if (it.length() != dates.length()) throw JSONException("Incomplete daily series: $name")
        }

        val codes = series("weather_code")
        val highs = series("temperature_2m_max")
        val lows = series("temperature_2m_min")
        val feelsLike = series("apparent_temperature_max")
        val rain = series("precipitation_probability_max")
        val wind = series("wind_speed_10m_max")
        val directions = series("wind_direction_10m_dominant")
        val sunsets = series("sunset")
        val days = (0 until dates.length()).map { index ->
            ForecastDay(
                date = LocalDate.parse(dates.getString(index)),
                code = integer(codes.get(index)),
                high = number(highs.get(index)),
                low = number(lows.get(index)),
                feelsLikeHigh = number(feelsLike.get(index)),
                rainChance = number(rain.get(index), 0.0..100.0),
                windSpeed = number(wind.get(index), 0.0..Double.MAX_VALUE),
                windDirection = number(directions.get(index), 0.0..360.0),
                sunset = if (sunsets.isNull(index)) null else LocalDateTime.parse(sunsets.getString(index)).toLocalTime(),
            )
        }
        if (days.zipWithNext().any { (a, b) -> b.date != a.date.plusDays(1) }) {
            throw JSONException("Forecast dates must be consecutive")
        }
        val isDay = integer(current.get("is_day"))
        if (isDay != null && isDay !in 0..1) throw JSONException("Invalid day/night value")
        return WeatherForecast(
            current = CurrentWeather(
                time = LocalDateTime.parse(current.getString("time")).atZone(zone),
                temperature = number(current.get("temperature_2m")),
                feelsLike = number(current.get("apparent_temperature")),
                code = integer(current.get("weather_code")),
                isDay = isDay?.let { it == 1 },
                windSpeed = number(current.get("wind_speed_10m"), 0.0..Double.MAX_VALUE),
                windDirection = number(current.get("wind_direction_10m"), 0.0..360.0),
            ),
            days = days,
            fetchedAt = fetchedAt,
        )
    }

    private fun number(value: Any, range: ClosedFloatingPointRange<Double>? = null): Double? {
        if (value == JSONObject.NULL) return null
        val number = (value as? Number)?.toDouble() ?: throw JSONException("Expected a weather number")
        if (!number.isFinite() || (range != null && number !in range)) {
            throw JSONException("Weather number out of range")
        }
        return number
    }

    private fun integer(value: Any): Int? = number(value, 0.0..Int.MAX_VALUE.toDouble())?.let {
        if (it % 1 != 0.0) throw JSONException("Expected an integer weather code")
        it.toInt()
    }
}
