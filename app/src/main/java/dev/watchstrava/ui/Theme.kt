package dev.watchstrava.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import dev.watchstrava.engine.TargetStatus
import dev.watchstrava.model.StepKind
import dev.watchstrava.service.WorkoutSession.Tone

/** Accent colours the user can pick in Impostazioni → Personalizza. */
val Accents = listOf(
    "Arancio" to Color(0xFFFC4C02),
    "Azzurro" to Color(0xFF2E9BFF),
    "Acqua" to Color(0xFF00C7BE),
    "Lime" to Color(0xFFB4E61E),
    "Viola" to Color(0xFFAF52DE),
    "Rosa" to Color(0xFFFF2D78),
    "Oro" to Color(0xFFFFC300),
)

/** Selected accent; a snapshot state so changing it recolours the whole UI. */
val accentIndex = mutableIntStateOf(0)
val Accent: Color get() = Accents[accentIndex.intValue.coerceIn(Accents.indices)].second

val Surface = Color(0xFF1E1E22)
val Muted = Color(0xFF9A9AA2)
val Good = Color(0xFF4CD964)
val Warn = Color(0xFFFFC107)
val Bad = Color(0xFFFF453A)
val Easy = Color(0xFF3FA9F5)

/** Garmin-style zone colours: Z1 grey, Z2 blue, Z3 green, Z4 orange, Z5 red. */
fun zoneColor(zone: Int): Color = when (zone) {
    1 -> Color(0xFFB0B0B8)
    2 -> Color(0xFF2E9BFF)
    3 -> Color(0xFF34C759)
    4 -> Color(0xFFFF9500)
    5 -> Color(0xFFFF3B30)
    else -> Muted
}

fun stepColor(kind: StepKind): Color = when (kind) {
    StepKind.WORK -> Accent
    StepKind.EASY, StepKind.RECOVERY -> Easy
    StepKind.WARMUP, StepKind.COOLDOWN -> Color(0xFFB98CFF)
    StepKind.RUN -> Accent
}

fun statusColor(status: TargetStatus): Color = when (status) {
    TargetStatus.OK -> Good
    TargetStatus.TOO_FAST, TargetStatus.HR_HIGH -> Bad
    TargetStatus.TOO_SLOW, TargetStatus.HR_LOW -> Warn
    TargetStatus.NONE -> Color.White
}

fun toneColor(tone: Tone): Color = when (tone) {
    Tone.LAP -> Accent
    Tone.STEP_WORK -> Accent
    Tone.STEP_EASY -> Easy
    Tone.ALERT -> Bad
    Tone.DONE -> Good
    Tone.INFO -> Warn
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = Accent,
            primaryVariant = Accent.copy(alpha = 0.7f),
            secondary = Accent,
            surface = Surface,
            onPrimary = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}
