package dev.watchstrava.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import dev.watchstrava.data.Settings

/** Standard scrolling screen: clock on top, scroll indicator, rotary-crown scrolling. */
@Composable
fun ScreenList(initialCenterItem: Int = 1, content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = initialCenterItem)
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = state,
            content = content,
        )
    }
}

fun ScalingLazyListScope.header(title: String, color: Color = Accent) = item {
    ListHeader { Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
}

fun ScalingLazyListScope.note(text: String) = item {
    Text(
        text, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** "label / − value +" row for numeric settings. */
@Composable
fun ValueRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Surface)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 12.sp, color = Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactButton(onClick = onMinus, colors = ButtonDefaults.secondaryButtonColors()) {
                Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                value, Modifier.weight(1f), textAlign = TextAlign.Center,
                fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
            CompactButton(onClick = onPlus) {
                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Tap to cycle through [options]. */
@Composable
fun ChoiceRow(label: String, options: List<String>, selected: MutableState<Int>) {
    Chip(
        onClick = { selected.value = (selected.value + 1) % options.size },
        label = { Text(label, fontSize = 12.sp, color = Muted) },
        secondaryLabel = {
            Text(options[selected.value.coerceIn(options.indices)], fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, secondary: String? = null, onChange: (Boolean) -> Unit) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onChange,
        label = { Text(label) },
        secondaryLabel = secondary?.let { { Text(it, fontSize = 11.sp, color = Muted) } },
        toggleControl = { Switch(checked = checked) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun StartChip(label: String = "AVVIA", onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun MenuChip(label: String, secondary: String? = null, color: Color = Surface, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.Bold) },
        secondaryLabel = secondary?.let { { Text(it, fontSize = 11.sp, maxLines = 1) } },
        colors = ChipDefaults.chipColors(backgroundColor = color, contentColor = Color.White, secondaryContentColor = Color(0xDDFFFFFF)),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** An int form value remembered across launches. */
@Composable
fun rememberPref(settings: Settings, key: String, default: Int): MutableState<Int> {
    val state = remember { mutableIntStateOf(settings.int(key, default)) }
    LaunchedEffect(state.intValue) { settings.setInt(key, state.intValue) }
    return state
}

fun MutableState<Int>.add(delta: Int, range: IntRange) {
    value = (value + delta).coerceIn(range)
}
