package be.kenny.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/** Keeps D-pad focus inside this panel while it is visible, so the app row behind it cannot be reached. */
class FocusTrapLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        if (visibility != View.VISIBLE || next == null) return next
        return if (isInside(next)) next else focused
    }

    private fun isInside(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }
}

/** Minimal horizontal slider for the settings rows; the row itself handles the left/right keys. */
class SliderView(context: Context) : View(context) {
    var fraction = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var highlighted = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun onDraw(canvas: Canvas) {
        val thumb = (if (highlighted) 6f else 4.5f) * density
        val line = 1.5f * density
        val cy = height / 2f
        val start = thumb
        val end = width - thumb
        val x = start + (end - start) * fraction
        fill.alpha = if (highlighted) 255 else 190
        canvas.drawRoundRect(start, cy - line, end, cy + line, line, line, track)
        canvas.drawRoundRect(start, cy - line, x, cy + line, line, line, fill)
        canvas.drawCircle(x, cy, thumb, fill)
    }
}
