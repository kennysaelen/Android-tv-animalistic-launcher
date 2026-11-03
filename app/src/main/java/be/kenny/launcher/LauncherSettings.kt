package be.kenny.launcher

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A place used for both the weather forecast and the sunrise/sunset wallpaper timing. */
data class WeatherLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timeZone: String,
    val detail: String = "",
) {
    val zone: java.time.ZoneId get() = java.time.ZoneId.of(timeZone)

    companion object {
        val DEFAULT = WeatherLocation("Erembodegem", 50.92, 4.05, "Europe/Brussels", "Oost-Vlaanderen, België")
    }
}

enum class WallpaperMode(val phase: Sun.Phase?) {
    AUTO(null), DAY(Sun.Phase.DAY), EVENING(Sun.Phase.EVENING), NIGHT(Sun.Phase.NIGHT)
}

/**
 * A slot in the app row: either a package (launched through its TV launch intent) or an
 * explicit `package/activity` component, as stored by [ComponentName.flattenToString].
 */
data class AppSlot(val pkg: String, val component: ComponentName? = null) {
    val key: String get() = component?.flattenToString() ?: pkg

    companion object {
        fun parse(key: String): AppSlot? {
            if (key.isBlank()) return null
            val component = if ('/' in key) ComponentName.unflattenFromString(key) else null
            return if (component != null) AppSlot(component.packageName, component) else AppSlot(key.trim())
        }
    }
}

data class LauncherSettings(
    val wallpaper: WallpaperMode = WallpaperMode.AUTO,
    val clockSize: Int = 60,
    val slots: List<AppSlot> = DEFAULT_SLOTS,
    val visibleTiles: Int = 9,
    val tileSpacing: Int = 12,
    val cornerRadius: Int = 7,
    val unselectedOpacity: Int = 100,
    val location: WeatherLocation = WeatherLocation.DEFAULT,
    val forecastDays: Int = 10,
) {
    /** Clamps every value into its supported range, so stored or edited values can never break the layout. */
    fun normalized() = copy(
        clockSize = clockSize.coerceIn(CLOCK_SIZE),
        slots = slots.distinctBy { it.key }.take(MAX_SLOTS),
        visibleTiles = visibleTiles.coerceIn(VISIBLE_TILES),
        tileSpacing = tileSpacing.coerceIn(TILE_SPACING),
        cornerRadius = cornerRadius.coerceIn(CORNER_RADIUS),
        unselectedOpacity = unselectedOpacity.coerceIn(UNSELECTED_OPACITY),
        forecastDays = forecastDays.coerceIn(FORECAST_DAYS),
    )

    companion object {
        val CLOCK_SIZE = 40..120
        val VISIBLE_TILES = 3..12
        val TILE_SPACING = 0..32
        val CORNER_RADIUS = 0..24
        val UNSELECTED_OPACITY = 20..100
        val FORECAST_DAYS = 5..16
        const val MAX_SLOTS = 20

        val DEFAULT_SLOTS = listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.spotify.tv.android",
            "be.vrt.vrtnu",
            "be.vmma.vtm.zenderapp",
            "be.dpgmedia.streamz",
        ).map { AppSlot(it) }
    }
}

/** Persists [LauncherSettings] in private SharedPreferences. Missing or invalid values fall back to defaults. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    fun load(): LauncherSettings {
        val d = LauncherSettings()
        return LauncherSettings(
            wallpaper = runCatching { WallpaperMode.valueOf(prefs.getString(WALLPAPER, null)!!) }.getOrDefault(d.wallpaper),
            clockSize = prefs.getInt(CLOCK_SIZE, d.clockSize),
            slots = prefs.getString(SLOTS, null)?.let { stored ->
                runCatching { JSONArray(stored).let { a -> (0 until a.length()).mapNotNull { AppSlot.parse(a.getString(it)) } } }
                    .getOrNull()
            } ?: d.slots,
            visibleTiles = prefs.getInt(VISIBLE_TILES, d.visibleTiles),
            tileSpacing = prefs.getInt(TILE_SPACING, d.tileSpacing),
            cornerRadius = prefs.getInt(CORNER_RADIUS, d.cornerRadius),
            unselectedOpacity = prefs.getInt(UNSELECTED_OPACITY, d.unselectedOpacity),
            location = prefs.getString(LOCATION, null)?.let { runCatching { locationFromJson(JSONObject(it)) }.getOrNull() }
                ?: d.location,
            forecastDays = prefs.getInt(FORECAST_DAYS, d.forecastDays),
        ).normalized()
    }

    fun save(settings: LauncherSettings) {
        val s = settings.normalized()
        prefs.edit()
            .putString(WALLPAPER, s.wallpaper.name)
            .putInt(CLOCK_SIZE, s.clockSize)
            .putString(SLOTS, JSONArray(s.slots.map { it.key }).toString())
            .putInt(VISIBLE_TILES, s.visibleTiles)
            .putInt(TILE_SPACING, s.tileSpacing)
            .putInt(CORNER_RADIUS, s.cornerRadius)
            .putInt(UNSELECTED_OPACITY, s.unselectedOpacity)
            .putString(LOCATION, locationToJson(s.location).toString())
            .putInt(FORECAST_DAYS, s.forecastDays)
            .apply()
    }

    private fun locationToJson(l: WeatherLocation) = JSONObject()
        .put("name", l.name).put("lat", l.latitude).put("lon", l.longitude).put("tz", l.timeZone).put("detail", l.detail)

    private fun locationFromJson(o: JSONObject): WeatherLocation {
        val zone = o.getString("tz")
        java.time.ZoneId.of(zone)
        val lat = o.getDouble("lat")
        val lon = o.getDouble("lon")
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        return WeatherLocation(o.getString("name"), lat, lon, zone, o.optString("detail"))
    }

    private companion object {
        const val WALLPAPER = "wallpaper"
        const val CLOCK_SIZE = "clock_size"
        const val SLOTS = "slots"
        const val VISIBLE_TILES = "visible_tiles"
        const val TILE_SPACING = "tile_spacing"
        const val CORNER_RADIUS = "corner_radius"
        const val UNSELECTED_OPACITY = "unselected_opacity"
        const val LOCATION = "location"
        const val FORECAST_DAYS = "forecast_days"
    }
}
