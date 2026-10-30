package dev.watchstrava.model

import kotlin.math.cos
import kotlin.math.hypot

/** A point of the run, in metres east (x) / north (y) of the first fix. */
data class TrackPoint(val x: Float, val y: Float)

/**
 * GPS breadcrumb for the map page. Projects lat/lon to a local flat plane
 * (fine for a run's few km) and keeps a point every [spacingM] metres.
 */
class Track(private val spacingM: Double = 5.0, private val maxPoints: Int = 8_000) {
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var mPerDegLon = 0.0
    private var anchored = false
    private val points = ArrayList<TrackPoint>()

    /** Fixes the plane's origin (e.g. a route's start) instead of using the first fix. */
    fun anchor(lat: Double, lon: Double) {
        lat0 = lat
        lon0 = lon
        mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(lat))
        anchored = true
    }

    fun add(lat: Double, lon: Double) {
        if (!anchored) anchor(lat, lon)
        val p = TrackPoint(((lon - lon0) * mPerDegLon).toFloat(), ((lat - lat0) * M_PER_DEG_LAT).toFloat())
        val last = points.lastOrNull()
        if (last != null && hypot((p.x - last.x).toDouble(), (p.y - last.y).toDouble()) < spacingM) return
        points += p
        // Very long runs: halve the resolution instead of growing without bound.
        if (points.size > maxPoints) {
            val kept = points.filterIndexed { i, _ -> i % 2 == 0 || i == points.lastIndex }
            points.clear()
            points.addAll(kept)
        }
    }

    fun snapshot(): List<TrackPoint> = points.toList()

    private companion object {
        const val M_PER_DEG_LAT = 111_320.0
    }
}

/** Square window (side [spanM] metres) centred on the track, for drawing it north-up. */
data class TrackFit(val centerX: Float, val centerY: Float, val spanM: Float) {
    companion object {
        /** Never zoom in closer than this, so the first metres don't fill the screen. */
        const val MIN_SPAN_M = 150f
        private val NICE = listOf(10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000)

        fun of(points: List<TrackPoint>): TrackFit {
            if (points.isEmpty()) return TrackFit(0f, 0f, MIN_SPAN_M)
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val span = maxOf(maxX - minX, maxY - minY, MIN_SPAN_M) * 1.1f
            return TrackFit((minX + maxX) / 2, (minY + maxY) / 2, span)
        }

        /** Largest round length (10 m, 20 m, 50 m…) not longer than [maxM]. */
        fun niceScale(maxM: Float): Int = NICE.lastOrNull { it <= maxM } ?: NICE.first()
    }
}
