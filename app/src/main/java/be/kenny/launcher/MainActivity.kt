package be.kenny.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private data class AppEntry(val label: String, val pkg: String, val component: ComponentName? = null)

    private val appsEntry by lazy { AppEntry(getString(R.string.apps_open), "") }
    private lateinit var settingsStore: SettingsStore
    private var settings = LauncherSettings()

    private lateinit var bgBack: ImageView
    private lateinit var bgFront: ImageView
    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherText: TextView
    private lateinit var weatherShortcut: View
    private lateinit var forecastScreen: ForecastScreen
    private lateinit var appsScreen: AppsScreen
    internal lateinit var settingsScreen: SettingsScreen
    private lateinit var netIcon: ImageView
    private lateinit var gear: ImageView
    private lateinit var rowScroll: HorizontalScrollView
    private lateinit var rowClip: View
    private lateinit var row: LinearLayout
    private lateinit var label: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val tiles = mutableListOf<Pair<ImageView, AppEntry>>()
    private var lastFocus = 0
    private var currentPhase: Sun.Phase? = null
    private var forcedPhase: Sun.Phase? = null
    private var weatherForecast: WeatherForecast? = null
    private var weatherLoading = false
    private var weatherError = false
    private var networkAvailable = false
    private var started = false
    private var initialFocusPlaced = false

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("nl", "BE"))

    private val dimFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(0.85f)
        postConcat(ColorMatrix().apply { setScale(0.72f, 0.72f, 0.72f, 1f) })
    })

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = tick()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            if (settings.slots.any { it.pkg == pkg }) buildRow()
            if (appsScreen.isOpen) appsScreen.refresh()
            settingsScreen.refreshApps()
        }
    }

    private val weatherRunnable = Runnable { fetchWeather() }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            handler.post {
                updateNet(caps)
                if ((weatherForecast == null || weatherError) && started) scheduleWeather(0)
            }
        }

        override fun onLost(n: Network) {
            handler.post { updateNet(null) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bgBack = findViewById(R.id.bgBack)
        bgFront = findViewById(R.id.bgFront)
        timeView = findViewById(R.id.time)
        dateView = findViewById(R.id.date)
        weatherIcon = findViewById(R.id.weatherIcon)
        weatherText = findViewById(R.id.weatherText)
        weatherShortcut = findViewById(R.id.weatherShortcut)
        netIcon = findViewById(R.id.netIcon)
        gear = findViewById(R.id.gear)
        rowScroll = findViewById(R.id.rowScroll)
        rowClip = findViewById(R.id.rowClip)
        row = findViewById(R.id.row)
        label = findViewById(R.id.label)
        settingsStore = SettingsStore(this)
        settings = settingsStore.load()
        forecastScreen = ForecastScreen(findViewById(R.id.forecast), ::closeForecast) {
            if (started) scheduleWeather(0)
        }
        forecastScreen.location = settings.location
        appsScreen = AppsScreen(findViewById(R.id.apps), ::closeApps)
        settingsScreen = SettingsScreen(findViewById(R.id.settings), ::onSettingsChanged, ::openSystemSettings, ::closeSettings)
        applyClockSize()
        weatherShortcut.setOnClickListener { openForecast() }
        weatherShortcut.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            view.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            if (hasFocus) label.setText(R.string.weather_open)
        }

        gear.setOnClickListener { openSettings() }
        gear.setOnFocusChangeListener { v, has ->
            v.background = if (has) getDrawable(R.drawable.gear_focus) else null
            v.animate().alpha(if (has) 1f else 0.55f).scaleX(if (has) 1.15f else 1f)
                .scaleY(if (has) 1.15f else 1f).setDuration(150).start()
            if (has) label.setText(R.string.settings)
        }

        applyIntent(intent)
        buildRow()

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(netCallback)
        updateNet(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (forecastScreen.isOpen) closeForecast()
            if (appsScreen.isOpen) closeApps()
            if (settingsScreen.isOpen) closeSettings()
        }
        tick()
    }

    /** Test hook: am start -n be.kenny.launcher/.MainActivity --es phase day|evening|night|auto */
    private fun applyIntent(i: Intent?) {
        when (i?.getStringExtra("phase")) {
            "day" -> forcedPhase = Sun.Phase.DAY
            "evening" -> forcedPhase = Sun.Phase.EVENING
            "night" -> forcedPhase = Sun.Phase.NIGHT
            "auto" -> forcedPhase = null
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        tick()
        scheduleWeather(0)
    }

    override fun onResume() {
        super.onResume()
        if (forecastScreen.isOpen) forecastScreen.restoreFocus()
        else if (appsScreen.isOpen) appsScreen.restoreFocus()
        else if (settingsScreen.isOpen) settingsScreen.restoreFocus()
        else if (!weatherShortcut.hasFocus() && !gear.hasFocus()) focusLastApp()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The framework may give default focus to the first status icon; HOME should start on the app row.
        if (hasFocus && !initialFocusPlaced) {
            initialFocusPlaced = true
            if (!overlayOpen) focusLastApp()
        }
    }

    override fun onStop() {
        super.onStop()
        started = false
        unregisterReceiver(timeReceiver)
        handler.removeCallbacks(weatherRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageReceiver)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
        handler.removeCallbacksAndMessages(null)
    }

    @Deprecated("Back closes an overlay; it never exits the home screen")
    override fun onBackPressed() {
        if (forecastScreen.isOpen) closeForecast()
        else if (appsScreen.isOpen) closeApps()
        else if (settingsScreen.isOpen) settingsScreen.back()
        else if (gear.hasFocus() || weatherShortcut.hasFocus()) focusLastApp()
    }

    private val overlayOpen: Boolean
        get() = forecastScreen.isOpen || appsScreen.isOpen || settingsScreen.isOpen

    private fun focusLastApp() {
        (tiles.getOrNull(lastFocus)?.first ?: weatherShortcut).requestFocus()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dp(v: Int) = dp(v.toFloat()).roundToInt()

    // ---------- clock & wallpaper ----------

    private fun tick() {
        val now = ZonedDateTime.now()
        timeView.text = now.format(timeFmt)
        dateView.text = now.format(dateFmt)
        setPhase(forcedPhase ?: settings.wallpaper.phase ?: Sun.phase(now, settings.location))
        if (forecastScreen.isOpen) renderWeather()
    }

    private fun setPhase(p: Sun.Phase) {
        if (p == currentPhase) return
        val res = when (p) {
            Sun.Phase.DAY -> R.drawable.bg_day
            Sun.Phase.EVENING -> R.drawable.bg_dusk
            Sun.Phase.NIGHT -> R.drawable.bg_night
        }
        if (currentPhase == null) {
            bgBack.setImageResource(res)
        } else {
            bgFront.setImageResource(res)
            bgFront.alpha = 0f
            bgFront.animate().alpha(1f).setDuration(2000).withEndAction {
                bgBack.setImageResource(res)
                bgFront.alpha = 0f
                bgFront.setImageDrawable(null)
            }.start()
        }
        currentPhase = p
    }

    // ---------- apps ----------

    private fun launchIntent(a: AppEntry): Intent? {
        val pm = packageManager
        a.component?.let { c ->
            return try {
                pm.getActivityInfo(c, 0)
                Intent(Intent.ACTION_MAIN).setComponent(c)
            } catch (e: Exception) {
                null
            }
        }
        return pm.getLeanbackLaunchIntentForPackage(a.pkg) ?: pm.getLaunchIntentForPackage(a.pkg)
    }

    private fun loadBanner(a: AppEntry, launch: Intent): Drawable? {
        val pm = packageManager
        val comp = a.component ?: launch.component
        return runCatching { comp?.let { pm.getActivityBanner(it) } }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(a.pkg) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(a.pkg) }.getOrNull()
    }

    private fun buildRow() {
        val gearHadFocus = gear.isFocused
        val weatherHadFocus = weatherShortcut.isFocused
        row.removeAllViews()
        tiles.clear()
        val spacing = dp(settings.tileSpacing)
        val count = settings.slots.size + 1
        val visible = minOf(settings.visibleTiles, count)
        // Keep the 84 dp banner size when it fits; shrink tiles when many are visible side by side.
        val tileWidthDp = minOf(84f, (MAX_ROW_WIDTH_DP - visible * settings.tileSpacing) / visible)
        val tileW = dp(tileWidthDp).roundToInt()
        val tileH = dp(tileWidthDp * 47f / 84f).roundToInt()
        val radius = dp(settings.cornerRadius.toFloat())
        val outline = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
        }
        fun params() = LinearLayout.LayoutParams(tileW, tileH).also {
            it.marginStart = spacing / 2; it.marginEnd = spacing - spacing / 2
        }
        for (slot in settings.slots) {
            val a = AppEntry(appLabel(slot.pkg), slot.pkg, slot.component)
            val launch = launchIntent(a) ?: continue
            val tile = ImageView(this).apply {
                id = View.generateViewId()
                setImageDrawable(loadBanner(a, launch))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = a.label
                layoutParams = params()
                isFocusable = true
                nextFocusUpId = weatherShortcut.id
                outlineProvider = outline
                clipToOutline = true
                colorFilter = dimFilter
                alpha = unselectedAlpha
                setOnClickListener { launch(a) }
                setOnFocusChangeListener { v, has -> onTileFocus(v as ImageView, a, has) }
            }
            tiles += tile to a
            row.addView(tile)
        }
        val appsTile = ImageView(this).apply {
            id = R.id.appsTile
            setImageResource(R.drawable.ic_apps)
            background = GradientDrawable().apply {
                cornerRadius = radius.toFloat()
                setColor(0x8A10181C.toInt())
                setStroke(dp(0.5f).roundToInt().coerceAtLeast(1), 0x40FFFFFF)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (tileH * 0.25f).roundToInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = appsEntry.label
            layoutParams = params()
            isFocusable = true
            nextFocusUpId = weatherShortcut.id
            outlineProvider = outline
            clipToOutline = true
            colorFilter = dimFilter
            alpha = unselectedAlpha
            setOnClickListener { openApps() }
            setOnFocusChangeListener { v, has -> onTileFocus(v as ImageView, appsEntry, has) }
        }
        tiles += appsTile to appsEntry
        row.addView(appsTile)
        // Carousel: the ends of the row wrap around, so the banner can be rotated in either direction.
        val first = tiles.first().first
        first.nextFocusLeftId = appsTile.id
        appsTile.nextFocusRightId = first.id
        // The focus zoom grows each side by at most the gap minus 2 dp, so a focused tile never touches its neighbors.
        focusScale = 1f + (2f * (spacing - dp(2f)) / tileW).coerceIn(0f, MAX_FOCUS_ZOOM)
        val growth = (tileW * (focusScale - 1f) / 2f).roundToInt()
        val pad = growth + dp(6)
        val vPad = (tileH * (focusScale - 1f) / 2f).roundToInt() + dp(12)
        // Padding lives on the row (not the scroll view): the fading-edge layer and focus scrolling use the
        // scroll view's padding box, which would clip the zoomed tile.
        row.setPadding(pad, vPad, pad, vPad)
        (rowClip.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(55) - vPad
        rowScroll.layoutParams = rowScroll.layoutParams.apply { width = visible * (tileW + spacing) + 2 * pad }
        if (lastFocus >= tiles.size) lastFocus = 0
        val lastTileId = tiles.getOrNull(lastFocus)?.first?.id ?: weatherShortcut.id
        gear.nextFocusDownId = lastTileId
        weatherShortcut.nextFocusDownId = lastTileId
        if (!gearHadFocus && !weatherHadFocus && !overlayOpen) focusLastApp()
    }

    private var focusScale = 1f + MAX_FOCUS_ZOOM

    private val unselectedAlpha get() = settings.unselectedOpacity / 100f

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun onTileFocus(v: ImageView, a: AppEntry, has: Boolean) {
        val s = if (has) focusScale else 1f
        v.animate().scaleX(s).scaleY(s)
            .alpha(if (has) 1f else unselectedAlpha).setDuration(200).start()
        v.colorFilter = if (has) null else dimFilter
        v.foreground = if (has) GradientDrawable().apply {
            cornerRadius = dp(settings.cornerRadius.toFloat())
            setStroke(dp(2), Color.WHITE)
        } else null
        if (has) {
            lastFocus = tiles.indexOfFirst { it.first === v }.coerceAtLeast(0)
            label.text = a.label
            gear.nextFocusDownId = v.id
            weatherShortcut.nextFocusDownId = v.id
        }
    }

    private fun launch(a: AppEntry) {
        val i = launchIntent(a) ?: return
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot launch ${a.pkg}", e)
        }
    }

    // ---------- status ----------

    private fun updateNet(caps: NetworkCapabilities?) {
        networkAvailable = caps != null
        val res = when {
            caps == null -> R.drawable.ic_wifi_off
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.drawable.ic_ethernet
            else -> R.drawable.ic_wifi
        }
        netIcon.setImageResource(res)
        netIcon.alpha = if (caps == null) 0.45f else 1f
        renderWeather()
    }

    private fun openApps() {
        setOverlayVisible(true)
        appsScreen.show()
    }

    // ---------- launcher settings ----------

    private fun openSettings() {
        // The clock and app row stay visible behind the panel as a live preview.
        findViewById<View>(R.id.statusRow).visibility = View.GONE
        label.visibility = View.GONE
        settingsScreen.show(settings)
    }

    private fun closeSettings() {
        settingsScreen.hide()
        findViewById<View>(R.id.statusRow).visibility = View.VISIBLE
        label.visibility = View.VISIBLE
        gear.requestFocus()
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Settings not available", e)
        }
    }

    private fun onSettingsChanged(next: LauncherSettings) {
        val old = settings
        settings = next
        settingsStore.save(next)
        if (next.clockSize != old.clockSize) applyClockSize()
        if (next.slots != old.slots || next.visibleTiles != old.visibleTiles || next.tileSpacing != old.tileSpacing ||
            next.cornerRadius != old.cornerRadius || next.unselectedOpacity != old.unselectedOpacity) {
            buildRow()
        }
        if (next.wallpaper != old.wallpaper || next.location != old.location) tick()
        if (next.location != old.location) {
            forecastScreen.location = next.location
            weatherForecast = null
            weatherError = false
            weatherIcon.setImageResource(R.drawable.ic_partly)
            weatherText.setText(R.string.weather_unavailable_temperature)
            weatherShortcut.contentDescription = getString(R.string.weather_open)
            renderWeather()
            if (started) scheduleWeather(0)
        } else if (next.forecastDays != old.forecastDays && started) {
            // Debounced so holding the slider key does not fire a request for every step.
            scheduleWeather(1000)
        }
    }

    private fun applyClockSize() {
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, settings.clockSize.toFloat())
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, (17f * settings.clockSize / 60f).coerceIn(14f, 28f))
    }

    private fun closeApps() {
        appsScreen.hide()
        setOverlayVisible(false)
        (tiles.lastOrNull()?.first ?: weatherShortcut).requestFocus()
    }

    private fun openForecast() {
        setOverlayVisible(true)
        renderWeather()
        forecastScreen.show()
        if (started && (weatherForecast == null || weatherError)) scheduleWeather(0)
    }

    private fun closeForecast() {
        forecastScreen.hide()
        setOverlayVisible(false)
        weatherShortcut.requestFocus()
    }

    private fun setOverlayVisible(visible: Boolean) {
        val dashboardVisibility = if (visible) View.GONE else View.VISIBLE
        findViewById<View>(R.id.statusRow).visibility = dashboardVisibility
        findViewById<View>(R.id.dashboardBottomScrim).visibility = dashboardVisibility
        rowScroll.visibility = dashboardVisibility
        label.visibility = dashboardVisibility
        findViewById<View>(R.id.forecastScrim).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun renderWeather() {
        if (isDestroyed) return
        weatherForecast?.current?.let { current ->
            weatherIcon.setImageResource(WeatherDisplay.icon(current.code, current.isDay))
            weatherText.text = WeatherDisplay.temperature(current.temperature)
            weatherShortcut.contentDescription = getString(R.string.weather_shortcut_description,
                getString(WeatherDisplay.description(current.code, current.isDay)), weatherText.text)
        }
        forecastScreen.render(weatherForecast, weatherLoading, weatherError, !networkAvailable)
    }

    private fun scheduleWeather(delayMs: Long) {
        handler.removeCallbacks(weatherRunnable)
        handler.postDelayed(weatherRunnable, delayMs)
    }

    private fun fetchWeather() {
        if (weatherLoading || !started) return
        weatherLoading = true
        renderWeather()
        Thread {
            val location = settings.location
            val days = settings.forecastDays
            try {
                val forecast = Weather.load(location, days)
                handler.post {
                    if (isDestroyed) return@post
                    weatherLoading = false
                    if (location != settings.location || days != settings.forecastDays) {
                        // Settings changed while loading; fetch again for the new city or length.
                        if (started) scheduleWeather(0)
                        return@post
                    }
                    weatherError = false
                    weatherForecast = forecast
                    renderWeather()
                    if (started) scheduleWeather(30 * 60_000L)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Weather fetch failed", e)
                handler.post {
                    if (isDestroyed) return@post
                    weatherLoading = false
                    if (location != settings.location || days != settings.forecastDays) {
                        if (started) scheduleWeather(0)
                        return@post
                    }
                    weatherError = true
                    renderWeather()
                    if (started) scheduleWeather(60_000L)
                }
            }
        }.apply { name = "Weather" }.start()
    }

    companion object {
        private const val TAG = "KennysLauncher"
        /** Width of nine 84 dp tiles with the default 12 dp spacing; wider rows scroll instead. */
        private const val MAX_ROW_WIDTH_DP = 864f
        private const val MAX_FOCUS_ZOOM = 0.18f
    }
}
