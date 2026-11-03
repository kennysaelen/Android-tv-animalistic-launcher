package be.kenny.launcher

import android.content.ComponentName
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LauncherSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun storeRoundTripsEverySetting() {
        val store = SettingsStore(context)
        assertEquals(LauncherSettings(), store.load())
        val custom = LauncherSettings(
            wallpaper = WallpaperMode.NIGHT,
            clockSize = 96,
            slots = listOf(AppSlot("com.spotify.tv.android"), AppSlot("a.b", ComponentName("a.b", "a.b.Tv"))),
            visibleTiles = 4,
            tileSpacing = 20,
            cornerRadius = 0,
            unselectedOpacity = 45,
            location = WeatherLocation("Gent", 51.05, 3.72, "Europe/Brussels", "Oost-Vlaanderen, België"),
            forecastDays = 16,
        )
        store.save(custom)
        assertEquals(custom, SettingsStore(context).load())
    }

    @Test
    fun invalidStoredValuesFallBackOrClamp() {
        context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE).edit()
            .putString("wallpaper", "SUNSET")
            .putInt("clock_size", 500)
            .putString("slots", "not json")
            .putInt("visible_tiles", 0)
            .putInt("unselected_opacity", 1)
            .putString("location", """{"name":"X","lat":95,"lon":4,"tz":"Europe/Brussels"}""")
            .putInt("forecast_days", 30)
            .commit()
        val loaded = SettingsStore(context).load()
        assertEquals(WallpaperMode.AUTO, loaded.wallpaper)
        assertEquals(120, loaded.clockSize)
        assertEquals(LauncherSettings.DEFAULT_SLOTS, loaded.slots)
        assertEquals(3, loaded.visibleTiles)
        assertEquals(20, loaded.unselectedOpacity)
        assertEquals(WeatherLocation.DEFAULT, loaded.location)
        assertEquals(16, loaded.forecastDays)
    }

    @Test
    fun slotsAreUniqueAndCapped() {
        val many = (1..30).map { AppSlot("app.$it") } + AppSlot("app.1")
        val normalized = LauncherSettings(slots = many).normalized()
        assertEquals(LauncherSettings.MAX_SLOTS, normalized.slots.size)
        assertEquals(normalized.slots.distinct(), normalized.slots)
    }
}
