package dev.watchstrava.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A course to follow (from a GPX file). Points are kept both as lat/lon and in the same
 * local metre plane as [Track] (origin = first point), so the two can be drawn together.
 */
class Route(val name: String, latLon: List<Pair<Double, Double>>, val file: String = "") {
    val lat0: Double
    val lon0: Double
    private val mPerDegLon: Double
    private val xs: DoubleArray
    private val ys: DoubleArray
    /** Distance along the route at each point, metres (haversine). */
    val cumulative: DoubleArray
    val points: List<TrackPoint>

    init {
        require(latLon.size >= 2) { "A route needs at least 2 points" }
        lat0 = latLon[0].first
        lon0 = latLon[0].second
        mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(lat0))
        xs = DoubleArray(latLon.size) { (latLon[it].second - lon0) * mPerDegLon }
        ys = DoubleArray(latLon.size) { (latLon[it].first - lat0) * M_PER_DEG_LAT }
        cumulative = DoubleArray(latLon.size)
        for (i in 1 until latLon.size) {
            val (a, b) = latLon[i - 1] to latLon[i]
            cumulative[i] = cumulative[i - 1] + haversine(a.first, a.second, b.first, b.second)
        }
        points = List(latLon.size) { TrackPoint(xs[it].toFloat(), ys[it].toFloat()) }
    }

    val lengthM: Double get() = cumulative.last()

    /** Where a fix sits on the route: metres along it and metres off it. */
    data class Fix(val progressM: Double, val offM: Double)

    /**
     * Projects a position onto the nearest segment, looking only a little behind and [aheadM]
     * ahead of [lastProgressM], so out-and-back and loop courses don't jump to the other leg.
     * When that window is far away (> [LOST_M]) but another part of the route is close
     * (< [REJOIN_M]), the runner took a shortcut or started elsewhere: jump there, preferring
     * forward progress (and, on the very first fix, the earliest close candidate).
     */
    fun locate(lat: Double, lon: Double, lastProgressM: Double = 0.0, aheadM: Double = AHEAD_M): Fix {
        val x = (lon - lon0) * mPerDegLon
        val y = (lat - lat0) * M_PER_DEG_LAT
        val near = nearest(x, y, lastProgressM, lastProgressM - BEHIND_M, lastProgressM + aheadM)
        if (near.offM <= LOST_M) return near
        val any = nearest(x, y, lastProgressM, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, preferEarly = lastProgressM == 0.0)
        return if (any.offM < REJOIN_M) any else near
    }

    /**
     * Closest point of the segments overlapping [from]..[to]. Going backwards from [last] costs
     * a little, so where two legs share the road (turnaround of an out-and-back) the runner moves on.
     */
    private fun nearest(x: Double, y: Double, last: Double, from: Double, to: Double, preferEarly: Boolean = false): Fix {
        var best = Fix(0.0, Double.MAX_VALUE)
        var bestScore = Double.MAX_VALUE
        for (i in 0 until xs.size - 1) {
            if (cumulative[i + 1] < from || cumulative[i] > to) continue
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((x - xs[i]) * dx + (y - ys[i]) * dy) / len2).coerceIn(0.0, 1.0)
            val off = hypot(x - (xs[i] + t * dx), y - (ys[i] + t * dy))
            val progress = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            val score = off + BACKWARD_COST * (last - progress).coerceAtLeast(0.0) +
                if (preferEarly) EARLY_COST * progress else 0.0
            if (score < bestScore) {
                bestScore = score
                best = Fix(progress, off)
            }
        }
        return best
    }

    companion object {
        private const val M_PER_DEG_LAT = 111_320.0
        private const val EARTH_R = 6_371_000.0
        const val BEHIND_M = 50.0
        const val AHEAD_M = 300.0
        const val LOST_M = 100.0
        const val REJOIN_M = 50.0
        private const val BACKWARD_COST = 0.5
        private const val EARLY_COST = 0.02

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
            return 2 * EARTH_R * asin(sqrt(a.coerceAtMost(1.0)))
        }
    }
}
