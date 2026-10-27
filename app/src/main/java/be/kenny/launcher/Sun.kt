package be.kenny.launcher

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** NOAA sunrise/sunset approximation (accurate to ~1-2 minutes). */
object Sun {
    const val LAT = 50.92
    const val LON = 4.05

    enum class Phase { DAY, EVENING, NIGHT }

    fun times(date: LocalDate, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime> {
        val g = 2 * PI / 365 * (date.dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val lat = Math.toRadians(LAT)
        val ha = Math.toDegrees(
            acos(cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl))
        )
        val riseMin = 720 - 4 * (LON + ha) - eqTime
        val setMin = 720 - 4 * (LON - ha) - eqTime
        val base = date.atStartOfDay(ZoneOffset.UTC)
        return base.plusSeconds((riseMin * 60).toLong()).withZoneSameInstant(zone) to
            base.plusSeconds((setMin * 60).toLong()).withZoneSameInstant(zone)
    }

    /** Day from sunrise until 1h before sunset, evening ±1h around sunset, night otherwise. */
    fun phase(now: ZonedDateTime): Phase {
        val (rise, set) = times(now.toLocalDate(), now.zone)
        return when {
            now.isBefore(rise) -> Phase.NIGHT
            now.isBefore(set.minusHours(1)) -> Phase.DAY
            now.isBefore(set.plusHours(1)) -> Phase.EVENING
            else -> Phase.NIGHT
        }
    }
}
