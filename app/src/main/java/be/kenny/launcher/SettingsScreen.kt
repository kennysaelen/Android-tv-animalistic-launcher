package be.kenny.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Launcher settings panel. Every change is applied immediately through [onChange], so the clock,
 * wallpaper and app row behind the panel act as a live preview.
 */
class SettingsScreen(
    private val root: View,
    private val onChange: (LauncherSettings) -> Unit,
    private val onSystemSettings: () -> Unit,
    private val onClose: () -> Unit,
) {
    private enum class Page { MAIN, SLOTS, ADD_APP, CITY }

    private val context: Context = root.context
    private val pm = context.packageManager
    private val handler = Handler(Looper.getMainLooper())
    private val title = root.findViewById<TextView>(R.id.settingsTitle)
    private val hint = root.findViewById<TextView>(R.id.settingsHint)
    private val scroll = root.findViewById<ScrollView>(R.id.settingsScroll)
    private val list = root.findViewById<LinearLayout>(R.id.settingsList)
    private val updaters = mutableListOf<() -> Unit>()
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private var page = Page.MAIN
    private var movingIndex = -1
    private var resetArmed = false
    private var searchToken = 0
    private var cityQuery = ""
    private var cityResults = emptyList<WeatherLocation>()
    private var cityStatus: String? = null

    var settings = LauncherSettings()
        private set

    /** Test hooks: the city search runs on a background thread by default. */
    var geocoder: (String) -> List<WeatherLocation> = { Geocoding.search(it) }
    var background: (Runnable) -> Unit = { Thread(it, "Geocoding").start() }

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    init {
        scroll.isFocusable = false
    }

    fun show(current: LauncherSettings) {
        settings = current
        root.visibility = View.VISIBLE
        showPage(Page.MAIN, null)
    }

    fun hide() {
        root.visibility = View.GONE
        searchToken++
        movingIndex = -1
        resetArmed = false
        cityResults = emptyList()
        cityStatus = null
        hideKeyboard()
    }

    /** BACK returns to the previous page; on the main page it closes the panel. */
    fun back() {
        when {
            movingIndex >= 0 -> {
                val index = movingIndex
                movingIndex = -1
                showPage(Page.SLOTS, "slot:$index")
            }
            page == Page.ADD_APP -> showPage(Page.SLOTS, "add")
            page == Page.SLOTS -> showPage(Page.MAIN, "slots")
            page == Page.CITY -> {
                searchToken++
                showPage(Page.MAIN, "city")
            }
            else -> onClose()
        }
    }

    fun restoreFocus() {
        if (!root.hasFocus()) focusFirst()
    }

    /** Refreshes app labels after packages are installed or removed. */
    fun refreshApps() {
        if (isOpen && (page == Page.SLOTS || page == Page.ADD_APP)) showPage(page, (root.findFocus()?.tag as? String))
    }

    private fun showPage(target: Page, focusKey: String?) {
        page = target
        list.removeAllViews()
        updaters.clear()
        hint.visibility = View.GONE
        when (target) {
            Page.MAIN -> buildMain()
            Page.SLOTS -> buildSlots()
            Page.ADD_APP -> buildAddApp()
            Page.CITY -> buildCity()
        }
        scroll.scrollTo(0, 0)
        val focus = focusKey?.let { list.findViewWithTag<View>(it) }
        if (focus != null) focus.requestFocus() else focusFirst()
    }

    private fun focusFirst() {
        list.getFocusables(View.FOCUS_DOWN).firstOrNull()?.requestFocus()
    }

    private fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val next = transform(settings).normalized()
        if (next == settings) return
        settings = next
        onChange(next)
        updaters.forEach { it() }
    }

    // ---------- pages ----------

    private fun buildMain() {
        title.setText(R.string.settings)
        choice("wallpaper", R.string.settings_wallpaper, WallpaperMode.values().toList(), ::wallpaperName,
            { it.wallpaper }, { s, v -> s.copy(wallpaper = v) })
        slider("clock", R.string.settings_clock_size, LauncherSettings.CLOCK_SIZE, 4,
            { it.clockSize }, { s, v -> s.copy(clockSize = v) })

        section(R.string.settings_section_row)
        action("slots", string(R.string.settings_slots), { string(R.string.settings_slots_count, settings.slots.size) }) {
            showPage(Page.SLOTS, null)
        }
        slider("visible", R.string.settings_visible_tiles, LauncherSettings.VISIBLE_TILES, 1,
            { it.visibleTiles }, { s, v -> s.copy(visibleTiles = v) })
        slider("spacing", R.string.settings_tile_spacing, LauncherSettings.TILE_SPACING, 2,
            { it.tileSpacing }, { s, v -> s.copy(tileSpacing = v) })
        slider("corners", R.string.settings_corners, LauncherSettings.CORNER_RADIUS, 1,
            { it.cornerRadius }, { s, v -> s.copy(cornerRadius = v) },
            { if (it == 0) string(R.string.settings_corners_straight) else it.toString() })
        slider("opacity", R.string.settings_opacity, LauncherSettings.UNSELECTED_OPACITY, 5,
            { it.unselectedOpacity }, { s, v -> s.copy(unselectedOpacity = v) },
            { string(R.string.settings_percent, it) })

        section(R.string.settings_section_weather)
        action("city", string(R.string.settings_city), { settings.location.name }) {
            showPage(Page.CITY, "cityInput")
        }
        slider("days", R.string.settings_forecast_days, LauncherSettings.FORECAST_DAYS, 1,
            { it.forecastDays }, { s, v -> s.copy(forecastDays = v) },
            { string(R.string.settings_days, it) })

        section(R.string.settings_section_other)
        action("system", string(R.string.settings_system)) { onSystemSettings() }
        resetArmed = false
        val reset = action("reset", string(R.string.settings_reset), chevron = false) {}
        val resetLabel = reset.getChildAt(0) as TextView
        reset.setOnClickListener {
            if (!resetArmed) {
                resetArmed = true
                resetLabel.setText(R.string.settings_reset_confirm)
            } else {
                resetArmed = false
                update { LauncherSettings() }
                resetLabel.setText(R.string.settings_reset_done)
            }
            reset.contentDescription = resetLabel.text
        }
        reset.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                resetArmed = false
                resetLabel.setText(R.string.settings_reset)
                reset.contentDescription = resetLabel.text
            }
        }
    }

    private fun buildSlots() {
        title.setText(R.string.settings_slots)
        hint.visibility = View.VISIBLE
        hint.setText(if (movingIndex >= 0) R.string.settings_slot_moving else R.string.settings_slots_hint)
        if (settings.slots.isEmpty()) info(string(R.string.settings_slots_empty))
        for ((index, slot) in settings.slots.withIndex()) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val appName = appLabel(slot)
            val name = baseRow("slot:$index")
            name.addView(textView("${index + 1}.  $appName", 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (index == movingIndex) name.addView(textView("▲▼", 13f))
            name.isActivated = index == movingIndex
            name.contentDescription = if (index == movingIndex) "$appName. ${string(R.string.settings_slot_moving)}" else appName
            name.setOnClickListener {
                movingIndex = if (movingIndex == index) -1 else index
                showPage(Page.SLOTS, "slot:$index")
            }
            name.setOnKeyListener { _, code, event ->
                if (movingIndex != index) return@setOnKeyListener false
                val to = when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> index - 1
                    KeyEvent.KEYCODE_DPAD_DOWN -> index + 1
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return@setOnKeyListener true
                    else -> return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_DOWN) moveSlot(index, to)
                true
            }
            val remove = baseRow("remove:$index").apply {
                gravity = Gravity.CENTER
                addView(textView("✕", 15f))
                contentDescription = string(R.string.settings_slot_remove, appName)
                setOnClickListener { removeSlot(index) }
            }
            line.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
            line.addView(remove, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(4)
            })
            list.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) })
        }
        if (settings.slots.size < LauncherSettings.MAX_SLOTS) {
            action("add", string(R.string.settings_add_app)) { showPage(Page.ADD_APP, null) }
        } else {
            info(string(R.string.settings_slots_full, LauncherSettings.MAX_SLOTS))
        }
    }

    private fun moveSlot(from: Int, to: Int) {
        if (to !in settings.slots.indices) return
        update { s -> s.copy(slots = s.slots.toMutableList().apply { add(to, removeAt(from)) }) }
        movingIndex = to
        showPage(Page.SLOTS, "slot:$to")
    }

    private fun removeSlot(index: Int) {
        update { s -> s.copy(slots = s.slots.filterIndexed { i, _ -> i != index }) }
        movingIndex = -1
        val focus = if (settings.slots.isEmpty()) "add" else "slot:${index.coerceAtMost(settings.slots.lastIndex)}"
        showPage(Page.SLOTS, focus)
    }

    private fun buildAddApp() {
        title.setText(R.string.settings_add_title)
        val present = settings.slots.map { it.pkg }.toSet()
        val collator = Collator.getInstance(Locale("nl", "BE"))
        val apps = AppsScreen.installedActivities(context)
            .filter { it.activityInfo.packageName !in present }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .sortedWith(compareBy(collator) { it.first })
        if (apps.isEmpty()) info(string(R.string.settings_add_empty))
        for ((label, pkg) in apps) {
            action("app:$pkg", label, chevron = false) {
                update { s -> s.copy(slots = s.slots + AppSlot(pkg)) }
                showPage(Page.SLOTS, "slot:${settings.slots.lastIndex}")
            }
        }
    }

    private fun buildCity() {
        title.setText(R.string.settings_city)
        hint.visibility = View.VISIBLE
        hint.text = string(R.string.settings_city_current, describe(settings.location))
        val input = EditText(context).apply {
            tag = "cityInput"
            id = View.generateViewId()
            setText(cityQuery)
            setSelection(text.length)
            setHint(R.string.settings_city_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            typeface = light
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            background = context.getDrawable(R.drawable.settings_row_background)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnEditorActionListener { view, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    search(view.text.toString())
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    cityQuery = s?.toString().orEmpty()
                }
            })
        }
        list.addView(input, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
        action("citySearch", string(R.string.settings_city_search), chevron = false) {
            search(input.text.toString())
        }
        cityStatus?.let { info(it) }
        for ((index, location) in cityResults.withIndex()) {
            action("result:$index", location.name, { location.detail }, chevron = false) {
                cityResults = emptyList()
                cityStatus = null
                cityQuery = ""
                update { it.copy(location = location) }
                showPage(Page.MAIN, "city")
            }
        }
    }

    private fun search(text: String) {
        val query = text.trim()
        cityQuery = query
        hideKeyboard()
        if (query.length < 2) {
            cityResults = emptyList()
            cityStatus = string(R.string.settings_city_short)
            showPage(Page.CITY, "cityInput")
            return
        }
        val token = ++searchToken
        cityResults = emptyList()
        cityStatus = string(R.string.settings_city_searching)
        showPage(Page.CITY, "citySearch")
        background(Runnable {
            val result = runCatching { geocoder(query) }
            handler.post {
                if (token != searchToken || !isOpen || page != Page.CITY) return@post
                cityResults = result.getOrDefault(emptyList())
                cityStatus = when {
                    result.isFailure -> string(R.string.settings_city_error)
                    cityResults.isEmpty() -> string(R.string.settings_city_none)
                    else -> null
                }
                showPage(Page.CITY, if (cityResults.isEmpty()) "citySearch" else "result:0")
            }
        })
    }

    // ---------- rows ----------

    private fun slider(
        key: String,
        label: Int,
        range: IntRange,
        step: Int,
        get: (LauncherSettings) -> Int,
        set: (LauncherSettings, Int) -> LauncherSettings,
        format: (Int) -> String = { it.toString() },
    ) {
        val row = baseRow(key)
        val name = string(label)
        row.addView(textView(name, 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        val bar = SliderView(context)
        row.addView(bar, LinearLayout.LayoutParams(dp(112), dp(18)))
        val value = valueView()
        row.addView(value, LinearLayout.LayoutParams(dp(66), WRAP))
        val refresh = {
            val v = get(settings)
            bar.fraction = (v - range.first).toFloat() / (range.last - range.first)
            value.text = format(v)
            row.contentDescription = "$name, ${value.text}"
        }
        updaters += refresh
        refresh()
        row.setOnFocusChangeListener { _, hasFocus -> bar.highlighted = hasFocus }
        row.setOnKeyListener { _, code, event ->
            horizontal(code, event) { dir -> update { s -> set(s, (get(s) + dir * step).coerceIn(range)) } }
        }
        list.addView(row)
    }

    private fun <T> choice(
        key: String,
        label: Int,
        options: List<T>,
        names: (T) -> String,
        get: (LauncherSettings) -> T,
        set: (LauncherSettings, T) -> LauncherSettings,
    ) {
        val row = baseRow(key)
        val name = string(label)
        row.addView(textView(name, 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        val value = valueView().apply { gravity = Gravity.CENTER }
        row.addView(value, LinearLayout.LayoutParams(dp(170), WRAP))
        val refresh = {
            value.text = "‹   ${names(get(settings))}   ›"
            row.contentDescription = "$name, ${names(get(settings))}"
        }
        updaters += refresh
        refresh()
        val step = { dir: Int ->
            update { s -> set(s, options[Math.floorMod(options.indexOf(get(s)) + dir, options.size)]) }
        }
        row.setOnClickListener { step(1) }
        row.setOnKeyListener { _, code, event -> horizontal(code, event, step) }
        list.addView(row)
    }

    private fun action(
        key: String,
        label: String,
        value: (() -> String)? = null,
        chevron: Boolean = true,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = baseRow(key)
        row.addView(textView(label, 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        val valueText = value?.let { valueView().also { v -> row.addView(v, LinearLayout.LayoutParams(WRAP, WRAP)) } }
        if (chevron) row.addView(textView("›", 18f, 0.7f).apply { setPadding(dp(8), 0, 0, 0) })
        val refresh = {
            valueText?.text = value?.invoke()
            row.contentDescription = listOfNotNull(label, value?.invoke()).joinToString(", ")
        }
        updaters += refresh
        refresh()
        row.setOnClickListener { onClick() }
        list.addView(row)
        return row
    }

    private fun section(text: Int) {
        list.addView(textView(string(text), 12f, 0.6f), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(12)
            bottomMargin = dp(3)
            marginStart = dp(10)
        })
    }

    private fun info(text: String) {
        list.addView(textView(text, 13f, 0.75f).apply {
            maxLines = 3
            setSingleLine(false)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
            marginStart = dp(10)
            marginEnd = dp(10)
        })
    }

    private fun baseRow(key: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(36)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = context.getDrawable(R.drawable.settings_row_background)
        isFocusable = true
        isClickable = true
        tag = key
        id = View.generateViewId()
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) }
    }

    private fun textView(text: String, size: Float, alpha: Float = 1f) = TextView(context).apply {
        this.text = text
        typeface = light
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        this.alpha = alpha
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun valueView() = textView("", 14f, 0.85f).apply {
        gravity = Gravity.END
        maxWidth = dp(190)
    }

    private inline fun horizontal(code: Int, event: KeyEvent, change: (Int) -> Unit): Boolean {
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) change(dir)
        return true
    }

    // ---------- helpers ----------

    private fun appLabel(slot: AppSlot): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(slot.pkg, 0)).toString()
    } catch (e: Exception) {
        string(R.string.settings_slot_missing, slot.pkg)
    }

    private fun wallpaperName(mode: WallpaperMode) = string(when (mode) {
        WallpaperMode.AUTO -> R.string.settings_wallpaper_auto
        WallpaperMode.DAY -> R.string.settings_wallpaper_day
        WallpaperMode.EVENING -> R.string.settings_wallpaper_evening
        WallpaperMode.NIGHT -> R.string.settings_wallpaper_night
    })

    private fun describe(location: WeatherLocation) =
        listOf(location.name, location.detail).filter { it.isNotBlank() }.joinToString(", ")

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
