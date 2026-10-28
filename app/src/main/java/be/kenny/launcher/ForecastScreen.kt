package be.kenny.launcher

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class ForecastScreen(private val root: View, onBack: () -> Unit, onRetry: () -> Unit) {
    private val context = root.context
    private val daysRow = root.findViewById<LinearLayout>(R.id.forecastDays)
    private val back = root.findViewById<TextView>(R.id.forecastBack)
    private val retry = root.findViewById<TextView>(R.id.forecastRetry)
    private val cards = mutableListOf<Pair<View, ForecastDay>>()
    private val locale = Locale("nl", "BE")
    private val weekday = DateTimeFormatter.ofPattern("EEEE", locale)
    private val dateTime = DateTimeFormatter.ofPattern("d MMM HH:mm", locale)
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private var forecast: WeatherForecast? = null
    private var today = LocalDate.now(Weather.zone)
    private var selectedDate: LocalDate? = null

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    init {
        back.setOnClickListener { onBack() }
        retry.setOnClickListener { onRetry() }
        back.nextFocusLeftId = back.id
        back.nextFocusDownId = back.id
        retry.nextFocusUpId = retry.id
        retry.nextFocusRightId = retry.id
        retry.nextFocusLeftId = back.id
    }

    fun show() {
        root.visibility = View.VISIBLE
        focusSelection()
    }

    fun hide() {
        root.visibility = View.GONE
    }

    fun restoreFocus() {
        if (root.hasFocus()) return
        focusSelection()
    }

    private fun focusSelection() {
        val selected = cards.firstOrNull { it.second.date == selectedDate }?.first
        (selected ?: cards.firstOrNull()?.first ?: if (retry.visibility == View.VISIBLE) retry else back).requestFocus()
    }

    fun render(
        data: WeatherForecast?,
        loading: Boolean,
        error: Boolean,
        offline: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(Weather.zone),
    ) {
        val hadDayFocus = cards.any { it.first.hasFocus() }
        val hadRetryFocus = retry.hasFocus()
        val hadDays = cards.isNotEmpty()
        val localToday = now.withZoneSameInstant(Weather.zone).toLocalDate()
        val rebuild = data != forecast || today != localToday
        forecast = data
        today = localToday
        val days = data?.days.orEmpty().filter { it.date >= today }
        val stale = data != null && (Duration.between(data.fetchedAt, now.toInstant()).toMinutes() >= 60 ||
            Duration.between(data.current.time.toInstant(), now.toInstant()).toMinutes() >= 90)
        val needsRetry = !loading && (error || offline || stale || data?.hasMissingValues == true || days.isEmpty())
        retry.visibility = if (needsRetry) View.VISIBLE else View.GONE

        val status = mutableListOf(
            if (data == null) context.getString(R.string.weather_provider)
            else context.getString(R.string.weather_updated, data.fetchedAt.atZone(Weather.zone).format(dateTime))
        )
        val warning = when {
            loading -> if (data == null) R.string.weather_loading else R.string.weather_updating
            offline -> if (data == null) R.string.weather_offline else R.string.weather_cached_offline
            error -> if (data == null) R.string.weather_load_error else R.string.weather_cached_error
            stale -> R.string.weather_stale
            data?.hasMissingValues == true -> R.string.weather_partial
            else -> null
        }
        if (warning != null) status += context.getString(warning)
        root.findViewById<TextView>(R.id.forecastStatus).text = status.joinToString("\n")
        val message = root.findViewById<TextView>(R.id.forecastMessage)
        message.visibility = if (days.isEmpty()) View.VISIBLE else View.GONE
        message.setText(when {
            loading -> R.string.weather_loading
            offline -> R.string.weather_offline
            error -> R.string.weather_load_error
            else -> R.string.weather_no_days
        })
        root.findViewById<View>(R.id.forecastDetails).visibility = if (days.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.forecastDaysHeading).visibility = if (days.isEmpty()) View.GONE else View.VISIBLE
        if (rebuild) buildDays(days)
        if (cards.isNotEmpty()) {
            val selected = cards.firstOrNull { it.second.date == selectedDate } ?: cards.first()
            select(selected.first, selected.second)
        } else {
            root.findViewById<TextView>(R.id.forecastLabel).text = ""
        }
        updateNavigation()
        if (isOpen) {
            if (hadDayFocus || (!hadDays && cards.isNotEmpty())) {
                cards.firstOrNull { it.second.date == selectedDate }?.first?.requestFocus()
            } else if (hadRetryFocus && retry.visibility != View.VISIBLE) {
                back.requestFocus()
            }
            restoreFocus()
        }
    }

    private fun buildDays(days: List<ForecastDay>) {
        daysRow.removeAllViews()
        cards.clear()
        for ((index, day) in days.withIndex()) {
            val card = LayoutInflater.from(context).inflate(R.layout.weather_day, daysRow, false)
            card.id = View.generateViewId()
            (card.layoutParams as LinearLayout.LayoutParams).marginEnd =
                if (index == days.lastIndex) 0 else (12 * context.resources.displayMetrics.density).roundToInt()
            card.findViewById<TextView>(R.id.weatherDayName).text = dayName(day)
            card.findViewById<ImageView>(R.id.weatherDayIcon).setImageResource(WeatherDisplay.icon(day.code))
            card.findViewById<TextView>(R.id.weatherDayHigh).text = WeatherDisplay.temperature(day.high)
            card.findViewById<TextView>(R.id.weatherDayLow).text = WeatherDisplay.temperature(day.low)
            card.findViewById<TextView>(R.id.weatherDayRain).text =
                context.getString(R.string.weather_rain_value, WeatherDisplay.percent(day.rainChance))
            card.contentDescription = context.getString(R.string.weather_day_description,
                dayName(day), context.getString(WeatherDisplay.description(day.code)),
                WeatherDisplay.temperature(day.high), WeatherDisplay.temperature(day.low),
                WeatherDisplay.percent(day.rainChance))
            card.setOnClickListener {
                card.requestFocus()
                select(card, day)
            }
            card.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1f
                view.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                view.findViewById<View>(R.id.weatherDayContent).alpha = if (hasFocus) 1f else .78f
                if (hasFocus) select(view, day)
            }
            cards += card to day
            daysRow.addView(card)
        }
    }

    private fun select(view: View, day: ForecastDay) {
        val data = forecast ?: return
        selectedDate = day.date
        val current = data.current.takeIf { day.date == today && it.time.toLocalDate() == today }
        root.findViewById<TextView>(R.id.forecastTitle).text = context.getString(R.string.weather_title, Weather.LOCATION_NAME)
        root.findViewById<ImageView>(R.id.forecastIcon).setImageResource(
            WeatherDisplay.icon(if (current != null) current.code else day.code, current?.isDay ?: true))
        root.findViewById<TextView>(R.id.forecastTemperature).text =
            WeatherDisplay.temperature(if (current != null) current.temperature else day.high)
        root.findViewById<TextView>(R.id.forecastCondition).setText(
            WeatherDisplay.description(if (current != null) current.code else day.code, current?.isDay ?: true))
        root.findViewById<TextView>(R.id.forecastRange).text =
            context.getString(R.string.weather_range, WeatherDisplay.temperature(day.high), WeatherDisplay.temperature(day.low))
        root.findViewById<TextView>(R.id.forecastFeelsLikeLabel).setText(
            if (current != null) R.string.weather_feels_like else R.string.weather_feels_like_max)
        root.findViewById<TextView>(R.id.forecastFeelsLike).text =
            WeatherDisplay.temperature(if (current != null) current.feelsLike else day.feelsLikeHigh)
        root.findViewById<TextView>(R.id.forecastRain).text = WeatherDisplay.percent(day.rainChance)
        root.findViewById<TextView>(R.id.forecastWindLabel).setText(
            if (current != null) R.string.weather_wind else R.string.weather_wind_max)
        val speed = if (current != null) current.windSpeed else day.windSpeed
        val direction = if (current != null) current.windDirection else day.windDirection
        root.findViewById<TextView>(R.id.forecastWind).text = context.getString(
            R.string.weather_wind_value, WeatherDisplay.direction(direction), speed?.roundToInt()?.toString() ?: "--")
        root.findViewById<TextView>(R.id.forecastSunset).text = day.sunset?.format(clock) ?: "--"
        root.findViewById<TextView>(R.id.forecastLabel).text = if (current != null) {
            "${dayName(day)} · ${context.getString(R.string.weather_observed, current.time.format(clock))}"
        } else {
            context.getString(R.string.weather_maximum_label, dayName(day))
        }
        back.nextFocusUpId = view.id
        retry.nextFocusDownId = view.id
    }

    private fun updateNavigation() {
        val selectedId = cards.firstOrNull { it.second.date == selectedDate }?.first?.id
            ?: cards.firstOrNull()?.first?.id ?: back.id
        val topId = if (retry.visibility == View.VISIBLE) retry.id else back.id
        back.nextFocusUpId = selectedId
        back.nextFocusRightId = if (retry.visibility == View.VISIBLE) retry.id else selectedId
        retry.nextFocusDownId = selectedId
        cards.forEachIndexed { index, (view, _) ->
            view.nextFocusLeftId = cards.getOrNull(index - 1)?.first?.id ?: view.id
            view.nextFocusRightId = cards.getOrNull(index + 1)?.first?.id ?: view.id
            view.nextFocusUpId = topId
            view.nextFocusDownId = back.id
        }
    }

    private fun dayName(day: ForecastDay): String =
        if (day.date == today) context.getString(R.string.weather_today)
        else day.date.format(weekday).replaceFirstChar { it.titlecase(locale) }
}
