package dev.watchstrava.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import dev.watchstrava.R
import dev.watchstrava.data.Settings
import dev.watchstrava.model.Format
import dev.watchstrava.model.Zones
import dev.watchstrava.service.WorkoutSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A value the user can place in one of the four slots of a run page.
 * [raceOnly] fields come last, so for the other pages an option index still equals the ordinal.
 */
enum class DataField(@StringRes val label: Int, @StringRes val short: Int, val raceOnly: Boolean = false) {
    PACE(R.string.field_pace, R.string.field_pace_short),
    LAP_PACE(R.string.field_lap_pace, R.string.field_lap_pace_short),
    AVG_PACE(R.string.field_avg_pace, R.string.field_avg_pace_short),
    HR(R.string.field_hr, R.string.field_hr_short),
    LAP_HR(R.string.field_lap_hr, R.string.field_lap_hr_short),
    AVG_HR(R.string.field_avg_hr, R.string.field_avg_hr_short),
    CADENCE(R.string.field_cadence, R.string.field_cadence_short),
    LAP_TIME(R.string.field_lap_time, R.string.field_lap_time_short),
    LAP_DIST(R.string.field_lap_dist, R.string.field_lap_dist_short),
    CLOCK(R.string.field_clock, R.string.field_clock_short),
    REMAINING(R.string.field_remaining, R.string.field_remaining_short, raceOnly = true),
    FINISH(R.string.field_finish, R.string.field_finish_short, raceOnly = true),
    NEEDED_PACE(R.string.field_needed_pace, R.string.field_needed_pace_short, raceOnly = true),
    RACE_DELTA(R.string.field_race_delta, R.string.field_race_delta_short, raceOnly = true),
    DISTANCE(R.string.field_distance, R.string.field_distance_short, raceOnly = true);

    companion object {
        const val SLOTS = 4
        val defaults = listOf(PACE, HR, LAP_PACE, AVG_PACE)
        val raceDefaults = listOf(PACE, REMAINING, FINISH, NEEDED_PACE)
        val runOptions = entries.filter { !it.raceOnly }
        fun key(slot: Int) = "field_$slot"
        fun raceKey(slot: Int) = "race_field_$slot"
    }
}

/** Run-screen preferences, read once when a workout starts. */
data class RunPrefs(val name: String?, val fields: List<DataField>, val raceFields: List<DataField>) {
    constructor(settings: Settings) : this(
        name = settings.runnerName.takeIf { settings.showName && it.isNotBlank() },
        fields = readSlots(settings, DataField::key, DataField.defaults, DataField.runOptions),
        raceFields = readSlots(settings, DataField::raceKey, DataField.raceDefaults, DataField.entries),
    )
}

private fun readSlots(settings: Settings, key: (Int) -> String, defaults: List<DataField>, options: List<DataField>) =
    List(DataField.SLOTS) { slot ->
        options.getOrElse(settings.int(key(slot), defaults[slot].ordinal)) { defaults[slot] }
    }

/** Design width: the Xiaomi Watch 2 is 466 px at density 2. */
private const val DESIGN_DP = 233f

/**
 * Text size that scales with the screen and ignores the system font size,
 * so the run pages always fill the round display without spilling over.
 */
@Composable
fun fit(size: Float): TextUnit = with(LocalDensity.current) { fitDp(size).toSp() }

@Composable
fun fitDp(size: Float): Dp = (size * LocalConfiguration.current.screenWidthDp / DESIGN_DP).dp

/** Bold single-line number with a tight line height. */
@Composable
fun BigText(text: String, size: TextUnit, color: Color = Color.White, modifier: Modifier = Modifier) {
    Text(text, modifier, color = color, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1, lineHeight = size * 1.1f)
}

private val CLOCK = SimpleDateFormat("HH:mm", Locale.ITALY)

fun clockNow(): String = CLOCK.format(Date())

/** Value and colour of [field] for the current snapshot. */
fun DataField.render(live: WorkoutSession.Live): Pair<String, Color> {
    val s = live.snapshot
    return when (this) {
        DataField.PACE -> Format.pace(s.pace) to statusColor(s.targetStatus)
        DataField.LAP_PACE -> Format.pace(Format.paceOf(s.lapDistanceM, s.lapMs)) to Color.White
        DataField.AVG_PACE -> Format.pace(s.avgPace) to Color.White
        DataField.HR -> (s.heartRate?.toString() ?: "--") to zoneColor(s.zone)
        DataField.LAP_HR -> (s.lapAvgHr?.toString() ?: "--") to Color.White
        DataField.AVG_HR -> (s.avgHr?.toString() ?: "--") to Color.White
        DataField.CADENCE -> (s.cadence?.toString() ?: "--") to Color.White
        DataField.LAP_TIME -> Format.duration(s.lapMs) to Color.White
        DataField.LAP_DIST -> Format.meters(s.lapDistanceM) to Color.White
        DataField.CLOCK -> clockNow() to Color.White
        DataField.REMAINING -> (s.race?.let { Format.km(it.remainingM) } ?: "--") to Color.White
        DataField.FINISH -> (s.race?.finishMs?.let(Format::duration) ?: "--:--") to
            if (s.race?.finished == true) Good else Color.White
        DataField.NEEDED_PACE -> Format.pace(s.race?.neededPace) to Color.White
        DataField.RACE_DELTA -> s.race?.aheadSec.let { a ->
            (a?.let(Format::ahead) ?: "--") to when {
                a == null -> Color.White
                a >= 0 -> Good
                else -> Warn
            }
        }
        DataField.DISTANCE -> Format.km(s.distanceM) to Accent
    }
}

/**
 * Two rows of two fields separated by hairlines, Garmin data-screen style.
 * Sizes are in design dp (see [fit]); values longer than "10:35" shrink to stay inside the cell.
 */
@Composable
fun FieldGrid(live: WorkoutSession.Live, fields: List<DataField>, valueSize: Float = 30f, cellWidth: Float = 104f) {
    val width = fitDp(cellWidth)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Hairline(width * 1.8f)
        fields.chunked(2).forEachIndexed { row, pair ->
            if (row > 0) Hairline(width * 2)
            Row(Modifier.height(fitDp(valueSize * 1.1f + 14f)), verticalAlignment = Alignment.CenterVertically) {
                FieldCell(live, pair[0], valueSize, width)
                Box(Modifier.width(1.dp).fillMaxHeight(0.8f).background(Hair))
                pair.getOrNull(1)?.let { FieldCell(live, it, valueSize, width) }
            }
        }
    }
}

private val Hair = Color(0x33FFFFFF)

@Composable
private fun Hairline(width: Dp) {
    Box(Modifier.width(width).height(1.dp).background(Hair))
}

@Composable
private fun FieldCell(live: WorkoutSession.Live, field: DataField, valueSize: Float, width: Dp) {
    val (value, color) = field.render(live)
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(field.short), color = Muted, fontSize = fit(10f), fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
        BigText(value, fit(if (value.length > 5) valueSize * 0.78f else valueSize), color)
    }
}

/** Five zone segments; the current one is full colour and taller. */
@Composable
fun ZoneBar(zone: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (z in 1..5) {
            val on = z == zone
            Box(
                Modifier
                    .width(20.dp)
                    .height(if (on) 8.dp else 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) zoneColor(z) else zoneColor(z).copy(alpha = 0.3f)),
            )
        }
    }
}

/** "Z3 Base" label under the zone bar. */
@Composable
fun zoneLabel(zone: Int): String =
    if (zone > 0) stringResource(R.string.fmt_zone, zone, stringResource(Zones.names[zone])) else stringResource(R.string.zone_below)
