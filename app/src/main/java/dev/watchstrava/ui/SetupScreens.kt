package dev.watchstrava.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text
import dev.watchstrava.data.History
import dev.watchstrava.data.Settings
import dev.watchstrava.engine.Summary
import dev.watchstrava.model.Format
import dev.watchstrava.model.Plan
import dev.watchstrava.model.StepLength
import dev.watchstrava.model.Target
import dev.watchstrava.model.Zones
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(settings: Settings, onOpen: (String) -> Unit) = ScreenList(initialCenterItem = 0) {
    item { LogoHeader(settings.runnerName.takeIf { settings.showName && it.isNotBlank() }) }
    item { MenuChip("Corsa libera", "Passo, zone, km automatici", Accent) { onOpen("free") } }
    item { MenuChip("Ripetute", "Riscaldamento, N × veloce + recupero") { onOpen("intervals") } }
    item { MenuChip("Fartlek", "Cambi di ritmo a tempo o liberi") { onOpen("fartlek") } }
    item { MenuChip("Solo sensore cardio", "Battito a Strava, senza GPS") { onOpen("sensor") } }
    item { MenuChip("Storico", "Allenamenti passati") { onOpen("history") } }
    item { MenuChip("Personalizza", "Colore, nome, dati a schermo") { onOpen("customize") } }
    item { MenuChip("Impostazioni", "FC max, km auto, pausa auto…") { onOpen("settings") } }
}

// --- Shared target fields --------------------------------------------------

private val TARGET_TYPES = listOf("Nessuno", "Passo", "Zona cardio")

private class TargetState(settings: Settings, prefix: String, val maxHr: Int) {
    val type = settings.int("${prefix}_target", 0).let { mutableIntStateOf(it) }
    val pace = settings.int("${prefix}_pace", 300).let { mutableIntStateOf(it) }
    val tolerance = settings.int("${prefix}_tol", 10).let { mutableIntStateOf(it) }
    val zone = settings.int("${prefix}_zone", 2).let { mutableIntStateOf(it) }
    private val keys = listOf(type to "_target", pace to "_pace", tolerance to "_tol", zone to "_zone")
    private val save = { keys.forEach { (s, k) -> settings.setInt(prefix + k, s.intValue) } }

    fun target(): Target {
        save()
        return when (type.intValue) {
            1 -> Target.Pace(pace.intValue - tolerance.intValue, pace.intValue + tolerance.intValue)
            2 -> Target.Zone(zone.intValue)
            else -> Target.None
        }
    }
}

@Composable
private fun rememberTarget(settings: Settings, prefix: String) =
    remember { TargetState(settings, prefix, settings.maxHr) }

private fun ScalingLazyListScope.targetItems(t: TargetState) {
    item { ChoiceRow("Obiettivo", TARGET_TYPES, t.type) }
    when (t.type.intValue) {
        1 -> {
            item { ValueRow("Passo obiettivo /km", Format.pace(t.pace.intValue), { t.pace.add(-5, 150..720) }, { t.pace.add(5, 150..720) }) }
            item { ValueRow("Tolleranza", "± ${t.tolerance.intValue} s", { t.tolerance.add(-5, 5..60) }, { t.tolerance.add(5, 5..60) }) }
        }
        2 -> item {
            val z = t.zone.intValue
            ValueRow(
                "Zona · ${Zones.lower(z, t.maxHr)}–${Zones.upper(z, t.maxHr)} bpm",
                "Z$z ${Zones.names[z]}", { t.zone.add(-1, 1..5) }, { t.zone.add(1, 1..5) },
            )
        }
    }
}

// Warm-up / cool-down choices, in minutes (UNTIL_LAP = until GIRO).
private val WARMUPS = listOf(Plan.UNTIL_LAP, 0, 5, 10, 15, 20)
private val COOLDOWNS = listOf(0, 5, 10, 15, Plan.UNTIL_LAP)
private fun minutesLabel(m: Int) = when (m) {
    Plan.UNTIL_LAP -> "Fino a GIRO"
    0 -> "No"
    else -> "$m min"
}

// --- Free run --------------------------------------------------------------

@Composable
fun FreeRunSetup(settings: Settings, onStart: (Plan) -> Unit) {
    val target = rememberTarget(settings, "free")
    ScreenList {
        header("Corsa libera")
        targetItems(target)
        note(if (settings.autoLap) "Giro automatico ogni km" else "Giro automatico spento")
        item {
            StartChip {
                onStart(Plan.free(target.target(), autoLapMeters = if (settings.autoLap) 1000 else 0))
            }
        }
    }
}

// --- Intervals -------------------------------------------------------------

@Composable
fun IntervalsSetup(settings: Settings, onStart: (Plan) -> Unit) {
    val warmup = rememberPref(settings, "int_warmup", 0)
    val reps = rememberPref(settings, "int_reps", 6)
    val workType = rememberPref(settings, "int_work_type", 0) // 0 distance, 1 time
    val workM = rememberPref(settings, "int_work_m", 400)
    val workS = rememberPref(settings, "int_work_s", 60)
    val recType = rememberPref(settings, "int_rec_type", 0) // 0 time, 1 distance
    val recS = rememberPref(settings, "int_rec_s", 90)
    val recM = rememberPref(settings, "int_rec_m", 200)
    val cooldown = rememberPref(settings, "int_cooldown", 0)
    val target = rememberTarget(settings, "int")

    val work = if (workType.value == 0) StepLength.Distance(workM.value) else StepLength.Time(workS.value)
    val rec = if (recType.value == 0) StepLength.Time(recS.value) else StepLength.Distance(recM.value)

    ScreenList {
        header("Ripetute")
        item {
            Text(
                "${reps.value} × ${Format.length(work)} · rec ${Format.length(rec)}",
                fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
        }
        item { ChoiceRow("Riscaldamento", WARMUPS.map(::minutesLabel), warmup) }
        item { ValueRow("Ripetute", "${reps.value}", { reps.add(-1, 1..30) }, { reps.add(1, 1..30) }) }
        item { ChoiceRow("Ripetuta a", listOf("Distanza", "Tempo"), workType) }
        item {
            if (workType.value == 0) {
                ValueRow("Distanza veloce", Format.meters(workM.value.toDouble()), { workM.add(-100, 100..10_000) }, { workM.add(100, 100..10_000) })
            } else {
                ValueRow("Durata veloce", Format.seconds(workS.value), { workS.add(-15, 15..1_800) }, { workS.add(15, 15..1_800) })
            }
        }
        targetItems(target)
        item { ChoiceRow("Recupero a", listOf("Tempo", "Distanza"), recType) }
        item {
            if (recType.value == 0) {
                ValueRow("Durata recupero", Format.seconds(recS.value), { recS.add(-15, 15..600) }, { recS.add(15, 15..600) })
            } else {
                ValueRow("Distanza recupero", Format.meters(recM.value.toDouble()), { recM.add(-100, 100..2_000) }, { recM.add(100, 100..2_000) })
            }
        }
        item { ChoiceRow("Defaticamento", COOLDOWNS.map(::minutesLabel), cooldown) }
        note("GIRO salta allo step successivo")
        item {
            StartChip {
                onStart(
                    Plan.intervals(
                        WARMUPS[warmup.value], reps.value, work, target.target(), rec, COOLDOWNS[cooldown.value],
                    )
                )
            }
        }
    }
}

// --- Fartlek ---------------------------------------------------------------

@Composable
fun FartlekSetup(settings: Settings, onStart: (Plan) -> Unit) {
    val mode = rememberPref(settings, "fk_mode", 0) // 0 timed, 1 free
    val warmup = rememberPref(settings, "fk_warmup", 2) // index into WARMUPS → 5 min
    val cycles = rememberPref(settings, "fk_cycles", 8)
    val fast = rememberPref(settings, "fk_fast", 60)
    val easy = rememberPref(settings, "fk_easy", 120)
    val cooldown = rememberPref(settings, "fk_cooldown", 1)

    ScreenList {
        header("Fartlek")
        item { ChoiceRow("Modalità", listOf("A tempo", "Libero"), mode) }
        if (mode.value == 0) {
            item {
                Text("${cycles.value} × ${Format.seconds(fast.value)} / ${Format.seconds(easy.value)}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            item { ChoiceRow("Riscaldamento", WARMUPS.map(::minutesLabel), warmup) }
            item { ValueRow("Cicli", "${cycles.value}", { cycles.add(-1, 1..40) }, { cycles.add(1, 1..40) }) }
            item { ValueRow("Veloce", Format.seconds(fast.value), { fast.add(-10, 10..900) }, { fast.add(10, 10..900) }) }
            item { ValueRow("Lento", Format.seconds(easy.value), { easy.add(-10, 10..900) }, { easy.add(10, 10..900) }) }
            item { ChoiceRow("Defaticamento", COOLDOWNS.map(::minutesLabel), cooldown) }
            item {
                StartChip {
                    onStart(Plan.fartlek(WARMUPS[warmup.value], cycles.value, fast.value, easy.value, COOLDOWNS[cooldown.value]))
                }
            }
        } else {
            note("Corri libero e premi GIRO (o il tasto fisico) a ogni cambio di ritmo: ogni tratto diventa un giro con passo e battito.")
            item { StartChip { onStart(Plan.free(autoLapMeters = 0, name = "Fartlek libero")) } }
        }
    }
}

// --- Sensor only -----------------------------------------------------------

@Composable
fun SensorSetup(onStart: (Plan) -> Unit) = ScreenList {
    header("Solo sensore")
    note("L'orologio trasmette solo il battito. Su iPhone: Strava → Registra → Sensori → Aggiungi.")
    item { StartChip("AVVIA SENSORE") { onStart(Plan.free(autoLapMeters = 0, name = "Sensore cardio")) } }
}

// --- Settings --------------------------------------------------------------

@Composable
fun SettingsScreen(settings: Settings) {
    var maxHr by remember { mutableIntStateOf(settings.maxHr) }
    var autoLap by remember { mutableStateOf(settings.autoLap) }
    var autoPause by remember { mutableStateOf(settings.autoPause) }
    var broadcast by remember { mutableStateOf(settings.broadcastHr) }
    var screenOn by remember { mutableStateOf(settings.keepScreenOn) }
    var vibrate by remember { mutableStateOf(settings.vibrate) }

    ScreenList {
        header("Impostazioni")
        item {
            ValueRow("FC massima", "$maxHr bpm",
                { maxHr = (maxHr - 1).coerceAtLeast(120); settings.maxHr = maxHr },
                { maxHr = (maxHr + 1).coerceAtMost(220); settings.maxHr = maxHr })
        }
        note((1..5).joinToString("\n") { "Z$it ${Zones.names[it]}: ${Zones.lower(it, maxHr)}–${Zones.upper(it, maxHr)}" })
        item { SwitchRow("Giro auto 1 km", autoLap) { autoLap = it; settings.autoLap = it } }
        item { SwitchRow("Pausa automatica", autoPause, "Quando ti fermi") { autoPause = it; settings.autoPause = it } }
        item { SwitchRow("Battito a iPhone", broadcast, "Sensore Bluetooth per Strava") { broadcast = it; settings.broadcastHr = it } }
        item { SwitchRow("Schermo acceso", screenOn, "Durante l'allenamento") { screenOn = it; settings.keepScreenOn = it } }
        item { SwitchRow("Vibrazione", vibrate, "Giri, step e avvisi") { vibrate = it; settings.vibrate = it } }
    }
}

// --- Customize -------------------------------------------------------------

private val SLOT_NAMES = listOf("Campo in alto a sinistra", "Campo in alto a destra", "Campo in basso a sinistra", "Campo in basso a destra")

@Composable
fun CustomizeScreen(settings: Settings) {
    var showName by remember { mutableStateOf(settings.showName) }
    LaunchedEffect(accentIndex.intValue) { settings.accent = accentIndex.intValue }
    val slots = List(DataField.SLOTS) { rememberPref(settings, DataField.key(it), DataField.defaults[it].ordinal) }
    val fieldNames = DataField.entries.map { it.label }

    ScreenList {
        header("Personalizza")
        item { LogoMark(40.dp) }
        item { ChoiceRow("Colore", Accents.map { it.first }, accentIndex) }
        item { SwitchRow("Mostra nome", showName, settings.runnerName) { showName = it; settings.showName = it } }
        note("Schermata principale della corsa (anche in ripetute e fartlek)")
        slots.forEachIndexed { i, slot -> item { ChoiceRow(SLOT_NAMES[i], fieldNames, slot) } }
        item {
            CompactChip(
                onClick = { slots.forEachIndexed { i, s -> s.value = DataField.defaults[i].ordinal } },
                label = { Text("Ripristina campi") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

// --- History ---------------------------------------------------------------

private val DATE = SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALY)

fun formatDate(ms: Long): String = DATE.format(Date(ms))

@Composable
fun HistoryScreen(history: History, onOpen: (Long) -> Unit) {
    val items = remember { history.all() }
    ScreenList {
        header("Storico")
        if (items.isEmpty()) note("Nessun allenamento salvato")
        items.forEach { s: Summary ->
            item {
                MenuChip(
                    "${Format.km(s.distanceM)} km · ${Format.duration(s.movingMs)}",
                    "${s.name} · ${formatDate(s.startedAt)}",
                ) { onOpen(s.startedAt) }
            }
        }
    }
}
