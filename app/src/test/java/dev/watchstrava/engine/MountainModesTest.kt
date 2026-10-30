package dev.watchstrava.engine

import dev.watchstrava.model.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** Barometric altitude, ski run detection and climbing routes. */
class MountainModesTest {

    private var now = 1_000_000L
    private var alt = 2000.0

    private val labels = LapLabels(
        lap = { "Giro $it" },
        step = { it.name },
        run = { "Discesa $it" },
        climb = { "Via $it" },
    )

    private fun engine(plan: Plan) =
        WorkoutEngine(plan, maxHr = 190, autoPause = false, labels = labels).also { it.start(now) }

    /**
     * [seconds] of one GPS fix, one altitude sample and one tick per second, moving at [speed] m/s
     * while the altitude changes by [climb] m/s (plus optional [noise] on top).
     */
    private fun WorkoutEngine.go(
        seconds: Int,
        speed: Double,
        climb: Double = 0.0,
        bpm: Int? = null,
        gps: Boolean = true,
        noise: (Int) -> Double = { 0.0 },
    ): List<WorkoutEngine.Event> {
        val events = mutableListOf<WorkoutEngine.Event>()
        repeat(seconds) { i ->
            now += 1000
            alt += climb
            if (gps) onLocation(now, speed, speed)
            onAltitude(now, alt + noise(i))
            bpm?.let { onHeartRate(now, it) }
            events += tick(now)
        }
        return events
    }

    // --- Altitude ------------------------------------------------------------

    @Test
    fun ascentAndDescentIgnoreSmallWobbles() {
        val e = engine(Plan.hike(autoLapMeters = 0))
        // ±2 m of noise on flat ground: below the 3 m hysteresis.
        e.go(300, speed = 1.2) { 2.0 * sin(it * 0.7) }
        assertEquals(0.0, e.snapshot(now).ascentM, 0.01)
        assertEquals(0.0, e.snapshot(now).descentM, 0.01)

        e.go(200, speed = 1.0, climb = 0.5) // +100 m
        e.go(100, speed = 1.0, climb = -0.4) // −40 m
        val s = e.snapshot(now)
        assertEquals(100.0, s.ascentM, 3.0)
        assertEquals(40.0, s.descentM, 3.0)
        assertEquals(2060.0, s.altitudeM!!, 0.01)
    }

    @Test
    fun verticalSpeedOverTheLastMinute() {
        val e = engine(Plan.hike(autoLapMeters = 0))
        e.go(10, speed = 1.0, climb = 0.2)
        assertNull("too few samples", e.snapshot(now).verticalSpeed)
        e.go(80, speed = 1.0, climb = 0.2) // 0.2 m/s = 720 m/h
        assertEquals(720.0, e.snapshot(now).verticalSpeed!!.toDouble(), 5.0)
        e.go(90, speed = 1.0, climb = -0.1)
        assertEquals(-360.0, e.snapshot(now).verticalSpeed!!.toDouble(), 5.0)
    }

    @Test
    fun altitudeOffsetShiftsDisplayOnly() {
        val e = engine(Plan.hike(autoLapMeters = 0))
        e.go(30, speed = 1.0)
        e.setAltitudeOffset(-120.0)
        e.go(30, speed = 1.0)
        val s = e.snapshot(now)
        assertEquals(1880.0, s.altitudeM!!, 0.01)
        assertEquals(0.0, s.ascentM + s.descentM, 0.01)
        assertEquals(1880.0, e.finish().maxAltitudeM!!, 0.01)
    }

    @Test
    fun noBarometerNoAltitude() {
        val e = engine(Plan.free())
        repeat(30) { now += 1000; e.onLocation(now, 3.0, 3.0); e.tick(now) }
        assertNull(e.snapshot(now).altitudeM)
        assertEquals(0.0, e.finish().ascentM, 0.0)
    }

    // --- Ski -------------------------------------------------------------------

    @Test
    fun descentLiftDescentGivesTwoRuns() {
        val e = engine(Plan.ski())
        val events = buildList {
            addAll(e.go(10, speed = 0.0)) // at the top
            addAll(e.go(150, speed = 12.0, climb = -2.0)) // run 1: −300 m
            addAll(e.go(240, speed = 5.0, climb = 1.5)) // chairlift: +360 m
            addAll(e.go(100, speed = 14.0, climb = -2.5)) // run 2: −250 m
            addAll(e.go(240, speed = 5.0, climb = 1.5)) // lift again
        }
        val runs = events.filterIsInstance<WorkoutEngine.Event.RunDone>().map { it.lap }
        assertEquals(2, runs.size)
        assertEquals(listOf("Discesa 1", "Discesa 2"), runs.map { it.label })
        assertEquals(300.0, runs[0].descentM, 1.0)
        assertEquals(250.0, runs[1].descentM, 1.0)
        // Dated from the top to the bottom: lift time is not in the run.
        assertEquals(150_000.0, runs[0].movingMs.toDouble(), 2_000.0)
        assertEquals(1800.0, runs[0].distanceM, 30.0)
        assertEquals(14.0, runs[1].maxSpeedMps, 0.5)

        val summary = e.finish()
        assertEquals(2, summary.laps.size) // not on a run at the end: nothing more
        assertEquals(550.0, summary.descentM, 6.0)
        assertEquals(14.0, summary.maxSpeedMps, 0.5)
    }

    @Test
    fun noiseAndSlowWalkingDoNotCreateRuns() {
        val e = engine(Plan.ski())
        // Fast on the flat with ±6 m of altitude noise.
        val a = e.go(300, speed = 6.0) { 6.0 * sin(it * 0.9) }
        // Walking down 60 m slowly (below 10 km/h).
        val b = e.go(120, speed = 1.5, climb = -0.5)
        assertTrue((a + b).none { it is WorkoutEngine.Event.RunDone })
        assertFalse(e.snapshot(now).active)
        assertEquals(0, e.finish().laps.size)
    }

    @Test
    fun liftDipIsNotARun() {
        val e = engine(Plan.ski())
        e.go(60, speed = 5.0, climb = 1.5)
        // The gondola dips 20 m, then climbs again: too small a drop to be a run.
        val events = e.go(10, speed = 5.0, climb = -2.0) + e.go(60, speed = 5.0, climb = 1.5)
        assertTrue(events.none { it is WorkoutEngine.Event.RunDone })
        assertEquals(0, e.finish().laps.size)
    }

    @Test
    fun standingStillEndsTheRun() {
        val e = engine(Plan.ski())
        e.go(5, speed = 0.0)
        e.go(60, speed = 10.0, climb = -1.5) // −90 m
        assertTrue(e.snapshot(now).active)
        val stop = e.go(200, speed = 0.0) // at the bottom, queueing
        val run = stop.filterIsInstance<WorkoutEngine.Event.RunDone>().single().lap
        assertFalse(e.snapshot(now).active)
        assertEquals(90.0, run.descentM, 1.0)
        // Ends where the stop began (plus a few seconds of speed smoothing), not 60 s later.
        assertTrue("ms=${run.movingMs}", run.movingMs in 60_000..68_000)
    }

    @Test
    fun shortMidRunStopDoesNotSplitTheRun() {
        val e = engine(Plan.ski())
        e.go(5, speed = 0.0)
        val a = e.go(30, speed = 10.0, climb = -1.5)
        val stop = e.go(70, speed = 0.0) // a break on the slope
        val b = e.go(30, speed = 10.0, climb = -1.5)
        assertTrue((a + stop + b).none { it is WorkoutEngine.Event.RunDone })
        assertTrue(e.snapshot(now).active)
        val laps = e.finish().laps
        assertEquals(1, laps.size)
        assertEquals(90.0, laps[0].descentM, 1.0)
    }

    @Test
    fun gpsSpeedSpikeIsIgnored() {
        val e = engine(Plan.free())
        e.go(10, speed = 5.0)
        val dist = e.snapshot(now).distanceM
        now += 1000
        e.onLocation(now, 500.0, 200.0)
        e.go(5, speed = 5.0)
        val s = e.finish()
        assertEquals(5.0, s.maxSpeedMps, 0.1)
        assertEquals(dist + 25.0, s.distanceM, 0.01)
    }

    @Test
    fun runInProgressIsClosedAtFinishAndLapButtonDoesNothing() {
        val e = engine(Plan.ski())
        e.go(5, speed = 0.0)
        e.go(40, speed = 12.0, climb = -2.0)
        assertTrue(e.lap().isEmpty())
        val laps = e.finish().laps
        assertEquals(1, laps.size)
        assertEquals("Discesa 1", laps[0].label)
    }

    // --- Climbing --------------------------------------------------------------

    @Test
    fun lapTogglesClimbingAndResting() {
        val e = engine(Plan.climb(outdoor = false))
        e.go(60, speed = 0.0, gps = false, bpm = 100) // warming up: resting
        assertFalse(e.snapshot(now).active)

        val start = e.lap().single() as WorkoutEngine.Event.ClimbToggled
        assertTrue(start.climbing)
        assertNull(start.lap)
        e.go(120, speed = 0.0, gps = false, bpm = 160)
        assertTrue(e.snapshot(now).active)
        assertEquals(120_000L, e.snapshot(now).stateMs)

        val end = e.lap().single() as WorkoutEngine.Event.ClimbToggled
        assertFalse(end.climbing)
        assertEquals("Via 1", end.lap!!.label)
        assertEquals(120_000L, end.lap!!.movingMs)
        assertEquals(160, end.lap!!.avgHr)

        e.go(180, speed = 0.0, gps = false, bpm = 110) // rest: not a lap
        assertEquals(180_000L, e.snapshot(now).stateMs)
        assertEquals(1, e.snapshot(now).runs)

        e.lap()
        e.go(90, speed = 0.0, gps = false, bpm = 150) // still climbing at the end
        val s = e.finish()
        assertEquals(listOf("Via 1", "Via 2"), s.laps.map { it.label })
        assertEquals(210_000L, s.lapsMs)
        assertEquals(240_000L, s.movingMs - s.lapsMs) // resting
        assertEquals(120_000L, s.longestLapMs)
    }

    @Test
    fun accidentalClimbTogglesAreIgnoredOrNotCounted() {
        val e = engine(Plan.climb(outdoor = false))
        e.go(10, speed = 0.0, gps = false)
        assertTrue((e.lap().single() as WorkoutEngine.Event.ClimbToggled).climbing)
        assertTrue("debounce", e.lap().isEmpty())
        e.go(2, speed = 0.0, gps = false)
        val end = e.lap().single() as WorkoutEngine.Event.ClimbToggled // route under 3 s
        assertFalse(end.climbing)
        assertNull(end.lap)
        assertEquals(0, e.snapshot(now).runs)

        e.go(5, speed = 0.0, gps = false)
        e.pause()
        assertTrue("paused", e.lap().isEmpty())
        assertEquals(0, e.finish().laps.size)
    }

    @Test
    fun outdoorRouteRecordsAscent() {
        val e = engine(Plan.climb(outdoor = true))
        e.go(30, speed = 0.0)
        e.lap()
        e.go(300, speed = 0.2, climb = 0.15) // a 45 m pitch
        val lap = (e.lap().single() as WorkoutEngine.Event.ClimbToggled).lap!!
        assertEquals(45.0, lap.ascentM, 3.0)
    }
}
