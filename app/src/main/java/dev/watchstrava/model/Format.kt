package dev.watchstrava.model

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

object Format {
    /** "4:35" for 275 s/km, "--:--" when unknown. */
    fun pace(secPerKm: Int?): String =
        if (secPerKm == null || secPerKm <= 0 || secPerKm >= 60 * 60) "--:--"
        else "%d:%02d".format(secPerKm / 60, secPerKm % 60)

    /** "1:05:12" or "25:13". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** Short duration for setup screens: "45 s", "1:30", "20 min". */
    fun seconds(sec: Int): String = when {
        sec < 60 -> "$sec s"
        sec % 60 == 0 -> "${sec / 60} min"
        else -> "%d:%02d".format(sec / 60, sec % 60)
    }

    /** "5,02" / "5.02" (km, decimal separator of the current locale). */
    fun km(meters: Double): String = String.format(Locale.getDefault(), "%.2f", meters / 1000)

    /** "400 m" below 1 km, otherwise "1,50 km" ("2 km" when whole). */
    fun meters(meters: Double): String {
        if (meters < 1000) return "${meters.roundToInt()} m"
        val locale = Locale.getDefault()
        val sep = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return String.format(locale, "%.2f km", meters / 1000).replace("${sep}00 km", " km")
    }

    /** "58" km/h from m/s, "--" when unknown. */
    fun kmh(mps: Double?): String = if (mps == null) "--" else (mps * 3.6).roundToInt().toString()

    /** Heights stay in metres: "1234 m". */
    fun height(m: Double?): String = if (m == null) "--" else "${m.roundToInt()} m"

    /** Vertical speed "+420" / "−130" (m/h). */
    fun vspeed(mPerH: Int?): String = when {
        mPerH == null -> "--"
        mPerH > 0 -> "+$mPerH"
        mPerH < 0 -> "−${-mPerH}"
        else -> "0"
    }

    /** "+0:42" ahead / "−1:05" behind. */
    fun ahead(sec: Int): String = (if (sec >= 0) "+" else "−") + duration(kotlin.math.abs(sec) * 1000L)

    /** [untilLap] is the localized wording for an open step ("fino a GIRO"). */
    fun length(length: StepLength, untilLap: String): String = when (length) {
        is StepLength.Time -> seconds(length.seconds)
        is StepLength.Distance -> meters(length.meters.toDouble())
        StepLength.Open -> untilLap
    }

    fun target(target: Target, maxHr: Int): String = when (target) {
        Target.None -> ""
        is Target.Pace -> "${pace(target.fastest)}–${pace(target.slowest)} /km"
        is Target.Zone -> "Z${target.zone} · ${Zones.lower(target.zone, maxHr)}–${Zones.upper(target.zone, maxHr)} bpm"
    }

    /** Average pace over a distance/time, null for tiny distances. */
    fun paceOf(distanceM: Double, ms: Long): Int? =
        if (distanceM < 10) null else (ms / 1000.0 / (distanceM / 1000)).roundToInt()
}
