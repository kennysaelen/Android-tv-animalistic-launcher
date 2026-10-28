package be.kenny.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
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

    private val wanted = listOf(
        AppEntry("Netflix", "com.netflix.ninja"),
        AppEntry("YouTube", "com.google.android.youtube.tv"),
        AppEntry("Prime Video", "com.amazon.amazonvideo.livingroom"),
        AppEntry("Disney+", "com.disney.disneyplus"),
        AppEntry("Spotify", "com.spotify.tv.android"),
        AppEntry("VRT MAX", "be.vrt.vrtnu"),
        AppEntry("VTM GO", "be.vmma.vtm.zenderapp"),
        AppEntry("Streamz", "be.dpgmedia.streamz"),
        AppEntry("TV kijken", "org.droidtv.playtv",
            ComponentName("org.droidtv.playtv", "org.droidtv.playtv.PlayTvActivity")),
    )

    private lateinit var bgBack: ImageView
    private lateinit var bgFront: ImageView
    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherText: TextView
    private lateinit var weatherShortcut: View
    private lateinit var forecastScreen: ForecastScreen
    private lateinit var netIcon: ImageView
    private lateinit var gear: ImageView
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
            if (wanted.any { it.pkg == pkg }) buildRow()
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
        row = findViewById(R.id.row)
        label = findViewById(R.id.label)
        forecastScreen = ForecastScreen(findViewById(R.id.forecast), ::closeForecast) {
            if (started) scheduleWeather(0)
        }
        weatherShortcut.setOnClickListener { openForecast() }
        weatherShortcut.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            view.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            if (hasFocus) label.setText(R.string.weather_open)
        }

        gear.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "Settings not available", e)
            }
        }
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
        if (intent.hasCategory(Intent.CATEGORY_HOME) && forecastScreen.isOpen) closeForecast()
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
        else if (!weatherShortcut.hasFocus() && !gear.hasFocus()) focusLastApp()
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

    @Deprecated("Back closes the forecast; it never exits the home screen")
    override fun onBackPressed() {
        if (forecastScreen.isOpen) closeForecast()
        else if (gear.hasFocus() || weatherShortcut.hasFocus()) focusLastApp()
    }

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
        setPhase(forcedPhase ?: Sun.phase(now))
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
        val radius = dp(7f)
        for (a in wanted) {
            val launch = launchIntent(a) ?: continue
            val tile = ImageView(this).apply {
                id = View.generateViewId()
                setImageDrawable(loadBanner(a, launch))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = a.label
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(47)).also {
                    it.marginStart = dp(6); it.marginEnd = dp(6)
                }
                isFocusable = true
                nextFocusUpId = weatherShortcut.id
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
                }
                clipToOutline = true
                elevation = dp(4f)
                colorFilter = dimFilter
                setOnClickListener { launch(a) }
                setOnFocusChangeListener { v, has -> onTileFocus(v as ImageView, a, has) }
            }
            tiles += tile to a
            row.addView(tile)
        }
        if (lastFocus >= tiles.size) lastFocus = 0
        val lastTileId = tiles.getOrNull(lastFocus)?.first?.id ?: weatherShortcut.id
        gear.nextFocusDownId = lastTileId
        weatherShortcut.nextFocusDownId = lastTileId
        if (!gearHadFocus && !weatherHadFocus && !forecastScreen.isOpen) focusLastApp()
    }

    private fun onTileFocus(v: ImageView, a: AppEntry, has: Boolean) {
        val s = if (has) 1.18f else 1f
        v.animate().scaleX(s).scaleY(s).translationZ(if (has) dp(8f) else 0f).setDuration(200).start()
        v.colorFilter = if (has) null else dimFilter
        v.foreground = if (has) getDrawable(R.drawable.tile_ring) else null
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

    private fun openForecast() {
        setForecastVisible(true)
        renderWeather()
        forecastScreen.show()
        if (started && (weatherForecast == null || weatherError)) scheduleWeather(0)
    }

    private fun closeForecast() {
        forecastScreen.hide()
        setForecastVisible(false)
        weatherShortcut.requestFocus()
    }

    private fun setForecastVisible(visible: Boolean) {
        val dashboardVisibility = if (visible) View.GONE else View.VISIBLE
        findViewById<View>(R.id.statusRow).visibility = dashboardVisibility
        findViewById<View>(R.id.dashboardBottomScrim).visibility = dashboardVisibility
        row.visibility = dashboardVisibility
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
            try {
                val forecast = Weather.load()
                handler.post {
                    if (isDestroyed) return@post
                    weatherLoading = false
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
                    weatherError = true
                    renderWeather()
                    if (started) scheduleWeather(60_000L)
                }
            }
        }.apply { name = "Weather" }.start()
    }

    companion object {
        private const val TAG = "KennysLauncher"
    }
}
