package dev.watchstrava.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.Text
import dev.watchstrava.R
import dev.watchstrava.engine.Summary
import dev.watchstrava.model.Format
import dev.watchstrava.model.Sport

@Composable
fun SummaryScreen(summary: Summary, onDone: () -> Unit) = ScreenList {
    header(R.string.summary_done, Good)
    summaryItems(summary)
    note(R.string.summary_strava_note)
    item { StartChip(stringResource(R.string.ok), onDone) }
}

@Composable
fun HistoryDetail(summary: Summary, onDelete: () -> Unit) = ScreenList {
    header(summary.name)
    note(formatDate(summary.startedAt))
    summaryItems(summary)
    item { MenuChip(stringResource(R.string.delete), color = Color(0xFF5A1A1A), onClick = onDelete) }
}

fun ScalingLazyListScope.summaryItems(s: Summary) {
    val ski = s.sport == Sport.SKI
    val climb = s.sport.isClimb
    item {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                ski || climb -> {
                    Text("${s.laps.size}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(if (ski) R.string.runs else R.string.routes), color = Muted, fontSize = 12.sp)
                }
                else -> {
                    Text(Format.km(s.distanceM), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("km", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
    item { StatRow(stringResource(R.string.time), Format.duration(s.movingMs)) }
    when {
        ski -> {
            item { StatRow(stringResource(R.string.descent), Format.height(s.descentM)) }
            item { StatRow(stringResource(R.string.max_speed), "${Format.kmh(s.maxSpeedMps)} km/h") }
            item { StatRow(stringResource(R.string.runs_distance), Format.meters(s.lapsDistanceM)) }
        }
        climb -> {
            item { StatRow(stringResource(R.string.time_on_routes), Format.duration(s.lapsMs)) }
            item { StatRow(stringResource(R.string.time_resting), Format.duration(s.movingMs - s.lapsMs)) }
            item { StatRow(stringResource(R.string.longest_climb), Format.duration(s.longestLapMs)) }
            if (s.sport == Sport.CLIMB_OUTDOOR) item { StatRow(stringResource(R.string.ascent), Format.height(s.ascentM)) }
        }
        else -> {
            item { StatRow(stringResource(R.string.avg_pace), "${Format.pace(s.pace)} /km") }
            // Runs only show the climb when a barometer measured some.
            if (s.sport == Sport.HIKE || s.ascentM + s.descentM > 0) {
                item { StatRow(stringResource(R.string.ascent), Format.height(s.ascentM)) }
                item { StatRow(stringResource(R.string.descent), Format.height(s.descentM)) }
            }
            if (s.sport == Sport.HIKE) item { StatRow(stringResource(R.string.max_altitude), Format.height(s.maxAltitudeM)) }
        }
    }
    s.avgHr?.let { item { StatRow(stringResource(R.string.avg_max_hr), "$it / ${s.maxHr ?: "--"}") } }
    if (s.sport == Sport.RUN) s.avgCadence?.let { item { StatRow(stringResource(R.string.avg_cadence), stringResource(R.string.fmt_spm, it)) } }

    val totalZone = s.zoneMs.drop(1).sum()
    if (totalZone > 0) {
        item { Text(stringResource(R.string.time_in_zone), color = Muted, fontSize = 12.sp) }
        for (z in 5 downTo 1) item { ZoneBar(z, s.zoneMs[z], totalZone) }
    }
    // Ski runs and climbing routes are worth listing even when there is only one.
    if (s.laps.size > 1 || (ski || climb) && s.laps.isNotEmpty()) {
        item { Text(stringResource(if (ski) R.string.runs else if (climb) R.string.routes else R.string.laps), color = Muted, fontSize = 12.sp) }
        s.laps.forEach { lap ->
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("${lap.number}. ${lap.label}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    val hr = lap.avgHr?.let { " · ♥$it" } ?: ""
                    Text(
                        when {
                            ski -> "↓ ${Format.height(lap.descentM)} · ${Format.kmh(lap.maxSpeedMps)} km/h · ${Format.duration(lap.movingMs)}$hr"
                            climb -> Format.duration(lap.movingMs) + hr + if (lap.ascentM >= 1) " · ↑ ${Format.height(lap.ascentM)}" else ""
                            else -> "${Format.meters(lap.distanceM)} · ${Format.duration(lap.movingMs)} · ${Format.pace(lap.pace)}$hr"
                        },
                        fontSize = 12.sp, color = Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ZoneBar(zone: Int, ms: Long, total: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Z$zone", fontSize = 12.sp, color = zoneColor(zone), modifier = Modifier.width(28.dp))
        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp)) {
            Box(
                Modifier
                    .fillMaxWidth((ms.toFloat() / total).coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(zoneColor(zone)),
            )
        }
        Text(Format.duration(ms), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}
