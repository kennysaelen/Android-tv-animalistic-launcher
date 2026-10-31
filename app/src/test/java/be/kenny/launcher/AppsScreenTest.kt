package be.kenny.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
class AppsScreenTest {
    private val pm get() = shadowOf(RuntimeEnvironment.getApplication().packageManager)

    @Before
    fun installApps() {
        install("com.android.vending", "Google Play Store")
        install("com.netflix.ninja", "Netflix")
        install("org.ted.android", "TED")
        install("com.spotify.tv.android", "Spotify")
        install("org.droidtv.playtv", "TV kijken")
        install("org.droidtv.channels", "Zenders")
        for (i in 1..6) install("com.example.app$i", "Zz app $i")
    }

    @Test
    fun rowHasNoWatchTvAndEndsWithAppsTile() = withActivity { activity, _ ->
        val row = activity.findViewById<LinearLayout>(R.id.row)
        val labels = (0 until row.childCount).map { row.getChildAt(it).contentDescription.toString() }
        assertEquals(listOf("Netflix", "Spotify", "Alle apps"), labels)
        assertFalse(labels.contains("TV kijken"))
    }

    @Test
    fun gridListsVisibleAppsSortedAndHidesPhilipsAndLauncherEntries() = withActivity { activity, _ ->
        open(activity)
        val labels = gridItems(activity).map { it.contentDescription.toString() }
        assertEquals(listOf("Google Play Store", "Netflix", "Spotify", "TED") + (1..6).map { "Zz app $it" }, labels)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.apps).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.row).visibility)
        assertTrue(activity.findViewById<View>(R.id.time).isShown)
        assertTrue(gridItems(activity).first().hasFocus())
    }

    @Test
    fun dpadMovesThroughGridAndDownFromLastRowReachesBack() = withActivity { activity, _ ->
        open(activity)
        val items = gridItems(activity)
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(items[1].hasFocus())
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down keeps the column", items[AppsScreen.COLUMNS + 1].hasFocus())
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        val back = activity.findViewById<View>(R.id.appsBack)
        assertTrue(back.hasFocus())
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up from Back returns to the last item", items[AppsScreen.COLUMNS + 1].hasFocus())
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_UP)
        pressRemote(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Top row stays put", items[1].hasFocus())
    }

    @Test
    fun clickLaunchesExplicitComponent() = withActivity { activity, _ ->
        open(activity)
        gridItems(activity).first().performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ComponentName("com.android.vending", "com.android.vending.MainActivity"), started.component)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun backAndHomeCloseGridAndRefocusAppsTile() = withActivity { activity, controller ->
        val tile = activity.findViewById<View>(R.id.appsTile)
        tile.performClick()
        pressRemote(activity, KeyEvent.KEYCODE_BACK)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.apps).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.row).visibility)
        assertTrue(tile.hasFocus())
        tile.performClick()
        controller.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.apps).visibility)
        assertTrue(tile.hasFocus())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun packageChangesRefreshOpenGrid() = withActivity { activity, _ ->
        open(activity)
        install("com.example.added", "Aaa nieuw")
        activity.sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:com.example.added")))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Aaa nieuw", gridItems(activity).first().contentDescription)
        assertTrue(activity.findViewById<View>(R.id.apps).hasFocus())
    }

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

    private fun open(activity: MainActivity) {
        activity.findViewById<View>(R.id.appsTile).performClick()
        layout(activity)
    }

    private fun layout(activity: MainActivity) {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun gridItems(activity: MainActivity): List<View> {
        val grid = activity.findViewById<ViewGroup>(R.id.appsGrid)
        return (0 until grid.childCount).flatMap { r ->
            val row = grid.getChildAt(r) as ViewGroup
            (0 until row.childCount).map { row.getChildAt(it) }
        }
    }

    private fun withActivity(block: (MainActivity, ActivityController<MainActivity>) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible().windowFocusChanged(true)
        try {
            block(controller.get(), controller)
        } finally {
            controller.destroy()
        }
    }
}
