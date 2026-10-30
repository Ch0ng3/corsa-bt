package dev.watchstrava.ui

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text
import dev.watchstrava.R
import dev.watchstrava.data.History
import dev.watchstrava.data.Routes
import dev.watchstrava.data.Settings
import dev.watchstrava.engine.Summary
import dev.watchstrava.model.Format
import dev.watchstrava.model.Plan
import dev.watchstrava.model.Route
import dev.watchstrava.model.Sport
import dev.watchstrava.model.StepKind
import dev.watchstrava.model.StepLength
import dev.watchstrava.model.Target
import dev.watchstrava.model.Zones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(settings: Settings, onOpen: (String) -> Unit) = ScreenList(initialCenterItem = 0) {
    item { LogoHeader(settings.runnerName.takeIf { settings.showName && it.isNotBlank() }) }
    item { MenuChip(stringResource(R.string.plan_free), stringResource(R.string.home_free_desc), Accent) { onOpen("free") } }
    item { MenuChip(stringResource(R.string.home_race), stringResource(R.string.home_race_desc)) { onOpen("race") } }
    item { MenuChip(stringResource(R.string.home_ski), stringResource(R.string.home_ski_desc)) { onOpen("ski") } }
    item { MenuChip(stringResource(R.string.home_climb), stringResource(R.string.home_climb_desc)) { onOpen("climb") } }
    item { MenuChip(stringResource(R.string.home_hike), stringResource(R.string.home_hike_desc)) { onOpen("hike") } }
    item { MenuChip(stringResource(R.string.plan_intervals), stringResource(R.string.home_intervals_desc)) { onOpen("intervals") } }
    item { MenuChip(stringResource(R.string.plan_fartlek), stringResource(R.string.home_fartlek_desc)) { onOpen("fartlek") } }
    item { MenuChip(stringResource(R.string.home_sensor), stringResource(R.string.home_sensor_desc)) { onOpen("sensor") } }
    item { MenuChip(stringResource(R.string.home_history), stringResource(R.string.home_history_desc)) { onOpen("history") } }
    item { MenuChip(stringResource(R.string.home_customize), stringResource(R.string.home_customize_desc)) { onOpen("customize") } }
    item { MenuChip(stringResource(R.string.home_settings), stringResource(R.string.home_settings_desc)) { onOpen("settings") } }
}

// --- Shared target fields --------------------------------------------------

private val TARGET_TYPES = listOf(R.string.target_none, R.string.target_pace, R.string.target_zone)

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
    item { ChoiceRow(stringResource(R.string.target), TARGET_TYPES.map { stringResource(it) }, t.type) }
    when (t.type.intValue) {
        1 -> {
            item { ValueRow(stringResource(R.string.target_pace_label), Format.pace(t.pace.intValue), { t.pace.add(-5, 150..720) }, { t.pace.add(5, 150..720) }) }
            item { ValueRow(stringResource(R.string.tolerance), stringResource(R.string.fmt_tolerance, t.tolerance.intValue), { t.tolerance.add(-5, 5..60) }, { t.tolerance.add(5, 5..60) }) }
        }
        2 -> item {
            val z = t.zone.intValue
            ValueRow(
                stringResource(R.string.zone_range, Zones.lower(z, t.maxHr), Zones.upper(z, t.maxHr)),
                zoneLabel(z), { t.zone.add(-1, 1..5) }, { t.zone.add(1, 1..5) },
            )
        }
    }
}

// Warm-up / cool-down choices, in minutes (UNTIL_LAP = until GIRO).
private val WARMUPS = listOf(Plan.UNTIL_LAP, 0, 5, 10, 15, 20)
private val COOLDOWNS = listOf(0, 5, 10, 15, Plan.UNTIL_LAP)

@Composable
private fun minutesLabels(choices: List<Int>) = choices.map { m ->
    when (m) {
        Plan.UNTIL_LAP -> stringResource(R.string.until_lap)
        0 -> stringResource(R.string.no)
        else -> stringResource(R.string.fmt_minutes, m)
    }
}

// --- GPX route choice ------------------------------------------------------

private class RouteChoice(val routes: List<Route>, val index: MutableState<Int>) {
    val selected: Route? get() = routes.getOrNull(index.value - 1)
}

/** Re-scans the routes folder each time the screen opens; null while loading. */
@Composable
private fun rememberRoute(settings: Settings, key: String): RouteChoice? {
    val context = LocalContext.current
    val routes = produceState<List<Route>?>(null) { value = withContext(Dispatchers.IO) { Routes.load(context) } }.value
        ?: return null
    val choice = remember(routes) {
        RouteChoice(routes, mutableIntStateOf(routes.indexOfFirst { it.file == settings.string(key, "") } + 1))
    }
    LaunchedEffect(choice, choice.index.value) { settings.setString(key, choice.selected?.file.orEmpty()) }
    return choice
}

private fun ScalingLazyListScope.routeItems(r: RouteChoice?, showLength: Boolean = false) {
    r ?: return
    if (r.routes.isEmpty()) return note(R.string.route_add_note)
    item { ChoiceRow(stringResource(R.string.route), listOf(stringResource(R.string.route_none)) + r.routes.map { it.name }, r.index) }
    val selected = r.selected
    if (showLength && selected != null) item {
        Text(stringResource(R.string.route_length, Format.meters(selected.lengthM)), color = Muted, fontSize = 12.sp)
    }
}

// --- Free run --------------------------------------------------------------

@Composable
fun FreeRunSetup(settings: Settings, onStart: (Plan, Route?) -> Unit) {
    val target = rememberTarget(settings, "free")
    val route = rememberRoute(settings, "free_route")
    val name = stringResource(R.string.plan_free)
    ScreenList {
        header(R.string.plan_free)
        targetItems(target)
        routeItems(route)
        if (target.type.intValue != 0) note(R.string.free_note_target)
        note(if (settings.autoLap) R.string.free_autolap_on else R.string.free_autolap_off)
        item {
            StartChip {
                onStart(Plan.free(target.target(), autoLapMeters = if (settings.autoLap) 1000 else 0, name = name), route?.selected)
            }
        }
    }
}

// --- Race ------------------------------------------------------------------

private class RaceDistance(@StringRes val label: Int, @StringRes val name: Int, val meters: Int, val goalSec: Int)

private val RACES = listOf(
    RaceDistance(R.string.race_5k, R.string.race_name_5k, 5_000, 25 * 60),
    RaceDistance(R.string.race_10k, R.string.race_name_10k, 10_000, 50 * 60),
    RaceDistance(R.string.race_half, R.string.race_half, 21_098, 105 * 60),
    RaceDistance(R.string.race_marathon, R.string.race_marathon, 42_195, 4 * 3600),
)
private const val CUSTOM_RACE = 4 // index after RACES: "Altra distanza"

@Composable
fun RaceSetup(settings: Settings, onStart: (Plan, Route?) -> Unit) {
    val route = rememberRoute(settings, "race_route")
    val distance = rememberPref(settings, "race_dist", 2)
    val customM = rememberPref(settings, "race_custom_m", 15_000)
    val noGoal = rememberPref(settings, "race_no_goal", 0) // 0 finish time, 1 none
    // One goal per distance, so switching 10 km ↔ mezza keeps each time.
    val goals = List(RACES.size + 1) { i -> rememberPref(settings, "race_goal_$i", RACES.getOrNull(i)?.goalSec ?: 75 * 60) }

    val custom = distance.value == CUSTOM_RACE
    val meters = if (custom) customM.value else RACES[distance.value].meters
    val name = if (custom) stringResource(R.string.race_name_custom, Format.meters(meters.toDouble())) else stringResource(RACES[distance.value].name)
    val goal = goals[distance.value]
    val km = meters / 1000.0
    val pace = (goal.value / km).roundToInt()
    val hasGoal = noGoal.value == 0

    ScreenList {
        header(R.string.home_race)
        item {
            Text(
                Format.meters(meters.toDouble()) + if (hasGoal) " · ${Format.duration(goal.value * 1000L)}" else "",
                fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
        }
        item { ChoiceRow(stringResource(R.string.distance), RACES.map { stringResource(it.label) } + stringResource(R.string.race_other), distance) }
        if (custom) item {
            ValueRow(stringResource(R.string.distance), Format.meters(meters.toDouble()), { customM.add(-500, 1_000..100_000) }, { customM.add(500, 1_000..100_000) })
        }
        item { ChoiceRow(stringResource(R.string.target), listOf(stringResource(R.string.race_goal_finish), stringResource(R.string.target_none)), noGoal) }
        if (hasGoal) {
            item { ValueRow(stringResource(R.string.goal_time), Format.duration(goal.value * 1000L), { goal.add(-60, 60..36_000) }, { goal.add(60, 60..36_000) }) }
            item {
                val setPace = { p: Int -> goal.value = (p * km).roundToInt().coerceIn(60, 36_000) }
                ValueRow(stringResource(R.string.avg_pace), "${Format.pace(pace)} /km", { setPace(pace - 1) }, { setPace(pace + 1) })
            }
        }
        routeItems(route, showLength = true)
        note(R.string.race_note)
        item { StartChip { onStart(Plan.race(name, meters, if (hasGoal) goal.value else 0), route?.selected) } }
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
    val name = stringResource(R.string.plan_intervals)
    val untilLap = stringResource(R.string.step_until_lap)

    val work = if (workType.value == 0) StepLength.Distance(workM.value) else StepLength.Time(workS.value)
    val rec = if (recType.value == 0) StepLength.Time(recS.value) else StepLength.Distance(recM.value)

    ScreenList {
        header(R.string.plan_intervals)
        item {
            Text(
                stringResource(R.string.fmt_intervals_summary, reps.value, Format.length(work, untilLap), Format.length(rec, untilLap)),
                fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
        }
        item { ChoiceRow(stringResource(StepKind.WARMUP.label), minutesLabels(WARMUPS), warmup) }
        item { ValueRow(stringResource(R.string.reps), "${reps.value}", { reps.add(-1, 1..30) }, { reps.add(1, 1..30) }) }
        item { ChoiceRow(stringResource(R.string.rep_by), listOf(stringResource(R.string.distance), stringResource(R.string.time)), workType) }
        item {
            if (workType.value == 0) {
                ValueRow(stringResource(R.string.fast_distance), Format.meters(workM.value.toDouble()), { workM.add(-100, 100..10_000) }, { workM.add(100, 100..10_000) })
            } else {
                ValueRow(stringResource(R.string.fast_duration), Format.seconds(workS.value), { workS.add(-15, 15..1_800) }, { workS.add(15, 15..1_800) })
            }
        }
        targetItems(target)
        item { ChoiceRow(stringResource(R.string.recovery_by), listOf(stringResource(R.string.time), stringResource(R.string.distance)), recType) }
        item {
            if (recType.value == 0) {
                ValueRow(stringResource(R.string.recovery_duration), Format.seconds(recS.value), { recS.add(-15, 15..600) }, { recS.add(15, 15..600) })
            } else {
                ValueRow(stringResource(R.string.recovery_distance), Format.meters(recM.value.toDouble()), { recM.add(-100, 100..2_000) }, { recM.add(100, 100..2_000) })
            }
        }
        item { ChoiceRow(stringResource(StepKind.COOLDOWN.label), minutesLabels(COOLDOWNS), cooldown) }
        note(R.string.intervals_note)
        item {
            StartChip {
                onStart(
                    Plan.intervals(
                        WARMUPS[warmup.value], reps.value, work, target.target(), rec, COOLDOWNS[cooldown.value], name,
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
    val name = stringResource(R.string.plan_fartlek)
    val freeName = stringResource(R.string.plan_fartlek_free)

    ScreenList {
        header(R.string.plan_fartlek)
        item { ChoiceRow(stringResource(R.string.mode), listOf(stringResource(R.string.mode_timed), stringResource(R.string.mode_free)), mode) }
        if (mode.value == 0) {
            item {
                Text("${cycles.value} × ${Format.seconds(fast.value)} / ${Format.seconds(easy.value)}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            item { ChoiceRow(stringResource(StepKind.WARMUP.label), minutesLabels(WARMUPS), warmup) }
            item { ValueRow(stringResource(R.string.cycles), "${cycles.value}", { cycles.add(-1, 1..40) }, { cycles.add(1, 1..40) }) }
            item { ValueRow(stringResource(StepKind.WORK.label), Format.seconds(fast.value), { fast.add(-10, 10..900) }, { fast.add(10, 10..900) }) }
            item { ValueRow(stringResource(StepKind.EASY.label), Format.seconds(easy.value), { easy.add(-10, 10..900) }, { easy.add(10, 10..900) }) }
            item { ChoiceRow(stringResource(StepKind.COOLDOWN.label), minutesLabels(COOLDOWNS), cooldown) }
            item {
                StartChip {
                    onStart(Plan.fartlek(WARMUPS[warmup.value], cycles.value, fast.value, easy.value, COOLDOWNS[cooldown.value], name))
                }
            }
        } else {
            note(R.string.fartlek_free_note)
            item { StartChip { onStart(Plan.free(autoLapMeters = 0, name = freeName)) } }
        }
    }
}

// --- Ski, climbing, hiking ---------------------------------------------------

@Composable
private fun hasBarometer(): Boolean {
    val context = LocalContext.current
    return remember { context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null }
}

@Composable
fun SkiSetup(onStart: (Plan) -> Unit) {
    val name = stringResource(R.string.home_ski)
    val barometer = hasBarometer()
    ScreenList {
        header(R.string.home_ski)
        note(R.string.ski_note)
        if (!barometer) note(R.string.no_barometer)
        item { StartChip { onStart(Plan.ski(name)) } }
    }
}

@Composable
fun ClimbSetup(settings: Settings, onStart: (Plan) -> Unit) {
    val where = rememberPref(settings, "climb_where", 0) // 0 indoor, 1 outdoor
    val outdoor = where.value == 1
    val name = stringResource(if (outdoor) R.string.plan_climb_outdoor else R.string.plan_climb_indoor)
    ScreenList {
        header(R.string.home_climb)
        item { ChoiceRow(stringResource(R.string.climb_where), listOf(stringResource(R.string.climb_indoor), stringResource(R.string.climb_outdoor)), where) }
        note(R.string.climb_note)
        note(if (outdoor) R.string.climb_outdoor_note else R.string.climb_indoor_note)
        item { StartChip { onStart(Plan.climb(outdoor, name)) } }
    }
}

@Composable
fun HikeSetup(settings: Settings, onStart: (Plan, Route?) -> Unit) {
    val route = rememberRoute(settings, "hike_route")
    val name = stringResource(R.string.home_hike)
    val barometer = hasBarometer()
    ScreenList {
        header(R.string.home_hike)
        routeItems(route, showLength = true)
        note(if (barometer) R.string.hike_note else R.string.no_barometer)
        note(if (settings.autoLap) R.string.free_autolap_on else R.string.free_autolap_off)
        item { StartChip { onStart(Plan.hike(if (settings.autoLap) 1000 else 0, name), route?.selected) } }
    }
}

// --- Sensor only -----------------------------------------------------------

@Composable
fun SensorSetup(onStart: (Plan) -> Unit) {
    val name = stringResource(R.string.plan_sensor)
    ScreenList {
        header(R.string.sensor_title)
        note(R.string.sensor_note)
        item { StartChip(stringResource(R.string.sensor_start)) { onStart(Plan.free(autoLapMeters = 0, name = name)) } }
    }
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
    val zoneNames = Zones.names.map { stringResource(it) }

    ScreenList {
        header(R.string.home_settings)
        item {
            ValueRow(stringResource(R.string.max_hr), stringResource(R.string.fmt_bpm, maxHr),
                { maxHr = (maxHr - 1).coerceAtLeast(120); settings.maxHr = maxHr },
                { maxHr = (maxHr + 1).coerceAtMost(220); settings.maxHr = maxHr })
        }
        note((1..5).joinToString("\n") { "Z$it ${zoneNames[it]}: ${Zones.lower(it, maxHr)}–${Zones.upper(it, maxHr)}" })
        item { SwitchRow(stringResource(R.string.auto_lap), autoLap) { autoLap = it; settings.autoLap = it } }
        item { SwitchRow(stringResource(R.string.auto_pause), autoPause, stringResource(R.string.auto_pause_desc)) { autoPause = it; settings.autoPause = it } }
        item { SwitchRow(stringResource(R.string.broadcast_hr), broadcast, stringResource(R.string.broadcast_hr_desc)) { broadcast = it; settings.broadcastHr = it } }
        item { SwitchRow(stringResource(R.string.screen_on), screenOn, stringResource(R.string.screen_on_desc)) { screenOn = it; settings.keepScreenOn = it } }
        item { SwitchRow(stringResource(R.string.vibration), vibrate, stringResource(R.string.vibration_desc)) { vibrate = it; settings.vibrate = it } }
    }
}

// --- Customize -------------------------------------------------------------

private val SLOT_NAMES = listOf(R.string.slot_top_left, R.string.slot_top_right, R.string.slot_bottom_left, R.string.slot_bottom_right)

@Composable
fun CustomizeScreen(settings: Settings) {
    var showName by remember { mutableStateOf(settings.showName) }
    LaunchedEffect(accentIndex.intValue) { settings.accent = accentIndex.intValue }
    val slots = List(DataField.SLOTS) { rememberPref(settings, DataField.key(it), DataField.defaults[it].ordinal) }
    val raceSlots = List(DataField.SLOTS) { rememberPref(settings, DataField.raceKey(it), DataField.raceDefaults[it].ordinal) }
    val fieldNames = DataField.runOptions.map { stringResource(it.label) }
    val raceFieldNames = DataField.entries.map { stringResource(it.label) }

    ScreenList {
        header(R.string.home_customize)
        item { LogoMark(40.dp) }
        item { ChoiceRow(stringResource(R.string.color), Accents.map { stringResource(it.first) }, accentIndex) }
        item { SwitchRow(stringResource(R.string.show_name), showName, settings.runnerName) { showName = it; settings.showName = it } }
        note(R.string.customize_note_run)
        slots.forEachIndexed { i, slot -> item { ChoiceRow(stringResource(SLOT_NAMES[i]), fieldNames, slot) } }
        note(R.string.customize_note_race)
        raceSlots.forEachIndexed { i, slot -> item { ChoiceRow(stringResource(SLOT_NAMES[i]), raceFieldNames, slot) } }
        item {
            CompactChip(
                onClick = {
                    slots.forEachIndexed { i, s -> s.value = DataField.defaults[i].ordinal }
                    raceSlots.forEachIndexed { i, s -> s.value = DataField.raceDefaults[i].ordinal }
                },
                label = { Text(stringResource(R.string.reset_fields)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

// --- History ---------------------------------------------------------------

/** Day and time in the current language, e.g. "gio 30 ott, 18:05" / "Thu 30 Oct, 18:05". */
fun formatDate(ms: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun HistoryScreen(history: History, onOpen: (Long) -> Unit) {
    val items = remember { history.all() }
    ScreenList {
        header(R.string.home_history)
        if (items.isEmpty()) note(R.string.history_empty)
        items.forEach { s: Summary ->
            item {
                val headline = when {
                    s.sport == Sport.SKI -> stringResource(R.string.fmt_runs, s.laps.size)
                    s.sport.isClimb -> stringResource(R.string.fmt_routes, s.laps.size)
                    else -> "${Format.km(s.distanceM)} km"
                }
                MenuChip(
                    "$headline · ${Format.duration(s.movingMs)}",
                    "${s.name} · ${formatDate(s.startedAt)}",
                ) { onOpen(s.startedAt) }
            }
        }
    }
}
