package be.kenny.launcher

import org.junit.Assert.*
import org.junit.Test

class WeatherDisplayTest {
    @Test
    fun preservesNightIconsAndDistinguishesWmoConditions() {
        assertEquals(R.drawable.ic_moon, WeatherDisplay.icon(0, false))
        assertEquals(R.drawable.ic_moon, WeatherDisplay.icon(2, false))
        assertEquals(R.drawable.ic_sun, WeatherDisplay.icon(0))
        assertEquals(R.drawable.ic_partly, WeatherDisplay.icon(2))
        assertEquals(R.drawable.ic_fog, WeatherDisplay.icon(48))
        assertEquals(R.drawable.ic_rain, WeatherDisplay.icon(67))
        assertEquals(R.drawable.ic_snow, WeatherDisplay.icon(86))
        assertEquals(R.drawable.ic_thunder, WeatherDisplay.icon(99))
        assertEquals(R.string.weather_clear, WeatherDisplay.description(0, false))
        assertEquals(R.string.weather_freezing_rain, WeatherDisplay.description(67))
    }

    @Test
    fun unknownCodesAreNotReportedAsThunderstorms() {
        assertEquals(R.string.weather_unknown, WeatherDisplay.description(null))
        assertEquals(R.string.weather_unknown_code, WeatherDisplay.description(123))
        assertNotEquals(R.drawable.ic_thunder, WeatherDisplay.icon(123))
    }

    @Test
    fun formatsNumbersAndUnknownValuesWithoutAssumingZero() {
        assertEquals("9°", WeatherDisplay.temperature(8.9))
        assertEquals("-12°", WeatherDisplay.temperature(-11.8))
        assertEquals("--°", WeatherDisplay.temperature(null))
        assertEquals("0%", WeatherDisplay.percent(0.0))
        assertEquals("--", WeatherDisplay.percent(null))
        assertEquals("N", WeatherDisplay.direction(0.0))
        assertEquals("N", WeatherDisplay.direction(360.0))
        assertEquals("ZW", WeatherDisplay.direction(223.0))
        assertEquals("--", WeatherDisplay.direction(null))
    }
}
