package be.kenny.launcher

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

internal fun pressRemote(activity: Activity, keyCode: Int) {
    shadowOf(Looper.getMainLooper()).idle()
    // Directional focus navigation happens in ViewRootImpl, after Activity key dispatch.
    val root = ReflectionHelpers.callInstanceMethod<Any>(activity.window.decorView, "getViewRootImpl")
    val enqueue = root.javaClass.getDeclaredMethod("enqueueInputEvent",
        InputEvent::class.java, Class.forName("android.view.InputEventReceiver"),
        Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    enqueue.isAccessible = true
    val time = SystemClock.uptimeMillis()
    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
        val event = KeyEvent(time, time, action, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD)
        enqueue.invoke(root, event, null, 0, true)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
