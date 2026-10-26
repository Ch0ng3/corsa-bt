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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import dev.watchstrava.model.Format
import dev.watchstrava.model.StepLength
import dev.watchstrava.model.TrackFit
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
                    live.sensorOnly -> SensorPage(live)
                    s.step != null -> StepPage(live, prefs)
                    else -> FreePage(live, prefs)
                }
                Page.MAP -> MapPage(live)
                Page.LAP -> LapPage(live)
                Page.ZONE -> ZonePage(live)
                Page.TOTALS -> TotalsPage(live)
            }
        }
        PageDots(pager.currentPage, pages.size, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        val top = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
        if (s.paused && pager.currentPage != 0) {
            Text(
                if (s.autoPaused) "PAUSA AUTO" else "IN PAUSA",
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

@Composable
private fun StepPage(live: WorkoutSession.Live, prefs: RunPrefs) {
    val s = live.snapshot
    val step = s.step ?: return
    val color = stepColor(step.kind)
    Box(Modifier.fillMaxSize()) {
        CircularProgressIndicator(
            progress = s.stepProgress,
            modifier = Modifier.fillMaxSize().padding(3.dp),
            indicatorColor = color,
            trackColor = color.copy(alpha = 0.18f),
            strokeWidth = 7.dp,
        )
        Column(
            Modifier.align(Alignment.Center).padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val reps = if (step.reps > 0) " ${step.rep}/${step.reps}" else ""
            Text(step.kind.label.uppercase() + reps, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            val remaining = when (step.length) {
                is StepLength.Time -> Format.duration(s.stepRemainingMs ?: 0)
                is StepLength.Distance -> Format.meters(s.stepRemainingM ?: 0.0)
                StepLength.Open -> Format.duration(s.stepElapsedMs)
            }
            Text(remaining, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            val target = Format.target(step.target, live.maxHr)
            when {
                target.isNotEmpty() -> Text(target, color = Muted, fontSize = 11.sp)
                step.length == StepLength.Open -> Text("premi GIRO per avanzare", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(3.dp))
            FieldGrid(live, prefs.fields, valueSize = 18.sp, cellWidth = 76.dp)
            val ahead = s.aheadSec
            when {
                ahead != null -> Text(
                    (if (ahead >= 0) "+" else "−") + Format.seconds(kotlin.math.abs(ahead)) + if (ahead >= 0) " avanti" else " indietro",
                    color = if (ahead >= 0) Good else Warn, fontSize = 11.sp,
                )
                !live.snapshot.gpsOk -> GpsWarning(live)
                else -> s.nextStep?.let {
                    Text("Poi: ${it.kind.label} ${Format.length(it.length)}", color = Muted, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FreePage(live: WorkoutSession.Live, prefs: RunPrefs) {
    val s = live.snapshot
    Column(
        Modifier.fillMaxSize().padding(top = 24.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (s.planDone) Text("COMPLETATO", color = Good, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(Format.duration(s.movingMs), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.km(s.distanceM), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text(" km", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(4.dp))
        FieldGrid(live, prefs.fields)
        Spacer(Modifier.height(5.dp))
        ZoneBar(s.zone)
        // One status line: GPS problems first, then the target, else the zone name.
        val target = if (s.planDone) "" else live.plan.steps.firstOrNull()?.let { Format.target(it.target, live.maxHr) }.orEmpty()
        when {
            !live.useGps || !s.gpsOk -> GpsWarning(live)
            target.isNotEmpty() -> Text("obiettivo $target", color = Muted, fontSize = 10.sp, maxLines = 1)
            else -> Text(zoneLabel(s.zone), color = zoneColor(s.zone), fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
        Text("bpm · Z${s.zone}", color = Muted, fontSize = 13.sp)
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
    val accent = Accent
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxPx = with(density) { maxWidth.toPx() } * 0.62f
        val fit = remember(track) { TrackFit.of(track) }
        val mPerPx = fit.spanM / boxPx
        val barM = TrackFit.niceScale(mPerPx * with(density) { 40.dp.toPx() })
        val barDp = with(density) { (barM / mPerPx).toDp() }

        Canvas(Modifier.fillMaxSize()) {
            val c = center
            drawCircle(Color(0x1AFFFFFF), radius = boxPx * 0.72f, center = c, style = Stroke(1.dp.toPx()))
            drawCircle(Color(0x10FFFFFF), radius = boxPx * 0.36f, center = c, style = Stroke(1.dp.toPx()))
            if (track.isEmpty()) return@Canvas
            fun pos(i: Int): Offset {
                val p = track[i]
                return Offset(
                    c.x + (p.x - fit.centerX) / fit.spanM * boxPx,
                    c.y - (p.y - fit.centerY) / fit.spanM * boxPx,
                )
            }
            val path = Path()
            track.indices.forEach { i -> pos(i).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, accent.copy(alpha = 0.35f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, accent, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Good, radius = 4.dp.toPx(), center = pos(0))
            val here = pos(track.lastIndex)
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
                if (live.useGps) "In attesa del GPS…" else "GPS non autorizzato",
                color = Warn, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.km(s.distanceM), color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(" km  ", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
                Text(Format.pace(s.pace), color = statusColor(s.targetStatus), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(" /km", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
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
            if (s.step != null) "STEP ${s.stepNumber}/${s.stepCount}" else "GIRO ${s.lapNumber}",
            color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        Text(Format.duration(s.lapMs), fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(Format.meters(s.lapDistanceM), fontSize = 24.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Metric(Format.pace(Format.paceOf(s.lapDistanceM, s.lapMs)), "/km medio", Color.White, 22.sp)
        }
        Metric(s.lapAvgHr?.toString() ?: "--", "♥ medio", Muted, 18.sp)
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
            Text(if (s.zone > 0) "Z${s.zone} ${Zones.names[s.zone]}" else "sotto Z1", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            bpm?.let { Text("${it * 100 / live.maxHr}% FC max", color = Muted, fontSize = 12.sp) }
            s.avgHr?.let { Text("media $it", color = Muted, fontSize = 12.sp) }
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
        TotalRow("Tempo", Format.duration(s.movingMs))
        TotalRow("Distanza", "${Format.km(s.distanceM)} km")
        TotalRow("Passo medio", Format.pace(s.avgPace))
        TotalRow("FC media", s.avgHr?.toString() ?: "--")
        TotalRow("Cadenza", s.cadence?.let { "$it spm" } ?: "--")
        Spacer(Modifier.height(4.dp))
        if (live.useGps) Text(if (s.gpsOk) "GPS ok" else "GPS assente", color = if (s.gpsOk) Good else Warn, fontSize = 11.sp)
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
            ControlChip("Riprendi", Good) { command(RunCommand.RESUME) }
        } else {
            ControlChip("Pausa", Warn, Color.Black) { command(RunCommand.PAUSE) }
        }
        ControlChip(if (s.step != null) "Step successivo" else "Giro", Surface) { command(RunCommand.LAP) }
        ControlChip(if (confirmStop) "Tocca per finire" else "Fine", Bad) {
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
        !live.useGps -> Text("GPS non autorizzato", color = Bad, fontSize = 11.sp)
        !live.snapshot.gpsOk -> Text("Ricerca GPS…", color = Warn, fontSize = 11.sp)
    }
}

@Composable
private fun BleStatus(live: WorkoutSession.Live) {
    val (text, color) = when {
        live.bleError != null -> live.bleError to Bad
        live.bleConnected > 0 -> "iPhone collegato" to Good
        else -> "Battito: in attesa di iPhone" to Muted
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
            Text(b.title, color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (b.detail.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(b.detail, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
