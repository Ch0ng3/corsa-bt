package dev.watchstrava.engine

import dev.watchstrava.model.Format
import dev.watchstrava.model.Plan
import dev.watchstrava.model.Race
import dev.watchstrava.model.Sport
import dev.watchstrava.model.Step
import dev.watchstrava.model.StepKind
import dev.watchstrava.model.StepLength
import dev.watchstrava.model.Target
import dev.watchstrava.model.Zones
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class TargetStatus { NONE, OK, TOO_FAST, TOO_SLOW, HR_LOW, HR_HIGH }

/** Words for lap labels, supplied by the caller so the engine needs no Android resources. */
class LapLabels(
    val lap: (number: Int) -> String,
    val step: (StepKind) -> String,
    /** Ski run ("Discesa 3"). */
    val run: (number: Int) -> String = lap,
    /** Climbing route ("Via 3"). */
    val climb: (number: Int) -> String = lap,
)

data class Lap(
    val number: Int,
    val label: String,
    val movingMs: Long,
    val distanceM: Double,
    val avgHr: Int?,
    val maxHr: Int?,
    val ascentM: Double = 0.0,
    /** For a ski run: the vertical drop from its top to its lowest point. */
    val descentM: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
) {
    val pace: Int? get() = Format.paceOf(distanceM, movingMs)
}

data class Summary(
    val startedAt: Long,
    val name: String,
    val movingMs: Long,
    val distanceM: Double,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgCadence: Int?,
    val zoneMs: List<Long>,
    val laps: List<Lap>,
    val sport: Sport = Sport.RUN,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val maxAltitudeM: Double? = null,
    val maxSpeedMps: Double = 0.0,
) {
    val pace: Int? get() = Format.paceOf(distanceM, movingMs)
    /** Ski: time and distance on runs; climbing: time on routes (laps are only runs/routes there). */
    val lapsMs: Long get() = laps.sumOf { it.movingMs }
    val lapsDistanceM: Double get() = laps.sumOf { it.distanceM }
    val longestLapMs: Long get() = laps.maxOfOrNull { it.movingMs } ?: 0L
}

data class Snapshot(
    val movingMs: Long,
    val distanceM: Double,
    val pace: Int?,
    val avgPace: Int?,
    val heartRate: Int?,
    val zone: Int,
    val avgHr: Int?,
    val cadence: Int?,
    val paused: Boolean,
    val autoPaused: Boolean,
    val gpsOk: Boolean,
    val lapNumber: Int,
    val lapMs: Long,
    val lapDistanceM: Double,
    val lapAvgHr: Int?,
    val structured: Boolean,
    val planDone: Boolean,
    val step: Step?,
    val nextStep: Step?,
    val stepNumber: Int,
    val stepCount: Int,
    val stepElapsedMs: Long,
    val stepDistanceM: Double,
    val stepRemainingMs: Long?,
    val stepRemainingM: Double?,
    val stepProgress: Float,
    val targetStatus: TargetStatus,
    /** Seconds ahead (+) or behind (−) the middle of the pace target in this step. */
    val aheadSec: Int?,
    val race: RaceStatus? = null,
    val sport: Sport = Sport.RUN,
    /** Barometric altitude (calibrated to GPS when possible); null without a barometer. */
    val altitudeM: Double? = null,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    /** m/h, + going up; null until enough altitude samples. */
    val verticalSpeed: Int? = null,
    val speedMps: Double? = null,
    val maxSpeedMps: Double = 0.0,
    /** Ski: a run is in progress; climbing: on a route. */
    val active: Boolean = false,
    /** Climbing: time in the current state (route or rest). */
    val stateMs: Long = 0,
    /** Completed ski runs / climbing routes. */
    val runs: Int = 0,
    val lastLap: Lap? = null,
)

data class RaceStatus(
    val remainingM: Double,
    /** Finish time at the current average pace, or the real one once [finished]. */
    val finishMs: Long?,
    /** Seconds ahead (+) or behind (−) the goal time at this distance; null without a goal. */
    val aheadSec: Int?,
    /** Pace needed from here to finish on the goal time; null without a goal or when out of reach. */
    val neededPace: Int?,
    val progress: Float,
    val finished: Boolean,
)

/**
 * Pure workout logic: no Android types, driven by [tick] once per second plus sensor callbacks.
 * Times are wall-clock milliseconds supplied by the caller, which keeps it testable.
 */
class WorkoutEngine(
    val plan: Plan,
    private val maxHr: Int,
    private val autoPause: Boolean,
    private val labels: LapLabels,
) {
    sealed interface Event {
        /** [fromStep] = closed by a step change of a structured plan (announced by StepStarted). */
        data class LapDone(val lap: Lap, val auto: Boolean, val fromStep: Boolean = false) : Event
        data class StepStarted(val step: Step) : Event
        data class Countdown(val seconds: Int) : Event
        data class OffTarget(val status: TargetStatus) : Event
        data object PlanDone : Event
        data class AutoPaused(val paused: Boolean) : Event
        data class RaceFinished(val movingMs: Long, val aheadSec: Int?) : Event
        /** A ski run was detected and closed (it is also the newest lap). */
        data class RunDone(val lap: Lap) : Event
        /** Climbing state changed by GIRO; [lap] is the route just finished. */
        data class ClimbToggled(val climbing: Boolean, val lap: Lap?) : Event
    }

    companion object {
        const val MIN_SPEED = 0.5 // m/s; slower counts as standing still
        private const val FIX_STALE_MS = 5_000L
        private const val HR_STALE_MS = 10_000L
        private const val AUTO_PAUSE_AFTER_MS = 3_000L
        private const val RESUME_SPEED = 1.0
        private const val ALERT_GRACE_MS = 15_000L // after a step starts
        private const val ALERT_OFF_FOR_MS = 5_000L // continuously off target
        private const val ALERT_REPEAT_MS = 30_000L
        private const val CADENCE_WINDOW_MS = 15_000L
        private const val RACE_ESTIMATE_AFTER_M = 200.0
        private const val MAX_PLAUSIBLE_SPEED = 45.0 // m/s; faster is a GPS glitch

        // Altitude
        const val ALT_HYSTERESIS_M = 3.0 // smaller wobbles are not ascent/descent
        private const val VSPEED_WINDOW_MS = 60_000L
        private const val VSPEED_MIN_SPAN_MS = 20_000L
        private const val ALT_STALE_MS = 10_000L

        // Ski run detection
        const val SKI_START_SPEED = 10 / 3.6 // m/s
        const val SKI_START_DROP_M = 15.0 // below the highest point since the last run
        const val SKI_LIFT_CLIMB_M = 20.0 // above the lowest point of the run: on a lift
        const val SKI_STILL_SPEED = 1.0 // m/s
        const val SKI_STILL_MS = 180_000L
        const val SKI_MIN_DROP_M = 30.0 // shorter "runs" (lift dips, bumps) are dropped
    }

    private var started = false
    private var startedAt = 0L
    private var lastTickMs = 0L
    private var manualPaused = false
    private var autoPaused = false
    private var slowMs = 0L
    private var hadFix = false

    private var movingMs = 0L
    private var distanceM = 0.0
    private var speed: Double? = null // smoothed, for display and targets
    private var rawSpeed = 0.0 // latest fix, for a responsive auto-pause
    private var lastFixMs = 0L

    private var hr: Int? = null
    private var hrMs = 0L
    private var hrSum = 0L
    private var hrN = 0
    private var hrMax = 0
    private val zoneMs = LongArray(6)
    private val stepSamples = ArrayDeque<Pair<Long, Long>>()
    private var cadenceSum = 0L
    private var cadenceN = 0

    private var stepIndex = 0
    private var stepStartMs = 0L
    private var stepStartM = 0.0
    private var offSinceMs: Long? = null
    private var lastAlertMs = Long.MIN_VALUE / 2
    private var lastCountdown = 0

    private val laps = mutableListOf<Lap>()
    private var lapStartMs = 0L
    private var lapStartM = 0.0
    private var lapHrSum = 0L
    private var lapHrN = 0
    private var lapHrMax = 0
    private var nextAutoLapM = plan.autoLapMeters.toDouble()
    private var raceFinishMs: Long? = null
    private var lapAscent0 = 0.0
    private var lapDescent0 = 0.0
    private var lapMaxSpeed = 0.0
    private var maxSpeed = 0.0
    /** Events raised by sensor callbacks, delivered by the next [tick]. */
    private val pending = mutableListOf<Event>()

    // Altitude, in the barometer's frame; [altOffset] only shifts what is shown.
    private var altitude: Double? = null
    private var altMs = 0L
    private var altOffset = 0.0
    private var altRef: Double? = null
    private var ascentM = 0.0
    private var descentM = 0.0
    private var maxAlt: Double? = null
    private val altSamples = ArrayDeque<Pair<Long, Double>>()

    // Ski: highest point since the last run, then the current run.
    private var onRun = false
    private var peakAlt: Double? = null
    private var peakMs = 0L
    private var peakM = 0.0
    private var peakSpeed = 0.0
    private var runTopAlt = 0.0
    private var runMinAlt = 0.0
    private var runMinMs = 0L
    private var runMinM = 0.0
    private var runMaxSpeed = 0.0
    private var stillMs = 0L
    private var stillStartMs = 0L
    private var stillStartM = 0.0

    // Climbing
    private var climbing = false
    private var stateStartMs = 0L
    private var lastToggleMs = -10_000L

    private val currentStep: Step? get() = plan.steps.getOrNull(stepIndex)
    private val planDone get() = plan.structured && stepIndex >= plan.steps.size
    val isPaused get() = manualPaused || autoPaused

    /**
     * [now] is a monotonic clock (elapsedRealtime) used for all timing;
     * [wallClock] only stamps the summary.
     */
    fun start(now: Long, wallClock: Long = now): List<Event> {
        started = true
        startedAt = wallClock
        lastTickMs = now
        return if (plan.structured) listOfNotNull(currentStep?.let { Event.StepStarted(it) }) else emptyList()
    }

    /** [deltaM] = distance since the previous accepted fix, [speedMps] = current GPS speed. */
    fun onLocation(now: Long, deltaM: Double, speedMps: Double) {
        if (!started || speedMps > MAX_PLAUSIBLE_SPEED) return // GPS glitch
        if (!isPaused) distanceM += deltaM
        val v = speed?.let { it * 0.7 + speedMps * 0.3 } ?: speedMps
        speed = v
        rawSpeed = speedMps
        lastFixMs = now
        hadFix = true
        if (!isPaused && v <= MAX_PLAUSIBLE_SPEED) {
            maxSpeed = maxOf(maxSpeed, v)
            lapMaxSpeed = maxOf(lapMaxSpeed, v)
            if (onRun) runMaxSpeed = maxOf(runMaxSpeed, v) else peakSpeed = maxOf(peakSpeed, v)
        }
    }

    /** Smoothed barometric altitude, about once per second. */
    fun onAltitude(now: Long, meters: Double) {
        if (!started) return
        altitude = meters
        altMs = now
        maxAlt = maxOf(maxAlt ?: meters, meters)
        altSamples.addLast(now to meters)
        while (altSamples.size > 2 && now - altSamples.first().first > VSPEED_WINDOW_MS) altSamples.removeFirst()
        if (isPaused) {
            altRef = meters
            return
        }
        val ref = altRef ?: meters
        when {
            meters - ref >= ALT_HYSTERESIS_M -> { ascentM += meters - ref; altRef = meters }
            ref - meters >= ALT_HYSTERESIS_M -> { descentM += ref - meters; altRef = meters }
            else -> altRef = ref
        }
        if (plan.sport == Sport.SKI) skiAltitude(meters)
    }

    /** Barometer-to-real altitude offset, e.g. from the first GPS fixes; never counts as ascent. */
    fun setAltitudeOffset(meters: Double) {
        altOffset = meters
    }

    fun onHeartRate(now: Long, bpm: Int) {
        hr = bpm
        hrMs = now
    }

    /** [total] is the cumulative step-counter value. */
    fun onSteps(now: Long, total: Long) {
        stepSamples.addLast(now to total)
        while (stepSamples.size > 2 && now - stepSamples.first().first > CADENCE_WINDOW_MS) stepSamples.removeFirst()
    }

    fun pause() {
        manualPaused = true
    }

    fun resume() {
        manualPaused = false
        autoPaused = false
        slowMs = 0
    }

    /** GIRO: next step in a structured plan, climbing ↔ resting, nothing in ski, otherwise a manual lap. */
    fun lap(): List<Event> {
        if (!started) return emptyList()
        if (plan.sport == Sport.SKI) return emptyList()
        if (plan.sport.isClimb) return listOfNotNull(toggleClimb())
        if (plan.structured && !planDone) return advance()
        nextAutoLapM = distanceM + plan.autoLapMeters
        return listOf(Event.LapDone(closeLap(lapLabel()), auto = false))
    }

    fun tick(now: Long): List<Event> {
        if (!started) return emptyList()
        val events = pending.toMutableList()
        pending.clear()
        val dt = (now - lastTickMs).coerceIn(0, 5_000)
        lastTickMs = now
        val fixStale = now - lastFixMs > FIX_STALE_MS
        if (fixStale) speed = null

        // Without a recent fix the speed is unknown (tunnel, bridge): leave auto-pause as it is.
        if (autoPause && hadFix && !fixStale && !manualPaused) {
            val v = rawSpeed
            if (!autoPaused) {
                slowMs = if (v < MIN_SPEED) slowMs + dt else 0
                if (slowMs >= AUTO_PAUSE_AFTER_MS) {
                    autoPaused = true
                    events += Event.AutoPaused(true)
                }
            } else if (v > RESUME_SPEED) {
                autoPaused = false
                slowMs = 0
                events += Event.AutoPaused(false)
            }
        }
        if (isPaused) return events

        // Ski: standing still for a while ends the run where the stop began.
        if (onRun) {
            val v = speed
            if (v != null && v < SKI_STILL_SPEED) {
                if (stillMs == 0L) { stillStartMs = movingMs; stillStartM = distanceM }
                stillMs += dt
                if (stillMs >= SKI_STILL_MS) endRun(stillStartMs, stillStartM)
            } else if (v != null) {
                stillMs = 0
            }
            events += pending
            pending.clear()
        }

        movingMs += dt
        currentHr(now)?.let { bpm ->
            hrSum += bpm; hrN++; hrMax = maxOf(hrMax, bpm)
            lapHrSum += bpm; lapHrN++; lapHrMax = maxOf(lapHrMax, bpm)
            zoneMs[Zones.zoneOf(bpm, maxHr)] += dt
        }
        cadence(now)?.let { if (it > 0) { cadenceSum += it; cadenceN++ } }

        if (!plan.structured || planDone) {
            if (plan.autoLapMeters > 0 && distanceM >= nextAutoLapM) {
                events += Event.LapDone(closeLap(lapLabel()), auto = true)
                nextAutoLapM += plan.autoLapMeters
            }
        }
        plan.race?.let { race ->
            if (raceFinishMs == null && distanceM >= race.meters) {
                raceFinishMs = movingMs
                events += Event.RaceFinished(movingMs, raceAhead(race))
            }
        }

        val step = currentStep
        if (plan.structured && step != null) {
            when (val len = step.length) {
                is StepLength.Time -> {
                    val remaining = len.seconds * 1000L - (movingMs - stepStartMs)
                    if (remaining <= 0) {
                        events += advance()
                        return events
                    }
                    val s = ceil(remaining / 1000.0).toInt()
                    if (s in 1..3 && s != lastCountdown) {
                        lastCountdown = s
                        events += Event.Countdown(s)
                    }
                }
                is StepLength.Distance -> if (distanceM - stepStartM >= len.meters) {
                    events += advance()
                    return events
                }
                StepLength.Open -> Unit
            }
        }

        // Slow-down / speed-up alerts only in structured workouts; free runs just colour the pace.
        val status = if (plan.structured) targetStatus(now) else TargetStatus.NONE
        if (status == TargetStatus.NONE || status == TargetStatus.OK) {
            offSinceMs = null
        } else {
            val since = offSinceMs ?: movingMs.also { offSinceMs = it }
            if (movingMs - stepStartMs >= ALERT_GRACE_MS &&
                movingMs - since >= ALERT_OFF_FOR_MS &&
                movingMs - lastAlertMs >= ALERT_REPEAT_MS
            ) {
                lastAlertMs = movingMs
                events += Event.OffTarget(status)
            }
        }
        return events
    }

    fun finish(): Summary {
        // Ski and climbing laps are only runs/routes: close one only if it is in progress.
        when {
            plan.sport == Sport.SKI -> if (onRun) endRun(movingMs, distanceM)
            plan.sport.isClimb -> if (climbing && movingMs - lapStartMs >= 1_000) closeLap(labels.climb(laps.size + 1))
            movingMs - lapStartMs >= 1_000 -> closeLap(lapLabel())
        }
        return Summary(
            startedAt = startedAt,
            name = plan.name,
            movingMs = movingMs,
            distanceM = distanceM,
            avgHr = if (hrN > 0) (hrSum / hrN).toInt() else null,
            maxHr = hrMax.takeIf { it > 0 },
            avgCadence = if (cadenceN > 0) (cadenceSum / cadenceN).toInt() else null,
            zoneMs = zoneMs.toList(),
            laps = laps.toList(),
            sport = plan.sport,
            ascentM = ascentM,
            descentM = descentM,
            maxAltitudeM = maxAlt?.plus(altOffset),
            maxSpeedMps = maxSpeed,
        )
    }

    fun snapshot(now: Long): Snapshot {
        val step = currentStep
        val stepElapsed = movingMs - stepStartMs
        val stepDist = distanceM - stepStartM
        val remainingMs = (step?.length as? StepLength.Time)?.let { (it.seconds * 1000L - stepElapsed).coerceAtLeast(0) }
        val remainingM = (step?.length as? StepLength.Distance)?.let { (it.meters - stepDist).coerceAtLeast(0.0) }
        val progress = when (val len = step?.length) {
            is StepLength.Time -> stepElapsed / (len.seconds * 1000f)
            is StepLength.Distance -> (stepDist / len.meters).toFloat()
            else -> 0f
        }.coerceIn(0f, 1f)
        val bpm = currentHr(now)
        val target = step?.target
        val ahead = if (target is Target.Pace && stepDist >= 50) {
            (stepDist / 1000 * target.middle - stepElapsed / 1000.0).roundToInt()
        } else null
        return Snapshot(
            movingMs = movingMs,
            distanceM = distanceM,
            pace = paceOf(speed),
            avgPace = Format.paceOf(distanceM, movingMs),
            heartRate = bpm,
            zone = bpm?.let { Zones.zoneOf(it, maxHr) } ?: 0,
            avgHr = if (hrN > 0) (hrSum / hrN).toInt() else null,
            cadence = cadence(now),
            paused = isPaused,
            autoPaused = autoPaused,
            gpsOk = hadFix && now - lastFixMs <= FIX_STALE_MS,
            lapNumber = laps.size + 1,
            lapMs = movingMs - lapStartMs,
            lapDistanceM = distanceM - lapStartM,
            lapAvgHr = if (lapHrN > 0) (lapHrSum / lapHrN).toInt() else null,
            structured = plan.structured,
            planDone = planDone,
            step = if (plan.structured) step else null,
            nextStep = if (plan.structured) plan.steps.getOrNull(stepIndex + 1) else null,
            stepNumber = stepIndex + 1,
            stepCount = plan.steps.size,
            stepElapsedMs = stepElapsed,
            stepDistanceM = stepDist,
            stepRemainingMs = remainingMs,
            stepRemainingM = remainingM,
            stepProgress = progress,
            targetStatus = targetStatus(now),
            aheadSec = ahead,
            race = plan.race?.let(::raceStatus),
            sport = plan.sport,
            altitudeM = altitude?.plus(altOffset),
            ascentM = ascentM,
            descentM = descentM,
            verticalSpeed = verticalSpeed(now),
            speedMps = speed,
            maxSpeedMps = maxSpeed,
            active = onRun || climbing,
            stateMs = movingMs - stateStartMs,
            runs = laps.size,
            lastLap = laps.lastOrNull(),
        )
    }

    // --- Altitude, ski, climbing -------------------------------------------

    /** Least-squares slope of the last minute of altitude, in m/h. */
    private fun verticalSpeed(now: Long): Int? {
        if (altSamples.size < 2 || now - altMs > ALT_STALE_MS) return null
        val t0 = altSamples.first().first
        if (altSamples.last().first - t0 < VSPEED_MIN_SPAN_MS) return null
        val n = altSamples.size
        val mt = altSamples.sumOf { (it.first - t0).toDouble() } / n
        val ma = altSamples.sumOf { it.second } / n
        var num = 0.0
        var den = 0.0
        for ((t, a) in altSamples) {
            val dt = t - t0 - mt
            num += dt * (a - ma)
            den += dt * dt
        }
        return if (den > 0) (num / den * 3_600_000).roundToInt() else null
    }

    /**
     * A run starts when, moving faster than [SKI_START_SPEED], the altitude is [SKI_START_DROP_M]
     * below the highest point since the last run (the run is dated from that point).
     * It ends at its lowest point once [SKI_LIFT_CLIMB_M] higher (a lift), or after [SKI_STILL_MS] still.
     */
    private fun skiAltitude(alt: Double) {
        if (!onRun) {
            val peak = peakAlt
            if (peak == null || alt >= peak) {
                setPeak(alt)
            } else if (peak - alt >= SKI_START_DROP_M && (speed ?: 0.0) >= SKI_START_SPEED) {
                onRun = true
                runTopAlt = peak
                runMinAlt = alt
                runMinMs = movingMs
                runMinM = distanceM
                runMaxSpeed = peakSpeed
                stillMs = 0
                beginLap(peakMs, peakM)
            }
            return
        }
        if (alt < runMinAlt) {
            runMinAlt = alt
            runMinMs = movingMs
            runMinM = distanceM
        }
        if (alt - runMinAlt >= SKI_LIFT_CLIMB_M) endRun(runMinMs, runMinM)
    }

    private fun setPeak(alt: Double?) {
        peakAlt = alt
        peakMs = movingMs
        peakM = distanceM
        peakSpeed = 0.0
    }

    private fun endRun(endMs: Long, endM: Double) {
        onRun = false
        stillMs = 0
        val drop = runTopAlt - runMinAlt
        if (drop >= SKI_MIN_DROP_M) {
            val lap = closeLap(labels.run(laps.size + 1), endMs, endM, ascent = 0.0, descent = drop, topSpeed = runMaxSpeed)
            pending += Event.RunDone(lap)
        }
        setPeak(altitude)
    }

    private fun toggleClimb(): Event? {
        if (isPaused || movingMs - lastToggleMs < 1_000) return null
        lastToggleMs = movingMs
        climbing = !climbing
        stateStartMs = movingMs
        if (climbing) {
            beginLap(movingMs, distanceM)
            return Event.ClimbToggled(true, null)
        }
        // A route under 3 s is an accidental double tap: back to resting, nothing counted.
        if (movingMs - lapStartMs < 3_000) return Event.ClimbToggled(false, null)
        return Event.ClimbToggled(false, closeLap(labels.climb(laps.size + 1)))
    }

    /** Starts lap accumulation at an earlier point (ski runs are dated from their top). */
    private fun beginLap(startMs: Long, startM: Double) {
        lapStartMs = startMs
        lapStartM = startM
        lapHrSum = 0
        lapHrN = 0
        lapHrMax = 0
        lapAscent0 = ascentM
        lapDescent0 = descentM
        lapMaxSpeed = 0.0
    }

    private fun raceStatus(race: Race): RaceStatus {
        val done = raceFinishMs
        val remaining = (race.meters - distanceM).coerceAtLeast(0.0)
        val needed = if (race.goalSec > 0 && done == null && remaining > 0) {
            val left = race.goalSec - movingMs / 1000.0
            if (left > 0) (left / (remaining / 1000)).roundToInt() else null
        } else null
        return RaceStatus(
            remainingM = if (done != null) 0.0 else remaining,
            finishMs = done ?: if (distanceM >= RACE_ESTIMATE_AFTER_M) (movingMs * race.meters / distanceM).toLong() else null,
            aheadSec = raceAhead(race),
            neededPace = needed,
            progress = if (done != null) 1f else (distanceM / race.meters).toFloat().coerceIn(0f, 1f),
            finished = done != null,
        )
    }

    /** Goal time scaled to the distance covered minus the time taken (frozen at the finish line). */
    private fun raceAhead(race: Race): Int? {
        if (race.goalSec <= 0) return null
        raceFinishMs?.let { return (race.goalSec - it / 1000.0).roundToInt() }
        if (distanceM < RACE_ESTIMATE_AFTER_M) return null
        return (race.goalSec * distanceM / race.meters - movingMs / 1000.0).roundToInt()
    }

    private fun advance(): List<Event> {
        val events = mutableListOf<Event>(Event.LapDone(closeLap(lapLabel()), auto = false, fromStep = true))
        stepIndex++
        stepStartMs = movingMs
        stepStartM = distanceM
        offSinceMs = null
        lastAlertMs = Long.MIN_VALUE / 2
        lastCountdown = 0
        val next = currentStep
        if (next != null) {
            events += Event.StepStarted(next)
        } else {
            // Plan finished: keep recording as a free run with auto-lap.
            nextAutoLapM = distanceM + plan.autoLapMeters
            events += Event.PlanDone
        }
        return events
    }

    private fun lapLabel(): String {
        val step = currentStep
        return when {
            !plan.structured || step == null -> labels.lap(laps.size + 1)
            step.reps > 0 -> "${labels.step(step.kind)} ${step.rep}/${step.reps}"
            else -> labels.step(step.kind)
        }
    }

    private fun closeLap(
        label: String,
        endMs: Long = movingMs,
        endM: Double = distanceM,
        ascent: Double = ascentM - lapAscent0,
        descent: Double = descentM - lapDescent0,
        topSpeed: Double = lapMaxSpeed,
    ): Lap {
        val lap = Lap(
            number = laps.size + 1,
            label = label,
            movingMs = endMs - lapStartMs,
            distanceM = endM - lapStartM,
            avgHr = if (lapHrN > 0) (lapHrSum / lapHrN).toInt() else null,
            maxHr = lapHrMax.takeIf { it > 0 },
            ascentM = ascent,
            descentM = descent,
            maxSpeedMps = topSpeed,
        )
        laps += lap
        beginLap(movingMs, distanceM)
        return lap
    }

    private fun targetStatus(now: Long): TargetStatus {
        if (isPaused) return TargetStatus.NONE
        return when (val t = currentStep?.target) {
            is Target.Pace -> {
                val p = paceOf(speed) ?: return TargetStatus.NONE
                when {
                    p < t.fastest -> TargetStatus.TOO_FAST
                    p > t.slowest -> TargetStatus.TOO_SLOW
                    else -> TargetStatus.OK
                }
            }
            is Target.Zone -> {
                val bpm = currentHr(now) ?: return TargetStatus.NONE
                val z = Zones.zoneOf(bpm, maxHr)
                when {
                    z < t.zone -> TargetStatus.HR_LOW
                    z > t.zone -> TargetStatus.HR_HIGH
                    else -> TargetStatus.OK
                }
            }
            else -> TargetStatus.NONE
        }
    }

    private fun currentHr(now: Long): Int? = hr?.takeIf { now - hrMs <= HR_STALE_MS }

    private fun cadence(now: Long): Int? {
        if (stepSamples.size < 2) return null
        val (t0, s0) = stepSamples.first()
        val (t1, s1) = stepSamples.last()
        if (now - t1 > CADENCE_WINDOW_MS || t1 - t0 < 5_000) return null
        return ((s1 - s0) * 60_000 / (t1 - t0)).toInt()
    }

    private fun paceOf(speed: Double?): Int? =
        if (speed == null || speed < MIN_SPEED) null else (1000 / speed).roundToInt()
}
