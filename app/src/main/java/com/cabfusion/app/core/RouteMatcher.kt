package com.cabfusion.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** One rider's trip as the matcher sees it. [route] is the road route from pickup to drop. */
data class TripPlan(
    val id: String,
    val riderName: String,
    val pickup: GeoPoint,
    val drop: GeoPoint,
    val route: List<GeoPoint>,
    val routeDistanceM: Double,
    val departureEpochMin: Long,
)

/** Explainable result: every factor that went into the score is kept. */
data class MatchResult(
    val candidate: TripPlan,
    val score: Int,                 // 0..100, shown to the rider as "92% match"
    val overlap: Double,            // share of the shorter route that runs alongside the other, 0..1
    val directionSimilarity: Double,// cosine of the angle between trip directions, 0..1
    val pickupGapM: Double,
    val detourRatioMine: Double,    // shared in-cab distance / solo distance for the requester
    val detourRatioTheirs: Double,
    val sharedPathM: Double,        // estimated length of the pooled trip
    val dropOrderMineFirst: Boolean,
)

/**
 * Route-similarity matching engine.
 *
 * A candidate is compatible only if it passes four hard gates (time window, pickup gap,
 * direction, detour). Compatible candidates are then scored on a weighted mix of route
 * overlap, direction similarity, pickup proximity and detour, and returned best first.
 */
class RouteMatcher(private val cfg: Config = Config()) {

    data class Config(
        val maxPickupGapM: Double = 2_500.0,
        val corridorM: Double = 400.0,      // a point "lies on" a route if within this distance
        val sampleStepM: Double = 100.0,
        val maxDetourRatio: Double = 1.45,  // nobody rides more than 45 % further than solo
        val minDirectionSim: Double = 0.5,  // cos 60°
        val timeWindowMin: Long = 30,
        val roadFactor: Double = 1.3,       // straight-line → road distance for un-routed legs
        val minScore: Int = 40,
        val maxGroupSize: Int = 3,
        val wOverlap: Double = 0.45,
        val wDirection: Double = 0.20,
        val wPickup: Double = 0.15,
        val wDetour: Double = 0.20,
    )

    fun rank(me: TripPlan, pool: List<TripPlan>): List<MatchResult> =
        pool.asSequence()
            .filter { it.id != me.id }
            .mapNotNull { score(me, it) }
            .filter { it.score >= cfg.minScore }
            .sortedByDescending { it.score }
            .toList()

    fun score(me: TripPlan, other: TripPlan): MatchResult? {
        if (abs(me.departureEpochMin - other.departureEpochMin) > cfg.timeWindowMin) return null
        val pickupGap = Geo.distanceM(me.pickup, other.pickup)
        if (pickupGap > cfg.maxPickupGapM) return null

        val dirSim = directionSimilarity(me, other)
        if (dirSim < cfg.minDirectionSim) return null

        // Try every pickup/drop order for the pair and keep the one with the smallest worst-case
        // detour; that is the order the driver is given.
        val road = cfg.roadFactor
        val mySolo = max(me.routeDistanceM, 1.0)
        val theirSolo = max(other.routeDistanceM, 1.0)
        var best: Plan? = null
        for (mePickedFirst in listOf(true, false)) for (meDroppedFirst in listOf(true, false)) {
            val p = plan(me, other, mePickedFirst, meDroppedFirst, road, mySolo, theirSolo)
            if (best == null || p.worst < best.worst) best = p
        }
        val chosen = best!!
        if (chosen.mine > cfg.maxDetourRatio || chosen.theirs > cfg.maxDetourRatio) return null
        val myDetour = chosen.mine
        val theirDetour = chosen.theirs
        val sharedPath = chosen.length
        val mineFirst = chosen.meDroppedFirst

        val overlap = overlap(me.route, other.route)
        val pickupScore = 1.0 - pickupGap / cfg.maxPickupGapM
        val worstDetour = max(myDetour, theirDetour)
        val detourScore = 1.0 - ((worstDetour - 1.0).coerceAtLeast(0.0) / (cfg.maxDetourRatio - 1.0))

        val raw = cfg.wOverlap * overlap + cfg.wDirection * dirSim +
            cfg.wPickup * pickupScore + cfg.wDetour * detourScore.coerceIn(0.0, 1.0)
        val score = (raw * 100).toInt().coerceIn(0, 100)
        return MatchResult(other, score, overlap, dirSim, pickupGap, myDetour, theirDetour, sharedPath, mineFirst)
    }

    private data class Plan(val length: Double, val mine: Double, val theirs: Double, val meDroppedFirst: Boolean) {
        val worst get() = max(mine, theirs)
    }

    /** In-cab distance for each rider for one pickup/drop order (straight-line legs × road factor). */
    private fun plan(me: TripPlan, o: TripPlan, mePickedFirst: Boolean, meDroppedFirst: Boolean,
                     road: Double, mySolo: Double, theirSolo: Double): Plan {
        val p1 = if (mePickedFirst) me.pickup else o.pickup
        val p2 = if (mePickedFirst) o.pickup else me.pickup
        val d1 = if (meDroppedFirst) me.drop else o.drop
        val d2 = if (meDroppedFirst) o.drop else me.drop
        val a = Geo.distanceM(p1, p2) * road
        val b = Geo.distanceM(p2, d1) * road
        val c = Geo.distanceM(d1, d2) * road
        // the rider picked first rides a+b (+c if dropped second); the other rides b (+c)
        val firstRiderIsMe = mePickedFirst
        val mine = (if (firstRiderIsMe) a else 0.0) + b + (if (meDroppedFirst) 0.0 else c)
        val theirs = (if (firstRiderIsMe) 0.0 else a) + b + (if (meDroppedFirst) c else 0.0)
        return Plan(a + b + c, mine / mySolo, theirs / theirSolo, meDroppedFirst)
    }

    /**
     * Greedy ride-group formation: start from the requester, add the best-scoring candidates
     * one by one as long as each new member is also compatible with everyone already in the group.
     */
    fun formGroup(me: TripPlan, pool: List<TripPlan>): List<MatchResult> {
        val group = ArrayList<MatchResult>()
        for (m in rank(me, pool)) {
            if (group.size + 1 >= cfg.maxGroupSize) break
            val okWithAll = group.all { g -> score(g.candidate, m.candidate) != null }
            if (okWithAll) group += m
        }
        return group
    }

    /** Fraction of the shorter route that runs within the corridor of the longer one. */
    fun overlap(a: List<GeoPoint>, b: List<GeoPoint>): Double {
        if (a.size < 2 || b.size < 2) return 0.0
        val (short, long) = if (Geo.lengthM(a) <= Geo.lengthM(b)) a to b else b to a
        val samples = Geo.resample(short, cfg.sampleStepM)
        val longSampled = Geo.resample(long, cfg.sampleStepM * 2)
        val inside = samples.count { Geo.distanceToPathM(it, longSampled) <= cfg.corridorM }
        return inside.toDouble() / samples.size
    }

    private fun directionSimilarity(a: TripPlan, b: TripPlan): Double {
        val diff = Math.toRadians(Geo.bearingDeg(a.pickup, a.drop) - Geo.bearingDeg(b.pickup, b.drop))
        return max(0.0, min(1.0, cos(diff)))
    }
}
