package dev.watchstrava.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    // ~111.2 m per 0.001° of latitude; at 45° N 0.001° of longitude is ~78.7 m.
    private val lat = 45.0
    private val lon = 9.0
    private val degLat = 0.001 / 111.19 // degrees per metre north (haversine earth)

    private fun north(m: Double) = lat + m * degLat

    @Test
    fun haversineMatchesKnownDistance() {
        assertEquals(111.19, Route.haversine(45.0, 9.0, 45.001, 9.0), 0.05)
    }

    @Test
    fun straightLine() {
        val r = Route("line", listOf(lat to lon, north(1000.0) to lon))
        assertEquals(1000.0, r.lengthM, 0.5)
        val fix = r.locate(north(400.0), lon)
        assertEquals(400.0, fix.progressM, 1.0)
        assertEquals(0.0, fix.offM, 0.5)
        // Beyond the end clamps to the finish.
        assertEquals(1000.0, r.locate(north(1050.0), lon, lastProgressM = 900.0).progressM, 1.0)
    }

    @Test
    fun lShape() {
        // 500 m north, then 0.005° east (~393 m at 45°).
        val corner = north(500.0) to lon
        val r = Route("L", listOf(lat to lon, corner, corner.first to lon + 0.005))
        val east = Route.haversine(corner.first, lon, corner.first, lon + 0.005)
        assertEquals(500.0 + east, r.lengthM, 1.0)
        val fix = r.locate(corner.first, lon + 0.0025, lastProgressM = 450.0)
        assertEquals(500.0 + east / 2, fix.progressM, 2.0)
        assertEquals(0.0, fix.offM, 1.0)
    }

    @Test
    fun outAndBackDoesNotJump() {
        // 1 km out, back on the same road.
        val r = Route("oab", listOf(lat to lon, north(1000.0) to lon, lat to lon))
        assertEquals(2000.0, r.lengthM, 1.0)
        var progress = 0.0
        for (m in 0..1000 step 20) progress = r.locate(north(m.toDouble()), lon, progress).progressM
        assertEquals(1000.0, progress, 2.0)
        // Coming back past the 300 m mark is 1700 m along, not 300 m.
        for (m in 1000 downTo 300 step 20) progress = r.locate(north(m.toDouble()), lon, progress).progressM
        assertEquals(1700.0, progress, 2.0)
        // From the start the outbound leg wins.
        assertEquals(300.0, r.locate(north(300.0), lon, 280.0).progressM, 2.0)
    }

    private fun dense(vararg ms: Int) = ms.map { north(it.toDouble()) to lon }

    @Test
    fun rejoinAfterGapStaysOnReturnLeg() {
        // 1 km out and back, a point every 100 m.
        val r = Route("oab", dense(0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 900, 800, 700, 600, 500, 400, 300, 200, 100, 0))
        // At 1300 m (north 700 on the way back), GPS gap, next fix 600 m further along at north 200.
        val fix = r.locate(north(200.0), lon, lastProgressM = 1300.0)
        assertEquals(1800.0, fix.progressM, 2.0)
    }

    @Test
    fun firstFixPrefersEarlyCandidate() {
        val r = Route("oab", dense(0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 900, 800, 700, 600, 500, 400, 300, 200, 100, 0))
        // First fix 200 m beyond the start window: outbound (500 m) and return (1500 m) are both close.
        assertEquals(500.0, r.locate(north(500.0), lon, lastProgressM = 0.0).progressM, 2.0)
    }

    @Test
    fun offRouteDistance() {
        val r = Route("line", listOf(lat to lon, north(1000.0) to lon))
        // 0.001° east at 45° ≈ 78.7 m.
        val fix = r.locate(north(500.0), lon + 0.001, lastProgressM = 480.0)
        assertEquals(78.7, fix.offM, 1.0)
        assertEquals(500.0, fix.progressM, 2.0)
    }

    @Test
    fun rejoinsElsewhereWhenFarFromWindow() {
        val r = Route("line", listOf(lat to lon, north(3000.0) to lon))
        // Started 2 km along the route: the window around 0 is far, the route itself is close.
        val fix = r.locate(north(2000.0), lon + 0.0001)
        assertEquals(2000.0, fix.progressM, 2.0)
        assertTrue(fix.offM < 10)
    }

    @Test
    fun pointsShareTrackPlane() {
        val r = Route("line", listOf(lat to lon, north(100.0) to lon + 0.001))
        val t = Track().apply { anchor(r.lat0, r.lon0); add(north(100.0), lon + 0.001) }
        assertEquals(r.points[1].x, t.snapshot()[0].x, 0.01f)
        assertEquals(r.points[1].y, t.snapshot()[0].y, 0.01f)
    }
}
