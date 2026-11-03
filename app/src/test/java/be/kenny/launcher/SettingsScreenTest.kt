package be.kenny.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], qualifiers = "w960dp-h540dp-land-xhdpi")
class SettingsScreenTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val pm get() = shadowOf(context.packageManager)

    @Before
    fun installApps() {
        install("com.netflix.ninja", "Netflix")
        install("com.spotify.tv.android", "Spotify")
        install("org.ted.android", "TED")
        install("com.android.vending", "Google Play Store")
    }

    @Test
    fun gearOpensPanelKeepsClockAndRowAsPreviewAndBackCloses() = withActivity { activity, _ ->
        open(activity)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settings).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusRow).visibility)
        assertTrue(activity.findViewById<View>(R.id.rowScroll).isShown)
        assertTrue(activity.findViewById<View>(R.id.time).isShown)
        assertTrue(row(activity, "wallpaper").hasFocus())

        row(activity, "slots").performClick()
        assertEquals(activity.getString(R.string.settings_slots), activity.findViewById<TextView>(R.id.settingsTitle).text)
        pressRemote(activity, KeyEvent.KEYCODE_BACK)
        assertTrue("Back returns to the main page", row(activity, "slots").hasFocus())
        pressRemote(activity, KeyEvent.KEYCODE_BACK)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settings).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusRow).visibility)
        assertTrue(activity.findViewById<View>(R.id.gear).hasFocus())
    }

    @Test
    fun homeClosesPanel() = withActivity { activity, controller ->
        open(activity)
        controller.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settings).visibility)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun focusCannotLeaveThePanel() = withActivity { activity, _ ->
        open(activity)
        val reset = row(activity, "reset")
        reset.requestFocus()
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(reset.hasFocus())
        row(activity, "wallpaper").requestFocus()
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(row(activity, "wallpaper").hasFocus())
    }

    @Test
    fun sliderAndChoiceKeysApplyLiveAndPersist() {
        withActivity { activity, _ ->
            open(activity)
            row(activity, "clock").requestFocus()
            pressRemote(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
            pressRemote(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("Left/right adjusts instead of moving focus", row(activity, "clock").hasFocus())
            assertEquals(dp(activity, 68f), activity.findViewById<TextView>(R.id.time).textSize, 0.01f)
            assertTrue(rowText(activity, "clock").contains("68"))

            row(activity, "wallpaper").requestFocus()
            pressRemote(activity, KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue(rowText(activity, "wallpaper").contains("Nacht"))

            row(activity, "opacity").requestFocus()
            repeat(2) { pressRemote(activity, KeyEvent.KEYCODE_DPAD_LEFT) }
            val tile = activity.findViewById<LinearLayout>(R.id.row).getChildAt(0)
            tile.animate().cancel()
            assertEquals(0.9f, tile.alpha, 0.001f)
        }
        val saved = SettingsStore(context).load()
        assertEquals(68, saved.clockSize)
        assertEquals(WallpaperMode.NIGHT, saved.wallpaper)
        assertEquals(90, saved.unselectedOpacity)
        withActivity { activity, _ ->
            assertEquals("Restored after restart", dp(activity, 68f), activity.findViewById<TextView>(R.id.time).textSize, 0.01f)
        }
    }

    @Test
    fun slotEditorMovesRemovesAndAddsApps() {
        SettingsStore(context).save(LauncherSettings(slots = listOf(AppSlot("com.netflix.ninja"), AppSlot("com.spotify.tv.android"))))
        withActivity { activity, _ ->
            assertEquals(listOf("Netflix", "Spotify", "Alle apps"), rowLabels(activity))
            open(activity)
            row(activity, "slots").performClick()
            row(activity, "slot:0").requestFocus()
            row(activity, "slot:0").performClick()
            assertTrue("OK starts move mode", row(activity, "slot:0").isActivated)
            pressRemote(activity, KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals(listOf("Spotify", "Netflix", "Alle apps"), rowLabels(activity))
            assertTrue("The moved app keeps focus", row(activity, "slot:1").hasFocus())
            row(activity, "slot:1").performClick()
            assertFalse(row(activity, "slot:1").isActivated)

            row(activity, "remove:0").performClick()
            assertEquals(listOf("Netflix", "Alle apps"), rowLabels(activity))

            row(activity, "add").performClick()
            val choices = activity.findViewById<LinearLayout>(R.id.settingsList)
            val names = (0 until choices.childCount).map { choices.getChildAt(it).tag }
            assertEquals(listOf("app:com.android.vending", "app:com.spotify.tv.android", "app:org.ted.android"), names)
            row(activity, "app:org.ted.android").performClick()
            assertEquals(listOf("Netflix", "TED", "Alle apps"), rowLabels(activity))
            assertTrue(row(activity, "slot:1").hasFocus())
        }
        assertEquals(listOf("com.netflix.ninja", "org.ted.android"), SettingsStore(context).load().slots.map { it.pkg })
    }

    @Test
    fun resetNeedsASecondPress() = withActivity { activity, _ ->
        open(activity)
        row(activity, "clock").requestFocus()
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        val reset = row(activity, "reset")
        reset.requestFocus()
        reset.performClick()
        assertEquals(64, SettingsStore(context).load().clockSize)
        reset.performClick()
        assertEquals(LauncherSettings(), SettingsStore(context).load())
        assertTrue(rowText(activity, "clock").contains("60"))
    }

    @Test
    fun citySearchSelectsResultForWeatherAndSun() = withActivity { activity, _ ->
        val gent = WeatherLocation("Gent", 51.05, 3.72, "Europe/Brussels", "Vlaanderen, België")
        var query = ""
        activity.settingsScreen.geocoder = { query = it; listOf(gent) }
        activity.settingsScreen.background = { it.run() }
        open(activity)
        row(activity, "city").performClick()
        val input = row(activity, "cityInput") as EditText
        assertTrue(input.hasFocus())
        input.setText("  Gent ")
        row(activity, "citySearch").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Gent", query)
        assertTrue(row(activity, "result:0").hasFocus())
        row(activity, "result:0").performClick()
        assertTrue(row(activity, "city").hasFocus())
        assertTrue(rowText(activity, "city").contains("Gent"))
        assertEquals(gent, SettingsStore(context).load().location)
        assertEquals("Weer in Gent", activity.findViewById<TextView>(R.id.forecastTitle).text)
    }

    @Test
    fun rowRotatesLikeACarouselWhenMoreAppsThanVisibleTiles() {
        val slots = listOf("com.netflix.ninja", "com.spotify.tv.android", "org.ted.android", "com.android.vending")
        SettingsStore(context).save(LauncherSettings(slots = slots.map { AppSlot(it) }, visibleTiles = 3))
        withActivity { activity, _ ->
            val row = activity.findViewById<LinearLayout>(R.id.row)
            val scroll = activity.findViewById<View>(R.id.rowScroll)
            assertTrue("Only three tiles fit", scroll.width < row.width)
            val first = row.getChildAt(0)
            first.requestFocus()
            pressRemote(activity, KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue("Left on the first tile wraps to Apps", activity.findViewById<View>(R.id.appsTile).hasFocus())
            pressRemote(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("Right on Apps wraps to the first tile", first.hasFocus())
        }
    }

    @Test
    fun focusZoomFitsInsideTheSpacingAndTheRow() {
        for (spacing in listOf(4, 24)) {
            SettingsStore(context).save(LauncherSettings(slots = listOf(AppSlot("com.netflix.ninja"), AppSlot("org.ted.android")), tileSpacing = spacing))
            withActivity { activity, _ ->
                val row = activity.findViewById<LinearLayout>(R.id.row)
                val tile = row.getChildAt(0)
                tile.requestFocus()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                val growthX = tile.width * (tile.scaleX - 1f) / 2f
                val growthY = tile.height * (tile.scaleY - 1f) / 2f
                assertTrue("Tile zooms when focused (spacing $spacing)", tile.scaleX > 1f)
                assertTrue("Zoom stays at most 18%", tile.scaleX <= 1.18f + 1e-3f)
                assertTrue("Zoom leaves a 2 dp gap to the neighbor (spacing $spacing)",
                    growthX <= dp(activity, spacing - 2f) + 1f)
                assertTrue("Row has room for the zoom horizontally", row.paddingLeft >= growthX)
                assertTrue("Row has room for the zoom vertically", row.paddingTop >= growthY && row.paddingBottom >= growthY)
            }
        }
    }

    private fun open(activity: MainActivity) {
        activity.findViewById<View>(R.id.gear).run { requestFocus(); performClick() }
        layout(activity)
    }

    private fun row(activity: MainActivity, key: String): View =
        activity.findViewById<View>(R.id.settingsList).findViewWithTag(key) ?: throw AssertionError("No row $key")

    private fun rowText(activity: MainActivity, key: String) = row(activity, key).contentDescription.toString()

    private fun rowLabels(activity: MainActivity): List<String> {
        val row = activity.findViewById<LinearLayout>(R.id.row)
        return (0 until row.childCount).map { row.getChildAt(it).contentDescription.toString() }
    }

    private fun dp(activity: MainActivity, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics)

    private fun install(pkg: String, label: String) {
        val component = ComponentName(pkg, "$pkg.MainActivity")
        val app = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label; name = label }
        pm.addOrUpdateActivity(ActivityInfo().apply {
            packageName = pkg
            name = component.className
            nonLocalizedLabel = label
            applicationInfo = app
        })
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        })
    }

    private fun layout(activity: MainActivity) {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun withActivity(block: (MainActivity, ActivityController<MainActivity>) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible().windowFocusChanged(true)
        try {
            layout(controller.get())
            block(controller.get(), controller)
        } finally {
            controller.destroy()
        }
    }
}
