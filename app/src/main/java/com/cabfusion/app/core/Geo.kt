package com.cabfusion.app.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS-84 coordinate. Kept free of Android types so the engine is unit-testable on the JVM. */
data class GeoPoint(val lat: Double, val lng: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lng - a.lng)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from a to b in degrees, 0..360. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lng - a.lng)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Total length of a polyline in metres. */
    fun lengthM(path: List<GeoPoint>): Double =
        path.zipWithNext().sumOf { (a, b) -> distanceM(a, b) }

    /** Re-sample a polyline so consecutive points are at most [stepM] metres apart. */
    fun resample(path: List<GeoPoint>, stepM: Double): List<GeoPoint> {
        if (path.size < 2) return path
        val out = ArrayList<GeoPoint>()
        out += path.first()
        for ((a, b) in path.zipWithNext()) {
            val d = distanceM(a, b)
            val n = (d / stepM).toInt()
            for (i in 1..n) {
                val t = i * stepM / d
                if (t < 1.0) out += GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            }
            out += b
        }
        return out
    }

    /** Shortest distance from point p to the polyline, approximated on a local flat projection. */
    fun distanceToPathM(p: GeoPoint, path: List<GeoPoint>): Double {
        if (path.isEmpty()) return Double.MAX_VALUE
        if (path.size == 1) return distanceM(p, path[0])
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(p.lat))
        var best = Double.MAX_VALUE
        for ((a, b) in path.zipWithNext()) {
            val ax = (a.lng - p.lng) * mPerDegLng; val ay = (a.lat - p.lat) * mPerDegLat
            val bx = (b.lng - p.lng) * mPerDegLng; val by = (b.lat - p.lat) * mPerDegLat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            val d = sqrt(cx * cx + cy * cy)
            if (d < best) best = d
        }
        return best
    }
}
