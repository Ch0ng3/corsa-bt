package dev.watchstrava.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import dev.watchstrava.data.Settings
import dev.watchstrava.model.Format
import dev.watchstrava.model.Zones
import dev.watchstrava.service.WorkoutSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A value the user can place in one of the four slots of the main run page. */
enum class DataField(val label: String, val short: String) {
    PACE("Passo attuale", "PASSO"),
    LAP_PACE("Passo giro (media)", "MEDIA GIRO"),
    AVG_PACE("Passo medio totale", "MEDIA TOT"),
    HR("Battito", "BATTITO"),
    LAP_HR("Battito medio giro", "♥ GIRO"),
    AVG_HR("Battito medio totale", "♥ MEDIO"),
    CADENCE("Cadenza", "CADENZA"),
    LAP_TIME("Tempo giro", "T. GIRO"),
    LAP_DIST("Distanza giro", "DIST. GIRO"),
    CLOCK("Ora", "ORA");

    companion object {
        const val SLOTS = 4
        val defaults = listOf(PACE, HR, LAP_PACE, AVG_PACE)
        fun key(slot: Int) = "field_$slot"
    }
}

/** Run-screen preferences, read once when a workout starts. */
data class RunPrefs(val name: String?, val fields: List<DataField>) {
    constructor(settings: Settings) : this(
        name = settings.runnerName.takeIf { settings.showName && it.isNotBlank() },
        fields = List(DataField.SLOTS) { slot ->
            DataField.entries.getOrElse(settings.int(DataField.key(slot), DataField.defaults[slot].ordinal)) {
                DataField.defaults[slot]
            }
        },
    )
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
    }
}

/** Two rows of two fields separated by hairlines, Garmin data-screen style. */
@Composable
fun FieldGrid(live: WorkoutSession.Live, fields: List<DataField>, valueSize: TextUnit = 20.sp, cellWidth: Dp = 84.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Hairline(cellWidth * 1.8f)
        fields.chunked(2).forEachIndexed { row, pair ->
            if (row > 0) Hairline(cellWidth * 2)
            Row(Modifier.height(rowHeight(valueSize)), verticalAlignment = Alignment.CenterVertically) {
                FieldCell(live, pair[0], valueSize, cellWidth)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Hair))
                pair.getOrNull(1)?.let { FieldCell(live, it, valueSize, cellWidth) }
            }
        }
    }
}

private val Hair = Color(0x33FFFFFF)

private fun rowHeight(valueSize: TextUnit) = (valueSize.value * 1.2f + 14f).dp

@Composable
private fun Hairline(width: Dp) {
    Box(Modifier.width(width).height(1.dp).background(Hair))
}

@Composable
private fun FieldCell(live: WorkoutSession.Live, field: DataField, valueSize: TextUnit, width: Dp) {
    val (value, color) = field.render(live)
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(field.short, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1)
        Text(value, color = color, fontSize = valueSize, fontWeight = FontWeight.Bold, maxLines = 1)
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
fun zoneLabel(zone: Int): String = if (zone > 0) "Z$zone ${Zones.names[zone]}" else "sotto Z1"
