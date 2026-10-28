package be.kenny.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], qualifiers = "w960dp-h540dp-land-xhdpi")
class MainActivityWeatherTest {
    @Test
    fun shortcutWorksWithoutWeatherOrInstalledAppsAndBackRestoresItsFocus() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible().windowFocusChanged(true)
        val activity = controller.get()
        try {
            val shortcut = activity.findViewById<View>(R.id.weatherShortcut)
            assertTrue(shortcut.isShown)
            assertTrue(shortcut.hasFocus())
            press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.forecast).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.statusRow).visibility)
            assertTrue("Shared clock stays visible", activity.findViewById<View>(R.id.time).isShown)
            press(activity, KeyEvent.KEYCODE_BACK)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.forecast).visibility)
            assertTrue(shortcut.hasFocus())
            press(activity, KeyEvent.KEYCODE_BACK)
            assertFalse(activity.isFinishing)
            assertTrue(shortcut.hasFocus())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun appRowAndSettingsHavePredictableNavigationThroughShortcut() {
        val pm = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        val component = ComponentName("com.netflix.ninja", "com.netflix.ninja.MainActivity")
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        })
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible().windowFocusChanged(true)
        val activity = controller.get()
        try {
            val tile = activity.findViewById<LinearLayout>(R.id.row).getChildAt(0)
            assertNotNull(tile)
            tile.requestFocus()
            press(activity, KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(activity.findViewById<View>(R.id.weatherShortcut).hasFocus())
            press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(activity.findViewById<View>(R.id.gear).hasFocus())
            press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
            press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.forecast).visibility)
            activity.sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:com.netflix.ninja")))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("An app-row rebuild must not steal forecast focus",
                activity.findViewById<View>(R.id.forecast).hasFocus())
            press(activity, KeyEvent.KEYCODE_BACK)
            assertTrue(activity.findViewById<View>(R.id.weatherShortcut).hasFocus())
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(activity.findViewById<LinearLayout>(R.id.row).getChildAt(0).hasFocus())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun homeIntentClosesForecastWithoutExitingLauncher() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible().windowFocusChanged(true)
        val activity = controller.get()
        try {
            activity.findViewById<View>(R.id.weatherShortcut).performClick()
            controller.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.forecast).visibility)
            assertTrue(activity.findViewById<View>(R.id.weatherShortcut).hasFocus())
            assertFalse(activity.isFinishing)
        } finally {
            controller.destroy()
        }
    }

    private fun press(activity: MainActivity, keyCode: Int) {
        pressRemote(activity, keyCode)
    }
}
