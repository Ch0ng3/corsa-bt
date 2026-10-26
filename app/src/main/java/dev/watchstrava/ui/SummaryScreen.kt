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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.Text
import dev.watchstrava.engine.Summary
import dev.watchstrava.model.Format

@Composable
fun SummaryScreen(summary: Summary, onDone: () -> Unit) = ScreenList {
    header("Fatto!", Good)
    summaryItems(summary)
    note("Ricordati di terminare e salvare l'attività su Strava (iPhone)")
    item { StartChip("OK", onDone) }
}

@Composable
fun HistoryDetail(summary: Summary, onDelete: () -> Unit) = ScreenList {
    header(summary.name)
    note(formatDate(summary.startedAt))
    summaryItems(summary)
    item { MenuChip("Elimina", color = Color(0xFF5A1A1A), onClick = onDelete) }
}

fun ScalingLazyListScope.summaryItems(s: Summary) {
    item {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Format.km(s.distanceM), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("km", color = Muted, fontSize = 12.sp)
        }
    }
    item { StatRow("Tempo", Format.duration(s.movingMs)) }
    item { StatRow("Passo medio", "${Format.pace(s.pace)} /km") }
    s.avgHr?.let { item { StatRow("FC media / max", "$it / ${s.maxHr ?: "--"}") } }
    s.avgCadence?.let { item { StatRow("Cadenza media", "$it spm") } }

    val totalZone = s.zoneMs.drop(1).sum()
    if (totalZone > 0) {
        item { Text("Tempo in zona", color = Muted, fontSize = 12.sp) }
        for (z in 5 downTo 1) item { ZoneBar(z, s.zoneMs[z], totalZone) }
    }
    if (s.laps.size > 1) {
        item { Text("Giri", color = Muted, fontSize = 12.sp) }
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
                    Text(
                        "${Format.meters(lap.distanceM)} · ${Format.duration(lap.movingMs)} · ${Format.pace(lap.pace)}" +
                            (lap.avgHr?.let { " · ♥$it" } ?: ""),
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
