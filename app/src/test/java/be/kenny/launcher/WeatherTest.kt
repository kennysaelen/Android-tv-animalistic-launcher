package be.kenny.launcher

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WeatherTest {
    @Test
    fun parsesCurrentAndFiveDaysWithExplicitUnitsAndLocation() {
        val result = Weather.parse(weatherJson(), Instant.parse("2026-10-28T00:05:00Z"))
        assertEquals(8.9, result.current.temperature!!, 0.001)
        assertEquals(false, result.current.isDay)
        assertEquals(Weather.zone, result.current.time.zone)
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), result.current.time.toInstant())
        assertEquals(5, result.days.size)
        assertEquals(LocalDate.of(2026, 10, 28), result.days.first().date)
        assertEquals(70.0, result.days[3].rainChance!!, 0.001)
        assertEquals("17:25", result.days.first().sunset.toString())
        assertFalse(result.hasMissingValues)
        assertTrue(Weather.requestUrl.contains("latitude=${Sun.LAT}&longitude=${Sun.LON}"))
        assertTrue(Weather.requestUrl.contains("forecast_days=5"))
        assertTrue(Weather.requestUrl.contains("temperature_unit=celsius&wind_speed_unit=kmh"))
    }

    @Test
    fun nullValuesAreUnavailableRatherThanInventedZeroes() {
        val json = JSONObject(weatherJson())
        json.getJSONObject("current").put("temperature_2m", JSONObject.NULL)
        json.getJSONObject("daily").getJSONArray("precipitation_probability_max").put(0, JSONObject.NULL)
        val result = Weather.parse(json.toString())
        assertNull(result.current.temperature)
        assertNull(result.days.first().rainChance)
        assertTrue(result.hasMissingValues)
    }

    @Test
    fun rejectsMissingAndMisalignedSeries() {
        val missing = JSONObject(weatherJson())
        missing.getJSONObject("daily").remove("sunset")
        assertThrows(JSONException::class.java) { Weather.parse(missing.toString()) }
        val short = JSONObject(weatherJson())
        short.getJSONObject("daily").getJSONArray("temperature_2m_max").remove(4)
        assertThrows(JSONException::class.java) { Weather.parse(short.toString()) }
    }

    @Test
    fun rejectsInvalidProbabilityAndDuplicateDates() {
        val probability = JSONObject(weatherJson())
        probability.getJSONObject("daily").getJSONArray("precipitation_probability_max").put(0, 101)
        assertThrows(JSONException::class.java) { Weather.parse(probability.toString()) }
        val dates = JSONObject(weatherJson())
        dates.getJSONObject("daily").getJSONArray("time").put(1, "2026-10-28")
        assertThrows(JSONException::class.java) { Weather.parse(dates.toString()) }
    }

    @Test
    fun disconnectsAfterSuccessHttpFailureAndMalformedResponse() {
        val valid = FakeConnection(weatherJson())
        assertEquals(5, Weather.load(valid).days.size)
        assertTrue(valid.disconnected)
        assertEquals(8000, valid.connectTimeout)
        assertEquals(8000, valid.readTimeout)
        val error = FakeConnection("", 503)
        assertThrows(IOException::class.java) { Weather.load(error) }
        assertTrue(error.disconnected)
        val malformed = FakeConnection("""{"current":{}}""")
        assertThrows(IOException::class.java) { Weather.load(malformed) }
        assertTrue(malformed.disconnected)
    }

    private class FakeConnection(private val body: String, private val status: Int = 200) :
        HttpURLConnection(URL("https://example.invalid/weather")) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }
}

internal fun weatherJson() = """
    {
      "current": {
        "time": "2026-10-28T01:00", "temperature_2m": 8.9, "apparent_temperature": 6.7,
        "weather_code": 0, "is_day": 0, "wind_speed_10m": 11.5, "wind_direction_10m": 223
      },
      "daily": {
        "time": ["2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31", "2026-11-01"],
        "weather_code": [2, 0, 3, 61, 2],
        "temperature_2m_max": [14.4, 15.3, 13.2, 13.0, 11.2],
        "temperature_2m_min": [6.3, 8.8, 5.6, 5.5, 4.4],
        "apparent_temperature_max": [12.6, 15.0, 11.8, 11.2, 9.2],
        "precipitation_probability_max": [20, 10, 30, 70, 20],
        "wind_speed_10m_max": [14.0, 11.2, 11.7, 12.2, 9.4],
        "wind_direction_10m_dominant": [222, 148, 69, 66, 36],
        "sunset": ["2026-10-28T17:25", "2026-10-29T17:23", "2026-10-30T17:22", "2026-10-31T17:20", "2026-11-01T17:18"]
      }
    }
""".trimIndent()
