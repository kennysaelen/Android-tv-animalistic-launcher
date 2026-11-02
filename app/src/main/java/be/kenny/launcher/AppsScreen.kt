package be.kenny.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt

/** Full-screen grid of every installed TV app, in launcher style. */
class AppsScreen(private val root: View, onBack: () -> Unit) {
    data class App(val label: String, val component: ComponentName, val banner: Drawable?, val isBanner: Boolean)

    private val context = root.context
    private val pm = context.packageManager
    private val grid = root.findViewById<LinearLayout>(R.id.appsGrid)
    private val scroll = root.findViewById<ScrollView>(R.id.appsScroll)
    private val back = root.findViewById<TextView>(R.id.appsBack)
    private val empty = root.findViewById<TextView>(R.id.appsEmpty)
    private val items = mutableListOf<Pair<View, App>>()
    private var focusedComponent: ComponentName? = null
    private val dim = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(0.85f)
        postConcat(ColorMatrix().apply { setScale(0.72f, 0.72f, 0.72f, 1f) })
    })

    val isOpen: Boolean get() = root.visibility == View.VISIBLE
    val apps: List<App> get() = items.map { it.second }

    init {
        scroll.isFocusable = false
        back.setOnClickListener { onBack() }
        back.nextFocusLeftId = back.id
        back.nextFocusDownId = back.id
        back.nextFocusRightId = back.id
    }

    fun show() {
        root.visibility = View.VISIBLE
        focusedComponent = null
        refresh()
        scroll.scrollTo(0, 0)
        focusTarget().requestFocus()
    }

    fun hide() {
        root.visibility = View.GONE
    }

    fun restoreFocus() {
        if (items.any { it.first.hasFocus() } || back.hasFocus()) return
        focusTarget().requestFocus()
    }

    private fun focusTarget(): View =
        items.firstOrNull { it.second.component == focusedComponent }?.first ?: items.firstOrNull()?.first ?: back

    /** Reloads the app list, keeping focus on the same app when it is still installed. */
    fun refresh() {
        build(load())
        if (isOpen) restoreFocus()
    }

    private fun load(): List<App> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val collator = Collator.getInstance(Locale("nl", "BE"))
        return pm.queryIntentActivities(query, 0)
            .filter { isVisible(it.activityInfo.packageName) }
            .distinctBy { it.activityInfo.packageName }
            .map { toApp(it) }
            .sortedWith(compareBy(collator) { it.label })
    }

    private fun isVisible(pkg: String) =
        pkg != context.packageName && HIDDEN_PACKAGE_PREFIXES.none { pkg.startsWith(it) }

    private fun toApp(ri: ResolveInfo): App {
        val info = ri.activityInfo
        val banner = runCatching { info.loadBanner(pm) }.getOrNull()
            ?: runCatching { info.applicationInfo.loadBanner(pm) }.getOrNull()
        val icon = if (banner == null) runCatching { ri.loadIcon(pm) }.getOrNull() else null
        return App(ri.loadLabel(pm).toString(), ComponentName(info.packageName, info.name), banner ?: icon, banner != null)
    }

    private fun build(apps: List<App>) {
        grid.removeAllViews()
        items.clear()
        empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        val density = context.resources.displayMetrics.density
        val gap = (GAP_DP * density).roundToInt()
        val inflater = LayoutInflater.from(context)
        for (chunk in apps.chunked(COLUMNS)) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
            for ((column, app) in chunk.withIndex()) {
                val item = inflater.inflate(R.layout.apps_item, row, false)
                item.id = View.generateViewId()
                (item.layoutParams as LinearLayout.LayoutParams).marginEnd = if (column == COLUMNS - 1) 0 else gap
                val banner = item.findViewById<ImageView>(R.id.appsItemBanner)
                banner.setImageDrawable(app.banner)
                if (!app.isBanner) {
                    banner.scaleType = ImageView.ScaleType.FIT_CENTER
                    val pad = (14 * density).roundToInt()
                    banner.setPadding(pad, pad, pad, pad)
                }
                banner.clipToOutline = true
                banner.colorFilter = dim
                item.findViewById<TextView>(R.id.appsItemLabel).text = app.label
                item.contentDescription = app.label
                item.setOnClickListener { launch(app) }
                item.setOnFocusChangeListener { view, hasFocus ->
                    val scale = if (hasFocus) 1.1f else 1f
                    view.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                    banner.colorFilter = if (hasFocus) null else dim
                    banner.foreground = if (hasFocus) context.getDrawable(R.drawable.tile_ring) else null
                    if (hasFocus) {
                        focusedComponent = app.component
                        back.nextFocusUpId = view.id
                    }
                }
                items += item to app
                row.addView(item)
            }
        }
        linkFocus()
    }

    private fun linkFocus() {
        val rows = items.chunked(COLUMNS)
        for ((r, row) in rows.withIndex()) {
            for ((c, pair) in row.withIndex()) {
                val view = pair.first
                view.nextFocusLeftId = row.getOrNull(c - 1)?.first?.id ?: view.id
                view.nextFocusRightId = row.getOrNull(c + 1)?.first?.id ?: view.id
                view.nextFocusUpId = rows.getOrNull(r - 1)?.let { it[c.coerceAtMost(it.lastIndex)].first.id } ?: view.id
                view.nextFocusDownId = rows.getOrNull(r + 1)?.let { it[c.coerceAtMost(it.lastIndex)].first.id } ?: back.id
            }
        }
        back.nextFocusUpId = items.firstOrNull()?.first?.id ?: back.id
    }

    private fun launch(app: App) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("KennysLauncher", "Cannot launch ${app.component}", e)
        }
    }

    companion object {
        const val COLUMNS = 6
        private const val GAP_DP = 16
        /** Philips system entries (channels, content explorer, manual, app gallery, Watch TV). */
        val HIDDEN_PACKAGE_PREFIXES = listOf("org.droidtv.")
    }
}
