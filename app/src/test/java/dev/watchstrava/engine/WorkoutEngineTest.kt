package dev.watchstrava.engine

import dev.watchstrava.model.Plan
import dev.watchstrava.model.StepKind
import dev.watchstrava.model.StepLength
import dev.watchstrava.model.Target
import dev.watchstrava.model.Zones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutEngineTest {

    private var now = 1_000_000L

    /** Runs [seconds] at constant [speed] m/s with [bpm], one GPS fix and tick per second. */
    private fun WorkoutEngine.run(seconds: Int, speed: Double, bpm: Int? = null): List<WorkoutEngine.Event> {
        val events = mutableListOf<WorkoutEngine.Event>()
        repeat(seconds) {
            now += 1000
            onLocation(now, speed, speed)
            bpm?.let { onHeartRate(now, it) }
            events += tick(now)
        }
        return events
    }

    private fun engine(plan: Plan, autoPause: Boolean = false) =
        WorkoutEngine(plan, maxHr = 190, autoPause = autoPause).also { it.start(now) }

    @Test
    fun freeRunAutoLapsEveryKilometre() {
        val e = engine(Plan.free(autoLapMeters = 1000))
        val laps = e.run(600, speed = 4.0).filterIsInstance<WorkoutEngine.Event.LapDone>()

        assertEquals(2, laps.size) // 2400 m
        assertTrue(laps.all { it.auto })
        assertEquals(250, laps[0].lap.pace) // 4 m/s = 4:10 /km
        assertEquals(2400.0, e.snapshot(now).distanceM, 0.01)
        assertEquals(250, e.snapshot(now).pace)
    }

    @Test
    fun intervalsAdvanceByDistanceAndTime() {
        val plan = Plan.intervals(
            warmupMin = 1, reps = 2,
            work = StepLength.Distance(400), workTarget = Target.None,
            recovery = StepLength.Time(60), cooldownMin = 0,
        )
        assertEquals(listOf(StepKind.WARMUP, StepKind.WORK, StepKind.RECOVERY, StepKind.WORK), plan.steps.map { it.kind })

        val e = engine(plan)
        val events = e.run(60 + 100 + 60 + 100, speed = 4.0)
        val started = events.filterIsInstance<WorkoutEngine.Event.StepStarted>().map { it.step.kind }

        assertEquals(listOf(StepKind.WORK, StepKind.RECOVERY, StepKind.WORK), started)
        assertEquals(WorkoutEngine.Event.PlanDone, events.last())
        val summary = e.finish()
        assertEquals(listOf("Riscaldamento", "Veloce 1/2", "Recupero 1/2", "Veloce 2/2"), summary.laps.map { it.label })
        assertEquals(400.0, summary.laps[1].distanceM, 4.01)
    }

    @Test
    fun countdownInLastThreeSecondsOfTimedStep() {
        val e = engine(Plan.fartlek(warmupMin = 0, cycles = 1, fastSec = 10, easySec = 10, cooldownMin = 0))
        val countdown = e.run(10, speed = 3.0).filterIsInstance<WorkoutEngine.Event.Countdown>().map { it.seconds }
        assertEquals(listOf(3, 2, 1), countdown)
    }

    @Test
    fun lapButtonEndsOpenWarmup() {
        val e = engine(Plan.intervals(Plan.UNTIL_LAP, 1, StepLength.Time(30), Target.None, StepLength.Time(30), 0))
        e.run(300, speed = 3.0)
        assertEquals(StepKind.WARMUP, e.snapshot(now).step?.kind)
        val events = e.lap()
        assertTrue(events.any { it is WorkoutEngine.Event.StepStarted && it.step.kind == StepKind.WORK })
    }

    @Test
    fun paceAlertAfterGraceAndThrottled() {
        // Target 4:00–4:20 /km, running 5:00 /km (3.33 m/s): too slow.
        val e = engine(Plan.free(Target.Pace(240, 260), autoLapMeters = 0))
        val alerts = e.run(70, speed = 1000.0 / 300).filterIsInstance<WorkoutEngine.Event.OffTarget>()
        // First alert after the 15 s grace, then every 30 s: t=15 and t=45.
        assertEquals(2, alerts.size)
        assertEquals(TargetStatus.TOO_SLOW, alerts[0].status)
    }

    @Test
    fun noAlertWhenOnTarget() {
        val e = engine(Plan.free(Target.Pace(240, 260), autoLapMeters = 0))
        assertTrue(e.run(120, speed = 1000.0 / 250).none { it is WorkoutEngine.Event.OffTarget })
        assertEquals(TargetStatus.OK, e.snapshot(now).targetStatus)
    }

    @Test
    fun heartRateZoneTarget() {
        val e = engine(Plan.free(Target.Zone(2), autoLapMeters = 0))
        val alerts = e.run(30, speed = 3.0, bpm = 165).filterIsInstance<WorkoutEngine.Event.OffTarget>()
        assertEquals(TargetStatus.HR_HIGH, alerts.single().status)
        assertEquals(4, e.snapshot(now).zone)
    }

    @Test
    fun autoPauseStopsMovingTime() {
        val e = engine(Plan.free(autoLapMeters = 0), autoPause = true)
        e.run(10, speed = 3.0)
        val pauseEvents = e.run(20, speed = 0.0)
        assertTrue(pauseEvents.contains(WorkoutEngine.Event.AutoPaused(true)))
        val moving = e.snapshot(now).movingMs
        assertTrue("moving=$moving", moving in 12_000..14_000)
        val resume = e.run(5, speed = 3.0)
        assertTrue(resume.contains(WorkoutEngine.Event.AutoPaused(false)))
    }

    @Test
    fun gpsGapDoesNotAutoPause() {
        val e = engine(Plan.free(autoLapMeters = 0), autoPause = true)
        e.run(10, speed = 3.0)
        val events = mutableListOf<WorkoutEngine.Event>()
        repeat(15) { now += 1000; events += e.tick(now) } // no fixes: tunnel
        assertTrue(events.none { it is WorkoutEngine.Event.AutoPaused })
        assertEquals(25_000L, e.snapshot(now).movingMs)
    }

    @Test
    fun autoPausedDistanceIsNotCounted() {
        val e = engine(Plan.free(autoLapMeters = 0), autoPause = true)
        e.run(10, speed = 3.0)
        e.run(10, speed = 0.0)
        val d = e.snapshot(now).distanceM
        repeat(5) { now += 1000; e.onLocation(now, 3.0, 0.8); e.tick(now) } // GPS drift while stopped
        assertEquals(d, e.snapshot(now).distanceM, 0.01)
    }

    @Test
    fun manualLapAfterPlanDoneIsNotAStepLap() {
        val e = engine(Plan.fartlek(0, 1, 10, 10, 0))
        e.run(25, speed = 3.0)
        val lap = e.lap().filterIsInstance<WorkoutEngine.Event.LapDone>().single()
        assertTrue(!lap.fromStep)
    }

    @Test
    fun zoneBoundsMatchDisplayedRange() {
        for (max in 150..210) for (z in 1..5) {
            assertEquals("max=$max z=$z", z, Zones.zoneOf(Zones.lower(z, max), max))
        }
    }

    @Test
    fun aheadOfVirtualPacer() {
        // Target middle 5:00 /km, running 4:00 /km: 1 km in 250 s instead of 300 s.
        val e = engine(Plan.intervals(0, 1, StepLength.Distance(2000), Target.Pace(290, 310), StepLength.Time(60), 0))
        e.run(250, speed = 4.0)
        assertEquals(50, e.snapshot(now).aheadSec)
    }

    @Test
    fun zoneMath() {
        assertEquals(0, Zones.zoneOf(90, 190))
        assertEquals(1, Zones.zoneOf(95, 190))
        assertEquals(3, Zones.zoneOf(140, 190))
        assertEquals(5, Zones.zoneOf(185, 190))
        assertEquals(152, Zones.lower(4, 190))
        assertEquals(171, Zones.upper(4, 190))
    }

    @Test
    fun staleHeartRateIsDropped() {
        val e = engine(Plan.free())
        e.onHeartRate(now, 150)
        e.run(11, speed = 3.0)
        assertNull(e.snapshot(now).heartRate)
    }
}
