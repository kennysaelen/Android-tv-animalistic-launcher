package be.kenny.launcher

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DateTimeException
import java.time.ZoneId

/** City search through the free Open-Meteo geocoding API (no key needed). */
object Geocoding {
    fun requestUrl(query: String) = "https://geocoding-api.open-meteo.com/v1/search?name=" +
        URLEncoder.encode(query.trim(), "UTF-8") + "&count=10&language=nl&format=json"

    fun search(query: String, connection: HttpURLConnection = URL(requestUrl(query)).openConnection() as HttpURLConnection): List<WeatherLocation> {
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Geocoding service returned HTTP ${connection.responseCode}")
            }
            return parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (e: JSONException) {
            throw IOException("Invalid geocoding response", e)
        } finally {
            connection.disconnect()
        }
    }

    /** Returns valid results only; entries without coordinates or a known time zone are skipped. */
    fun parse(body: String): List<WeatherLocation> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val o = results.optJSONObject(index) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lat = o.optDouble("latitude")
            val lon = o.optDouble("longitude")
            val zone = o.optString("timezone")
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            try {
                ZoneId.of(zone)
            } catch (e: DateTimeException) {
                return@mapNotNull null
            }
            val detail = listOf(o.optString("admin1"), o.optString("country"))
                .filter { it.isNotBlank() && it != name }.distinct().joinToString(", ")
            WeatherLocation(name, lat, lon, zone, detail)
        }
    }
}
