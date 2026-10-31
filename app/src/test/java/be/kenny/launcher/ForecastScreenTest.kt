package be.kenny.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForecastScreenTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var screen: ForecastScreen
    private val now = ZonedDateTime.parse("2026-10-28T01:05:00+01:00[Europe/Brussels]")
    private val data = Weather.parse(weatherJson(), now.toInstant())
    private var backCount = 0
    private var retryCount = 0

    @Before
    fun createScreen() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        controller = Robolectric.buildActivity(Activity::class.java)
        activity = controller.get()
        activity.setTheme(R.style.LauncherTheme)
        controller.create()
        activity.setContentView(R.layout.activity_main)
        activity.findViewById<View>(R.id.statusRow).visibility = View.GONE
        activity.findViewById<View>(R.id.row).visibility = View.GONE
        activity.findViewById<View>(R.id.label).visibility = View.GONE
        activity.findViewById<View>(R.id.dashboardBottomScrim).visibility = View.GONE
        activity.findViewById<View>(R.id.forecastScrim).visibility = View.VISIBLE
        screen = ForecastScreen(activity.findViewById(R.id.forecast), { backCount++ }, { retryCount++ })
        controller.start().resume().visible().windowFocusChanged(true)
    }

    @After
    fun destroyScreen() {
        controller.pause().stop().destroy()
    }

    @Test
    fun remoteMovesBetweenDaysUpdatesDetailsAndReachesBack() {
        screen.render(data, loading = false, error = false, offline = false, now = now)
        screen.show()
        layout()
        val row = activity.findViewById<LinearLayout>(R.id.forecastDays)
        assertEquals(5, row.childCount)
        assertTrue("Initial focus=${activity.currentFocus}; touch=${row.isInTouchMode}",
            row.getChildAt(0).hasFocus())
        assertEquals("9°", text(R.id.forecastTemperature))
        assertEquals("Helder", text(R.id.forecastCondition))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right should focus the second day; current=${activity.currentFocus}",
            row.getChildAt(1).hasFocus())
        assertEquals("15°", text(R.id.forecastTemperature))
        assertEquals("Gevoelstemperatuur (max.)", text(R.id.forecastFeelsLikeLabel))
        assertTrue(text(R.id.forecastLabel).contains("maximumtemperatuur"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(row.getChildAt(1).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down should focus Back; current=${activity.currentFocus}",
            activity.findViewById<View>(R.id.forecastBack).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(row.getChildAt(1).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, backCount)
    }

    @Test
    fun loadingFailureRetryAndCachedDataAreExplicitAndFocusable() {
        screen.render(null, loading = true, error = false, offline = false, now = now)
        screen.show()
        layout()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.forecastMessage).visibility)
        assertEquals("Weer laden…", text(R.id.forecastMessage))
        assertTrue("Loading focus=${activity.currentFocus}; touch=${activity.findViewById<View>(R.id.root).isInTouchMode}",
            activity.findViewById<View>(R.id.forecastBack).hasFocus())
        screen.render(null, loading = false, error = true, offline = true, now = now)
        layout()
        assertTrue(text(R.id.forecastMessage).contains("Geen internetverbinding"))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right should focus retry; current=${activity.currentFocus}",
            activity.findViewById<View>(R.id.forecastRetry).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, retryCount)
        screen.render(null, loading = true, error = true, offline = false, now = now)
        assertTrue(activity.findViewById<View>(R.id.forecastBack).hasFocus())
        screen.render(data, loading = false, error = false, offline = false, now = now)
        assertTrue(activity.findViewById<LinearLayout>(R.id.forecastDays).getChildAt(0).hasFocus())
        screen.render(data, loading = false, error = true, offline = true, now = now.plusHours(2))
        assertEquals("9°", text(R.id.forecastTemperature))
        assertTrue(text(R.id.forecastStatus).contains("Offline. Laatst opgehaalde gegevens."))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.forecastRetry).visibility)
    }

    @Test
    fun refreshPreservesSelectedDayAndMidnightRemovesPastDays() {
        screen.render(data, loading = false, error = false, offline = false, now = now)
        screen.show()
        layout()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        screen.render(data.copy(fetchedAt = now.plusMinutes(30).toInstant()),
            loading = false, error = false, offline = false, now = now.plusMinutes(30))
        assertTrue(activity.findViewById<LinearLayout>(R.id.forecastDays).getChildAt(2).hasFocus())
        assertEquals("13°", text(R.id.forecastTemperature))
        screen.render(data, loading = false, error = true, offline = false, now = now.plusDays(1))
        assertEquals(5, activity.findViewById<LinearLayout>(R.id.forecastDays).childCount)
        assertTrue(activity.findViewById<LinearLayout>(R.id.forecastDays).getChildAt(1).hasFocus())
        assertEquals("1–5 / 9", text(R.id.forecastPageRange))
        screen.render(data, loading = false, error = true, offline = true, now = now.plusDays(11))
        assertEquals(0, activity.findViewById<LinearLayout>(R.id.forecastDays).childCount)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.forecastDetails).visibility)
        assertTrue(activity.findViewById<View>(R.id.forecast).hasFocus())
    }

    @Test
    fun pagingRequiresActivationSelectsAdjacentDaysAndNeverWraps() {
        screen.render(data, loading = false, error = false, offline = false, now = now)
        screen.show()
        layout()
        val row = activity.findViewById<LinearLayout>(R.id.forecastDays)
        val previous = activity.findViewById<View>(R.id.forecastPrevious)
        val next = activity.findViewById<View>(R.id.forecastNext)
        assertEquals("1–5 / 10", text(R.id.forecastPageRange))
        assertFalse(previous.isEnabled)
        assertFalse(previous.isFocusable)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(row.getChildAt(0).hasFocus())
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        val fifthDayDetails = text(R.id.forecastLabel)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(next.hasFocus())
        assertEquals(fifthDayDetails, text(R.id.forecastLabel))
        assertEquals("1–5 / 10", text(R.id.forecastPageRange))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        layout()
        assertEquals(5, row.childCount)
        assertEquals("6–10 / 10", text(R.id.forecastPageRange))
        assertTrue(row.getChildAt(0).hasFocus())
        assertTrue(text(R.id.forecastLabel).contains("2 november"))
        assertEquals("13°", text(R.id.forecastTemperature))
        assertTrue(previous.isEnabled)
        assertFalse(next.isEnabled)
        assertFalse(next.isFocusable)
        repeat(5) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertTrue(row.getChildAt(4).hasFocus())
        assertTrue(text(R.id.forecastLabel).contains("6 november"))
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
        val sixthDayDetails = text(R.id.forecastLabel)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(previous.hasFocus())
        assertEquals(sixthDayDetails, text(R.id.forecastLabel))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        layout()
        assertEquals("1–5 / 10", text(R.id.forecastPageRange))
        assertTrue(row.getChildAt(4).hasFocus())
        assertTrue(text(R.id.forecastLabel).contains("1 november"))
        assertFalse(previous.isEnabled)
    }

    @Test
    fun refreshAndReopenPreserveSecondPageSelectionAndArrowFocus() {
        screen.render(data, loading = false, error = false, offline = false, now = now)
        screen.show()
        layout()
        activity.findViewById<View>(R.id.forecastNext).performClick()
        layout()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        screen.render(data.copy(fetchedAt = now.plusMinutes(30).toInstant()),
            loading = false, error = false, offline = false, now = now.plusMinutes(30))
        layout()
        val row = activity.findViewById<LinearLayout>(R.id.forecastDays)
        assertEquals("6–10 / 10", text(R.id.forecastPageRange))
        assertTrue(row.getChildAt(1).hasFocus())
        assertTrue(text(R.id.forecastLabel).contains("3 november"))
        screen.hide()
        screen.show()
        assertEquals("Reopening always returns to today", "1–5 / 10", text(R.id.forecastPageRange))
        assertTrue(row.getChildAt(0).hasFocus())
        assertTrue(text(R.id.forecastLabel).startsWith("Vandaag"))
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.forecastPreviousControl).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.forecastNextControl).visibility)
        activity.findViewById<View>(R.id.forecastNext).performClick()
        layout()
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.forecastNextControl).visibility)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        val selected = text(R.id.forecastLabel)
        val previous = activity.findViewById<View>(R.id.forecastPrevious)
        assertTrue(previous.hasFocus())
        screen.render(data, loading = false, error = false, offline = false, now = now.plusMinutes(31))
        assertTrue(previous.hasFocus())
        assertEquals(selected, text(R.id.forecastLabel))
        screen.render(data, loading = false, error = false, offline = false, now = now.plusDays(1))
        layout()
        assertEquals("1–5 / 9", text(R.id.forecastPageRange))
        assertTrue(row.getChildAt(4).hasFocus())
        assertTrue(text(R.id.forecastLabel).contains("2 november"))
        assertFalse(previous.isEnabled)
    }

    @Test
    fun shortForecastDoesNotPadDaysOrExposeEmptyPages() {
        screen.render(data.copy(days = data.days.take(7)),
            loading = false, error = false, offline = false, now = now)
        screen.show()
        layout()
        assertEquals("1–5 / 7", text(R.id.forecastPageRange))
        assertTrue(text(R.id.forecastStatus).contains("niet beschikbaar"))
        val next = activity.findViewById<View>(R.id.forecastNext)
        next.performClick()
        layout()
        val row = activity.findViewById<LinearLayout>(R.id.forecastDays)
        assertEquals(2, row.childCount)
        assertEquals("6–7 / 7", text(R.id.forecastPageRange))
        assertFalse(next.isEnabled)
        next.performClick()
        assertEquals("6–7 / 7", text(R.id.forecastPageRange))
        activity.findViewById<View>(R.id.forecastPrevious).requestFocus()
        screen.render(data.copy(days = data.days.take(5)),
            loading = false, error = false, offline = false, now = now)
        layout()
        assertEquals(5, row.childCount)
        assertEquals("1–5 / 5", text(R.id.forecastPageRange))
        assertFalse(next.isEnabled)
        assertFalse(activity.findViewById<View>(R.id.forecastPrevious).isEnabled)
        assertTrue(row.hasFocus())
    }

    @Test
    fun approvedColorsAreScopedToForecastAndTilesHaveOpaqueBackgrounds() {
        screen.render(data, loading = false, error = false, offline = false, now = now)
        screen.show()
        layout()
        val value = TypedValue()
        activity.theme.resolveAttribute(R.attr.weatherSunColor, value, true)
        assertEquals(Color.WHITE, value.data)
        activity.findViewById<View>(R.id.forecast).context.theme.resolveAttribute(R.attr.weatherSunColor, value, true)
        assertEquals(Color.rgb(255, 209, 102), value.data)
        activity.findViewById<View>(R.id.forecast).context.theme.resolveAttribute(R.attr.weatherRainColor, value, true)
        assertEquals(Color.rgb(104, 184, 255), value.data)
        val tile = activity.findViewById<LinearLayout>(R.id.forecastDays).getChildAt(1)
        val bitmap = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
        tile.background.setBounds(0, 0, tile.width, tile.height)
        tile.background.draw(Canvas(bitmap))
        assertEquals(117, Color.alpha(bitmap.getPixel(tile.width / 2, tile.height / 2)))
        bitmap.recycle()
    }

    @Test
    fun missingValuesRemainUnavailableAndNegativeTemperaturesFit() {
        val incomplete = data.copy(
            current = data.current.copy(temperature = null, feelsLike = null),
            days = data.days.map { it.copy(rainChance = null) },
        )
        screen.render(incomplete, loading = false, error = false, offline = false, now = now)
        screen.show()
        layout()
        assertEquals("--°", text(R.id.forecastTemperature))
        assertEquals("--°", text(R.id.forecastFeelsLike))
        assertEquals("--", text(R.id.forecastRain))
        assertTrue(text(R.id.forecastStatus).contains("niet beschikbaar"))
        screen.render(data.copy(current = data.current.copy(temperature = -12.0)),
            loading = false, error = false, offline = false, now = now)
        layout()
        val temperature = activity.findViewById<TextView>(R.id.forecastTemperature)
        assertEquals("-12°", temperature.text.toString())
        assertEquals(1, temperature.lineCount)
        assertTrue(temperature.paint.measureText(temperature.text.toString()) <= temperature.width)
        screen.render(data.copy(days = data.days.map { it.copy(high = -12.0, low = -20.0, rainChance = 100.0) }),
            loading = false, error = false, offline = false, now = now)
        layout()
        assertCardsFit()
    }

    @Test
    fun nativeLayoutFitsAtTvResolutionAndCanRenderForInspection() {
        screen.render(data, loading = false, error = false, offline = false, now = now)
        screen.show()
        activity.findViewById<ImageView>(R.id.bgBack).setImageResource(R.drawable.bg_day)
        activity.findViewById<TextView>(R.id.time).text = "01:05"
        activity.findViewById<TextView>(R.id.date).text = "woensdag 28 oktober"
        layout()
        val root = activity.findViewById<View>(R.id.root)
        val details = activity.findViewById<View>(R.id.forecastDetails)
        val heading = activity.findViewById<View>(R.id.forecastDaysHeading)
        assertEquals(1920, root.width)
        assertEquals(1080, root.height)
        assertTrue("Details must not overlap forecast heading", details.bottom < heading.top)
        assertTrue("Forecast heading must not overlap tiles",
            heading.bottom < activity.findViewById<View>(R.id.forecastPager).top)
        for (page in 1..2) {
            if (page == 2) {
                activity.findViewById<View>(R.id.forecastNext).performClick()
                layout()
            }
            assertCardsFit()
            val visibleArrow = if (page == 1) R.id.forecastNext else R.id.forecastPrevious
            val hiddenArrow = if (page == 1) R.id.forecastPrevious else R.id.forecastNext
            assertFalse(activity.findViewById<View>(hiddenArrow).isShown)
            for (id in listOf(visibleArrow)) {
                val bounds = Rect()
                assertTrue(activity.findViewById<View>(id).getGlobalVisibleRect(bounds))
                assertTrue("Arrows must remain inside TV-safe margins", bounds.left >= 96 && bounds.right <= 1824)
            }
            val name = if (page == 1) "forecast-ui-test.png" else "forecast-ui-page-2-test.png"
            val output = File("build/reports/$name")
            checkNotNull(output.parentFile).mkdirs()
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    private fun assertCardsFit() {
        val row = activity.findViewById<LinearLayout>(R.id.forecastDays)
        for (index in 0 until row.childCount) {
            val tile = row.getChildAt(index)
            assertTrue(tile.left >= 0 && tile.right <= row.width)
            val bounds = Rect()
            assertTrue(tile.getGlobalVisibleRect(bounds))
            assertTrue("Tiles must remain inside TV-safe margins", bounds.left >= 96 && bounds.right <= 1824)
            val content = tile.findViewById<View>(R.id.weatherDayContent)
            val chance = tile.findViewById<View>(R.id.weatherDayRain)
            assertTrue("Rain chance must fit inside the tile", chance.bottom <= content.height - content.paddingBottom)
            for (id in listOf(R.id.weatherDayName, R.id.weatherDayDate, R.id.weatherDayHigh,
                R.id.weatherDayLow, R.id.weatherDayRain)) {
                val label = tile.findViewById<TextView>(id)
                assertEquals("Card labels must not wrap", 1, label.lineCount)
                assertTrue("Card labels must not be clipped",
                    label.paint.measureText(label.text.toString()) <= label.width)
            }
        }
    }

    private fun layout() {
        val root = activity.findViewById<View>(R.id.root)
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        controller.windowFocusChanged(true)
    }

    private fun press(keyCode: Int) {
        pressRemote(activity, keyCode)
    }

    private fun text(id: Int) = activity.findViewById<TextView>(id).text.toString()
}
