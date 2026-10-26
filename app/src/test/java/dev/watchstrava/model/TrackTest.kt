package dev.watchstrava.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTest {

    @Test
    fun projectsToMetresAndSkipsJitter() {
        val t = Track(spacingM = 5.0)
        t.add(45.0, 9.0)
        t.add(45.00001, 9.0) // ~1 m north: dropped
        t.add(45.001, 9.0) // ~111 m north
        val pts = t.snapshot()
        assertEquals(2, pts.size)
        assertEquals(0f, pts[1].x, 0.01f)
        assertEquals(111.3f, pts[1].y, 0.5f)
    }

    @Test
    fun eastWestShrinksWithLatitude() {
        val t = Track()
        t.add(60.0, 10.0)
        t.add(60.0, 10.001) // cos(60°) = 0.5 → ~55.7 m
        assertEquals(55.7f, t.snapshot()[1].x, 0.5f)
    }

    @Test
    fun capsPointsByHalving() {
        val t = Track(spacingM = 1.0, maxPoints = 100)
        repeat(150) { t.add(45.0 + it * 0.0001, 9.0) }
        val pts = t.snapshot()
        assertEquals(true, pts.size <= 100)
        assertEquals(149 * 11.132f, pts.last().y, 1f) // newest point kept
    }

    @Test
    fun fitCentresAndKeepsMinimumSpan() {
        val small = TrackFit.of(listOf(TrackPoint(0f, 0f), TrackPoint(10f, 20f)))
        assertEquals(5f, small.centerX)
        assertEquals(10f, small.centerY)
        assertEquals(TrackFit.MIN_SPAN_M * 1.1f, small.spanM, 0.01f)

        val big = TrackFit.of(listOf(TrackPoint(-1000f, 0f), TrackPoint(1000f, 300f)))
        assertEquals(2200f, big.spanM, 0.01f)
    }

    @Test
    fun niceScale() {
        assertEquals(50, TrackFit.niceScale(73f))
        assertEquals(1000, TrackFit.niceScale(1999f))
        assertEquals(10, TrackFit.niceScale(3f))
    }
}
