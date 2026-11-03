package be.kenny.launcher

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class GeocodingTest {
    @Test
    fun parsesResultsAndSkipsInvalidEntries() {
        val body = """
            {"results":[
              {"name":"Gent","latitude":51.05,"longitude":3.71667,"country":"België","admin1":"Vlaanderen","timezone":"Europe/Brussels"},
              {"name":"Ghent","latitude":42.0,"longitude":-73.6,"country":"Verenigde Staten","admin1":"New York","timezone":"America/New_York"},
              {"name":"Nergens","latitude":51.0,"longitude":3.7,"timezone":"Not/AZone"},
              {"name":"","latitude":51.0,"longitude":3.7,"timezone":"Europe/Brussels"},
              {"name":"Zonder coördinaten","timezone":"Europe/Brussels"}
            ]}
        """.trimIndent()
        val results = Geocoding.parse(body)
        assertEquals(listOf("Gent", "Ghent"), results.map { it.name })
        assertEquals(WeatherLocation("Gent", 51.05, 3.71667, "Europe/Brussels", "Vlaanderen, België"), results[0])
        assertEquals("America/New_York", results[1].timeZone)
    }

    @Test
    fun noResultsIsAnEmptyList() {
        assertEquals(emptyList<WeatherLocation>(), Geocoding.parse("""{"generationtime_ms":0.2}"""))
    }

    @Test
    fun requestUrlEncodesTheQuery() {
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=Sint-Niklaas+Waas&count=10&language=nl&format=json",
            Geocoding.requestUrl(" Sint-Niklaas Waas "),
        )
    }
}
