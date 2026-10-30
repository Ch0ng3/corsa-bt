package dev.watchstrava.engine

import dev.watchstrava.model.Format
import dev.watchstrava.model.Plan
import dev.watchstrava.model.Race
import dev.watchstrava.model.Step
import dev.watchstrava.model.StepKind
import dev.watchstrava.model.StepLength
import dev.watchstrava.model.Target
import dev.watchstrava.model.Zones
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class TargetStatus { NONE, OK, TOO_FAST, TOO_SLOW, HR_LOW, HR_HIGH }

/** Words for lap labels, supplied by the caller so the engine needs no Android resources. */
class LapLabels(val lap: (number: Int) -> String, val step: (StepKind) -> String)

data class Lap(
    val number: Int,
    val label: String,
    val movingMs: Long,
    val distanceM: Double,
    val avgHr: Int?,
    val maxHr: Int?,
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
) {
    val pace: Int? get() = Format.paceOf(distanceM, movingMs)
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
        if (!started) return
        if (!isPaused) distanceM += deltaM
        speed = speed?.let { it * 0.7 + speedMps * 0.3 } ?: speedMps
        rawSpeed = speedMps
        lastFixMs = now
        hadFix = true
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

    /** GIRO: next step in a structured plan, otherwise a manual lap. */
    fun lap(): List<Event> {
        if (!started) return emptyList()
        if (plan.structured && !planDone) return advance()
        nextAutoLapM = distanceM + plan.autoLapMeters
        return listOf(Event.LapDone(closeLap(lapLabel()), auto = false))
    }

    fun tick(now: Long): List<Event> {
        if (!started) return emptyList()
        val events = mutableListOf<Event>()
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
        if (movingMs - lapStartMs >= 1_000) closeLap(lapLabel())
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
        )
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

    private fun closeLap(label: String): Lap {
        val lap = Lap(
            number = laps.size + 1,
            label = label,
            movingMs = movingMs - lapStartMs,
            distanceM = distanceM - lapStartM,
            avgHr = if (lapHrN > 0) (lapHrSum / lapHrN).toInt() else null,
            maxHr = lapHrMax.takeIf { it > 0 },
        )
        laps += lap
        lapStartMs = movingMs
        lapStartM = distanceM
        lapHrSum = 0
        lapHrN = 0
        lapHrMax = 0
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
