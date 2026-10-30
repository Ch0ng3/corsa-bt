package dev.watchstrava.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import dev.watchstrava.R
import dev.watchstrava.engine.Snapshot
import dev.watchstrava.model.Format
import dev.watchstrava.model.Sport
import dev.watchstrava.model.StepLength
import dev.watchstrava.model.TrackFit
import dev.watchstrava.model.TrackPoint
import dev.watchstrava.model.Zones
import dev.watchstrava.service.WorkoutSession
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private enum class Page { CONTROLS, MAIN, MAP, LAP, ZONE, TOTALS }

/** Pages: controls · main · map (GPS only) · lap · HR zone · totals. */
@Composable
fun RunScreen(live: WorkoutSession.Live, banner: WorkoutSession.Banner?, prefs: RunPrefs, command: (RunCommand) -> Unit) {
    val pages = remember(live.useGps) {
        Page.entries.filter { it != Page.MAP || live.useGps }
    }
    val mainPage = pages.indexOf(Page.MAIN)
    val pager = rememberPagerState(initialPage = mainPage) { pages.size }
    val s = live.snapshot

    // Jump back to the main page a few seconds after using the controls.
    LaunchedEffect(pager.currentPage, s.paused) {
        if (pager.currentPage == 0 && !s.paused) {
            delay(8_000)
            pager.animateScrollToPage(mainPage)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
            when (pages[index]) {
                Page.CONTROLS -> ControlsPage(live, command)
                Page.MAIN -> when {
                    live.plan.sport == Sport.SKI -> SkiPage(live)
                    live.plan.sport.isClimb -> ClimbPage(live)
                    live.plan.sport == Sport.HIKE -> HikePage(live)
                    live.sensorOnly -> SensorPage(live)
                    s.step != null -> StepPage(live, prefs)
                    s.race != null -> RacePage(live, prefs)
                    else -> FreePage(live, prefs)
                }
                Page.MAP -> MapPage(live)
                Page.LAP -> if (live.plan.sport == Sport.SKI || live.plan.sport.isClimb) LastLapPage(live) else LapPage(live)
                Page.ZONE -> ZonePage(live)
                Page.TOTALS -> TotalsPage(live)
            }
        }
        PageDots(pager.currentPage, pages.size, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        val top = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        if (s.paused && pager.currentPage != 0) {
            Text(
                stringResource(if (s.autoPaused) R.string.auto_paused else R.string.paused),
                color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = top
                    .clip(RoundedCornerShape(10.dp))
                    .background(Warn)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        } else if (pager.currentPage != 0) {
            RunHeader(prefs.name, top)
        }
        banner?.let { BannerOverlay(it) }
    }
}

enum class RunCommand { PAUSE, RESUME, LAP, STOP }

/** Name and clock, fixed at the top of every data page. */
@Composable
private fun RunHeader(name: String?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (name != null) {
            Text(name.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("  ·  ", color = Muted, fontSize = 11.sp)
        }
        Text(clockNow(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// --- Pages -----------------------------------------------------------------

/*
 * Main pages are laid out top-down for the round screen, in design dp (see fit()):
 * the big numbers sit in the upper band, the 2×2 grid in the widest band, one status line at the bottom.
 */

@Composable
private fun StepPage(live: WorkoutSession.Live, prefs: RunPrefs) {
    val s = live.snapshot
    val step = s.step ?: return
    val color = stepColor(step.kind)
    Box(Modifier.fillMaxSize()) {
        ProgressRing(s.stepProgress, color)
        Column(
            Modifier.fillMaxSize().padding(top = fitDp(26f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val reps = if (step.reps > 0) " ${step.rep}/${step.reps}" else ""
            Text(stringResource(step.kind.label).uppercase() + reps, color = color, fontSize = fit(14f), fontWeight = FontWeight.Bold, maxLines = 1)
            val remaining = when (step.length) {
                is StepLength.Time -> Format.duration(s.stepRemainingMs ?: 0)
                is StepLength.Distance -> Format.meters(s.stepRemainingM ?: 0.0)
                StepLength.Open -> Format.duration(s.stepElapsedMs)
            }
            BigText(remaining, fit(40f))
            val target = Format.target(step.target, live.maxHr)
            StatusText(
                when {
                    target.isNotEmpty() -> target
                    step.length == StepLength.Open -> stringResource(R.string.press_lap_to_advance)
                    else -> ""
                },
            )
            Spacer(Modifier.height(fitDp(2f)))
            FieldGrid(live, prefs.fields, valueSize = 26f, cellWidth = 88f)
            Spacer(Modifier.height(fitDp(3f)))
            val ahead = s.aheadSec
            when {
                ahead != null -> StatusText(Format.ahead(ahead) + " " + stringResource(if (ahead >= 0) R.string.ahead else R.string.behind), if (ahead >= 0) Good else Warn)
                !s.gpsOk -> GpsWarning(live)
                else -> s.nextStep?.let {
                    StatusText(stringResource(R.string.next_step, stringResource(it.kind.label), Format.length(it.length, stringResource(R.string.step_until_lap))))
                }
            }
        }
    }
}

@Composable
private fun FreePage(live: WorkoutSession.Live, prefs: RunPrefs) {
    val s = live.snapshot
    Column(
        Modifier.fillMaxSize().padding(top = fitDp(19f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BigText(Format.duration(s.movingMs), fit(38f))
        Row(verticalAlignment = Alignment.Bottom) {
            BigText(Format.km(s.distanceM), fit(32f), Accent)
            Text(" km", fontSize = fit(13f), color = Muted, modifier = Modifier.padding(bottom = fitDp(4f)))
        }
        Spacer(Modifier.height(fitDp(2f)))
        FieldGrid(live, prefs.fields)
        Spacer(Modifier.height(fitDp(3f)))
        // One status line: GPS problems first, then plan done / target, else the zone bar.
        val target = if (s.planDone) "" else live.plan.steps.firstOrNull()?.let { Format.target(it.target, live.maxHr) }.orEmpty()
        when {
            !live.useGps || !s.gpsOk -> GpsWarning(live)
            s.planDone -> StatusText(stringResource(R.string.completed), Good)
            target.isNotEmpty() -> StatusText(stringResource(R.string.target_status, target))
            else -> ZoneBar(s.zone)
        }
    }
}

/** Race: ring = distance covered, then time, gap to the goal and the four race fields. */
@Composable
private fun RacePage(live: WorkoutSession.Live, prefs: RunPrefs) {
    val s = live.snapshot
    val race = s.race ?: return
    Box(Modifier.fillMaxSize()) {
        ProgressRing(race.progress, if (race.finished) Good else Accent)
        Column(
            Modifier.fillMaxSize().padding(top = fitDp(26f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BigText(Format.duration(s.movingMs), fit(36f))
            val ahead = race.aheadSec
            Row(verticalAlignment = Alignment.Bottom) {
                if (ahead != null) {
                    BigText(Format.ahead(ahead), fit(28f), if (ahead >= 0) Good else Warn)
                    Text(" " + stringResource(if (ahead >= 0) R.string.ahead else R.string.behind), fontSize = fit(13f), color = Muted, modifier = Modifier.padding(bottom = fitDp(3f)))
                } else {
                    BigText(Format.km(s.distanceM), fit(28f), Accent)
                    Text(" km", fontSize = fit(13f), color = Muted, modifier = Modifier.padding(bottom = fitDp(3f)))
                }
            }
            Spacer(Modifier.height(fitDp(2f)))
            FieldGrid(live, prefs.raceFields, valueSize = 26f, cellWidth = 88f)
            Spacer(Modifier.height(fitDp(3f)))
            when {
                !live.useGps || !s.gpsOk -> GpsWarning(live)
                race.finished -> StatusText(stringResource(R.string.finish_line), Good)
                else -> StatusText(s.heartRate?.let { "♥ $it · ${zoneLabel(s.zone)}" } ?: "♥ --", zoneColor(s.zone))
            }
        }
    }
}

/** HR and zone name as the bottom line, coloured by zone. */
@Composable
private fun HrStatus(s: Snapshot) =
    StatusText(s.heartRate?.let { "♥ $it · ${zoneLabel(s.zone)}" } ?: "♥ --", zoneColor(s.zone))

/** Ski: big speed, state line on top (run number while descending), four day totals, HR. */
@Composable
private fun SkiPage(live: WorkoutSession.Live) {
    val s = live.snapshot
    Column(
        Modifier.fillMaxSize().padding(top = fitDp(26f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (s.active) stringResource(R.string.ski_on_run, s.runs + 1) else stringResource(R.string.ski_off_run),
            color = if (s.active) Accent else Muted, fontSize = fit(13f), fontWeight = FontWeight.Bold, maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            BigText(Format.kmh(s.speedMps), fit(48f), if (s.active) Accent else Color.White)
            Text(" km/h", fontSize = fit(13f), color = Muted, modifier = Modifier.padding(bottom = fitDp(6f)))
        }
        Spacer(Modifier.height(fitDp(2f)))
        ValueGrid(
            listOf(
                Cell(stringResource(R.string.field_max_speed_short), Format.kmh(s.maxSpeedMps)),
                Cell(stringResource(R.string.field_altitude_short), Format.height(s.altitudeM)),
                Cell(stringResource(R.string.field_runs_short), "${s.runs}", Accent),
                Cell(stringResource(R.string.field_descent_short), Format.height(s.descentM)),
            ),
            valueSize = 28f,
        )
        Spacer(Modifier.height(fitDp(3f)))
        if (!s.gpsOk) GpsWarning(live) else HrStatus(s)
    }
}

/** Climbing: state and its timer, then routes, total time and HR (outdoor: altitude and ascent). */
@Composable
private fun ClimbPage(live: WorkoutSession.Live) {
    val s = live.snapshot
    val color = if (s.active) Accent else Easy
    val outdoor = live.plan.sport == Sport.CLIMB_OUTDOOR
    Column(
        Modifier.fillMaxSize().padding(top = fitDp(26f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (s.active) R.string.climb_on_route else R.string.climb_rest),
            color = color, fontSize = fit(15f), fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1,
        )
        BigText(Format.duration(s.stateMs), fit(44f), color)
        Spacer(Modifier.height(fitDp(2f)))
        val top = listOf(
            Cell(stringResource(R.string.field_routes_short), "${s.runs}", Accent),
            Cell(stringResource(R.string.field_total_short), Format.duration(s.movingMs)),
        )
        val bottom = if (outdoor) listOf(
            Cell(stringResource(R.string.field_altitude_short), Format.height(s.altitudeM)),
            Cell(stringResource(R.string.field_ascent_short), Format.height(s.ascentM)),
        ) else listOf(
            Cell(stringResource(R.string.field_hr_short), s.heartRate?.toString() ?: "--", zoneColor(s.zone)),
            Cell(stringResource(R.string.field_avg_hr_short), s.avgHr?.toString() ?: "--"),
        )
        ValueGrid(top + bottom, valueSize = 28f)
        Spacer(Modifier.height(fitDp(3f)))
        if (outdoor) HrStatus(s) else ZoneBar(s.zone)
    }
}

/** Hiking: time and km, then altitude, vertical speed, ascent and descent; HR at the bottom. */
@Composable
private fun HikePage(live: WorkoutSession.Live) {
    val s = live.snapshot
    Column(
        Modifier.fillMaxSize().padding(top = fitDp(19f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BigText(Format.duration(s.movingMs), fit(36f))
        Row(verticalAlignment = Alignment.Bottom) {
            BigText(Format.km(s.distanceM), fit(30f), Accent)
            Text(" km", fontSize = fit(13f), color = Muted, modifier = Modifier.padding(bottom = fitDp(4f)))
        }
        Spacer(Modifier.height(fitDp(2f)))
        ValueGrid(
            listOf(
                Cell(stringResource(R.string.field_altitude_short), Format.height(s.altitudeM)),
                Cell(stringResource(R.string.field_vspeed_short), Format.vspeed(s.verticalSpeed)),
                Cell(stringResource(R.string.field_ascent_short), Format.height(s.ascentM)),
                Cell(stringResource(R.string.field_descent_short), Format.height(s.descentM)),
            ),
            valueSize = 28f,
        )
        Spacer(Modifier.height(fitDp(3f)))
        when {
            !live.useGps || !s.gpsOk -> GpsWarning(live)
            live.offRoute -> StatusText(stringResource(R.string.off_route), Warn)
            else -> HrStatus(s)
        }
    }
}

@Composable
private fun SensorPage(live: WorkoutSession.Live) {
    val s = live.snapshot
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(s.heartRate?.toString() ?: "--", fontSize = 60.sp, fontWeight = FontWeight.Bold, color = zoneColor(s.zone))
        Text(stringResource(R.string.fmt_bpm_zone, s.zone), color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        ZoneBar(s.zone)
        Spacer(Modifier.height(6.dp))
        Text(Format.duration(s.movingMs), fontSize = 22.sp)
        BleStatus(live)
    }
}

/** Live breadcrumb of the run, north up, auto-zoomed to fit. */
@Composable
private fun MapPage(live: WorkoutSession.Live) {
    val s = live.snapshot
    val track = live.track
    val route = live.route
    val accent = Accent
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxPx = with(density) { maxWidth.toPx() } * 0.62f
        val fit = remember(track, route) { TrackFit.of(if (route.isEmpty()) track else route + track) }
        val mPerPx = fit.spanM / boxPx
        val barM = TrackFit.niceScale(mPerPx * with(density) { 40.dp.toPx() })
        val barDp = with(density) { (barM / mPerPx).toDp() }

        Canvas(Modifier.fillMaxSize()) {
            val c = center
            drawCircle(Color(0x1AFFFFFF), radius = boxPx * 0.72f, center = c, style = Stroke(1.dp.toPx()))
            drawCircle(Color(0x10FFFFFF), radius = boxPx * 0.36f, center = c, style = Stroke(1.dp.toPx()))
            fun pos(p: TrackPoint) = Offset(
                c.x + (p.x - fit.centerX) / fit.spanM * boxPx,
                c.y - (p.y - fit.centerY) / fit.spanM * boxPx,
            )
            fun pathOf(points: List<TrackPoint>) = Path().apply {
                points.forEachIndexed { i, p -> pos(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            }
            if (route.isNotEmpty()) {
                drawPath(pathOf(route), Muted.copy(alpha = 0.55f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(Color.White, radius = 5.dp.toPx(), center = pos(route.last()), style = Stroke(2.dp.toPx()))
                drawCircle(Good, radius = 3.5.dp.toPx(), center = pos(route.first()))
            }
            if (track.isEmpty()) return@Canvas
            val path = pathOf(track)
            drawPath(path, accent.copy(alpha = 0.35f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, accent, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Good, radius = 4.dp.toPx(), center = pos(track.first()))
            val here = pos(track.last())
            drawCircle(Color.Black, radius = 8.dp.toPx(), center = here)
            drawCircle(accent, radius = 7.dp.toPx(), center = here)
            drawCircle(Color.White, radius = 4.dp.toPx(), center = here)
        }

        Text(
            "N", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp),
        )
        if (track.isEmpty()) {
            Text(
                stringResource(if (live.useGps) R.string.waiting_gps else R.string.gps_denied),
                color = Warn, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            live.routeRemainingM?.let {
                Text(stringResource(R.string.route_left, Format.km(it)), color = if (live.offRoute) Warn else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.km(s.distanceM), color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(" km  ", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
                if (s.sport == Sport.SKI) {
                    Text(Format.kmh(s.speedMps), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(" km/h", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
                } else {
                    Text(Format.pace(s.pace), color = statusColor(s.targetStatus), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(" /km", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            if (track.isNotEmpty()) {
                Box(Modifier.width(barDp).height(2.dp).background(Color.White))
                Text(Format.meters(barM.toDouble()), color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun LapPage(live: WorkoutSession.Live) {
    val s = live.snapshot
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (s.step != null) stringResource(R.string.fmt_step_of, s.stepNumber, s.stepCount) else stringResource(R.string.lap_n, s.lapNumber),
            color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        Text(Format.duration(s.lapMs), fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(Format.meters(s.lapDistanceM), fontSize = 24.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Metric(Format.pace(Format.paceOf(s.lapDistanceM, s.lapMs)), stringResource(R.string.per_km_avg), Color.White, 22.sp)
        }
        Metric(s.lapAvgHr?.toString() ?: "--", stringResource(R.string.hr_avg), Muted, 18.sp)
    }
}

/** Ski / climbing: the last completed run or route (the current one is on the main page). */
@Composable
private fun LastLapPage(live: WorkoutSession.Live) {
    val s = live.snapshot
    val ski = s.sport == Sport.SKI
    val lap = s.lastLap
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (lap == null) {
            Text(stringResource(if (ski) R.string.no_runs_yet else R.string.no_routes_yet), color = Muted, fontSize = 14.sp)
            return@Column
        }
        Text(
            stringResource(if (ski) R.string.last_run else R.string.last_route) + " · ${lap.number}",
            color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        if (ski) {
            Text("↓ ${Format.height(lap.descentM)}", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Metric(Format.kmh(lap.maxSpeedMps), stringResource(R.string.field_max_speed_short), Color.White, 22.sp)
            Text("${Format.duration(lap.movingMs)} · ${Format.meters(lap.distanceM)}", fontSize = 18.sp)
        } else {
            Text(Format.duration(lap.movingMs), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            if (lap.ascentM >= 1) Text("↑ ${Format.height(lap.ascentM)}", fontSize = 20.sp)
        }
        Spacer(Modifier.height(4.dp))
        Metric("${lap.avgHr ?: "--"} / ${lap.maxHr ?: "--"}", "♥", Muted, 18.sp)
    }
}

@Composable
private fun ZonePage(live: WorkoutSession.Live) {
    val s = live.snapshot
    val bpm = s.heartRate
    Box(Modifier.fillMaxSize()) {
        ZoneGauge(bpm, live.maxHr, Modifier.fillMaxSize().padding(6.dp))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(bpm?.toString() ?: "--", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = zoneColor(s.zone))
            Text(zoneLabel(s.zone), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            bpm?.let { Text(stringResource(R.string.pct_max_hr, it * 100 / live.maxHr), color = Muted, fontSize = 12.sp) }
            s.avgHr?.let { Text(stringResource(R.string.avg_value, it), color = Muted, fontSize = 12.sp) }
        }
    }
}


@Composable
private fun TotalsPage(live: WorkoutSession.Live) {
    val s = live.snapshot
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(clockNow(), fontSize = 16.sp, color = Muted)
        TotalRow(stringResource(R.string.time), Format.duration(s.movingMs))
        if (live.useGps) TotalRow(stringResource(R.string.distance), "${Format.km(s.distanceM)} km")
        if (s.sport == Sport.RUN) {
            TotalRow(stringResource(R.string.avg_pace), Format.pace(s.avgPace))
        } else {
            TotalRow(stringResource(R.string.ascent), Format.height(s.ascentM))
            TotalRow(stringResource(R.string.descent), Format.height(s.descentM))
        }
        TotalRow(stringResource(R.string.avg_hr), s.avgHr?.toString() ?: "--")
        if (s.sport == Sport.RUN) TotalRow(stringResource(R.string.cadence), s.cadence?.let { stringResource(R.string.fmt_spm, it) } ?: "--")
        Spacer(Modifier.height(4.dp))
        if (live.useGps) Text(stringResource(if (s.gpsOk) R.string.gps_ok else R.string.gps_missing), color = if (s.gpsOk) Good else Warn, fontSize = 11.sp)
        BleStatus(live)
    }
}

@Composable
private fun ControlsPage(live: WorkoutSession.Live, command: (RunCommand) -> Unit) {
    val s = live.snapshot
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(confirmStop) {
        if (confirmStop) {
            delay(3_000)
            confirmStop = false
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        if (s.paused) {
            ControlChip(stringResource(R.string.resume), Good) { command(RunCommand.RESUME) }
        } else {
            ControlChip(stringResource(R.string.pause), Warn, Color.Black) { command(RunCommand.PAUSE) }
        }
        // Ski runs are automatic: no lap button.
        val lapLabel = when {
            s.sport == Sport.SKI -> null
            s.sport.isClimb -> if (s.active) R.string.climb_end else R.string.climb_start
            s.step != null -> R.string.next_step_button
            else -> R.string.lap
        }
        lapLabel?.let { ControlChip(stringResource(it), if (s.sport.isClimb && !s.active) Accent else Surface) { command(RunCommand.LAP) } }
        ControlChip(stringResource(if (confirmStop) R.string.tap_to_end else R.string.end), Bad) {
            if (confirmStop) command(RunCommand.STOP) else confirmStop = true
        }
    }
}

// --- Pieces ----------------------------------------------------------------

@Composable
private fun Metric(value: String, unit: String, color: Color, size: TextUnit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = size, fontWeight = FontWeight.Bold)
        Text(" $unit", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun TotalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GpsWarning(live: WorkoutSession.Live) {
    when {
        !live.useGps -> StatusText(stringResource(R.string.gps_denied), Bad)
        !live.snapshot.gpsOk -> StatusText(stringResource(R.string.gps_searching), Warn)
    }
}

/** The single line at the bottom of a main page. */
@Composable
private fun StatusText(text: String, color: Color = Muted) {
    if (text.isEmpty()) return
    Text(text, color = color, fontSize = fit(12f), fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
}

/** Thin progress ring along the bezel. */
@Composable
private fun ProgressRing(progress: Float, color: Color) {
    CircularProgressIndicator(
        progress = progress,
        modifier = Modifier.fillMaxSize().padding(3.dp),
        indicatorColor = color,
        trackColor = color.copy(alpha = 0.18f),
        strokeWidth = 7.dp,
    )
}

@Composable
private fun BleStatus(live: WorkoutSession.Live) {
    val (text, color) = when {
        live.bleError != null -> live.bleError to Bad
        live.bleConnected > 0 -> stringResource(R.string.iphone_connected) to Good
        else -> stringResource(R.string.waiting_iphone) to Muted
    }
    Text(text, color = color, fontSize = 11.sp, textAlign = TextAlign.Center)
}

@Composable
private fun ControlChip(label: String, background: Color, content: Color = Color.White, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        colors = ChipDefaults.chipColors(backgroundColor = background, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) {
            Box(
                Modifier
                    .size(if (it == current) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (it == current) Color.White else Color(0x66FFFFFF)),
            )
        }
    }
}

/** Five coloured zone arcs around the bottom/sides with a marker at the current HR. */
@Composable
private fun ZoneGauge(bpm: Int?, maxHr: Int, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 10.dp.toPx()
        val start = 150f
        val sweep = 240f
        val seg = sweep / 5
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        for (z in 1..5) {
            drawArc(
                color = zoneColor(z),
                startAngle = start + (z - 1) * seg + 1.5f,
                sweepAngle = seg - 3f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Butt),
            )
        }
        bpm ?: return@Canvas
        val angle = Math.toRadians((start + sweep * Zones.gaugePosition(bpm, maxHr)).toDouble())
        val r = arcSize.width / 2
        val c = Offset(size.width / 2 + (cos(angle) * r).toFloat(), size.height / 2 + (sin(angle) * r).toFloat())
        drawCircle(Color.Black, radius = stroke, center = c)
        drawCircle(Color.White, radius = stroke * 0.7f, center = c)
    }
}

@Composable
private fun BannerOverlay(b: WorkoutSession.Banner) {
    LaunchedEffect(b) {
        delay((b.untilMs - System.currentTimeMillis()).coerceAtLeast(0))
        if (WorkoutSession.banner.value === b) WorkoutSession.banner.value = null
    }
    val color = toneColor(b.tone)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0000000))
            .border(8.dp, color, CircleShape)
            .clickable { WorkoutSession.banner.value = null },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            // Shrink long titles ("RISCALDAMENTO", "RECUPERO 2/6") so they never break mid-word.
            Text(b.title, color = color, fontSize = fit(minOf(28f, 250f / b.title.length.coerceAtLeast(1))),
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
            if (b.detail.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(b.detail, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
