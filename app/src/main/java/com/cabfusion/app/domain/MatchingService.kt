package com.cabfusion.app.domain

import com.cabfusion.app.core.FareCalculator
import com.cabfusion.app.core.MatchResult
import com.cabfusion.app.core.RouteMatcher
import com.cabfusion.app.core.StopPlanner
import com.cabfusion.app.core.TripPlan
import com.cabfusion.app.data.Backend
import com.cabfusion.app.data.MapsRepository
import com.cabfusion.app.data.model.RideGroup
import com.cabfusion.app.data.model.RideRequest
import com.cabfusion.app.data.model.flatten
import com.cabfusion.app.data.model.simplify

/** A proposed pooled ride shown to the rider before they confirm it. */
data class PoolOffer(
    val matches: List<MatchResult>,
    val group: RideGroup,
    val myShare: Int,
    val mySolo: Int,
) { val saving get() = (mySolo - myShare).coerceAtLeast(0) }

/**
 * Glue between the pure matching engine and the backend: turns open ride requests into
 * [TripPlan]s, ranks them, plans the stop order and the shared road route, and splits the fare.
 */
class MatchingService(
    private val backend: Backend,
    private val maps: MapsRepository,
    private val matcher: RouteMatcher = RouteMatcher(),
    private val fare: FareCalculator = FareCalculator(),
) {
    private fun RideRequest.toPlan() = TripPlan(id, riderName, pickup, drop, routePoints, distanceM, departureEpochMin)

    /** Every compatible open request for [me], best match first. */
    suspend fun candidates(me: RideRequest): List<MatchResult> {
        val pool = backend.openRequests().filter { it.riderId != me.riderId }.map { it.toPlan() }
        return matcher.rank(me.toPlan(), pool)
    }

    /** Builds the best group (up to 3 riders) for [me]; null when nobody compatible is waiting. */
    suspend fun buildOffer(me: RideRequest): PoolOffer? {
        val open = backend.openRequests().filter { it.riderId != me.riderId }
        val byId = open.associateBy { it.id }
        val members = matcher.formGroup(me.toPlan(), open.map { it.toPlan() })
        if (members.isEmpty()) return null
        val others = members.mapNotNull { byId[it.candidate.id] }
        val all = listOf(me) + others

        val stops = StopPlanner.order(me.toPlan(), others.map { it.toPlan() })
        val route = maps.route(stops.map { it.point })
        val split = fare.split(
            all.associate { it.riderId to it.distanceM },
            all.associate { it.riderId to it.durationS },
            route.distanceM, route.durationS,
        )
        val shares = split.associate { it.riderId to it.share }
        val names = all.associate { it.id to it }
        val group = RideGroup(
            requestIds = all.map { it.id },
            riderIds = all.map { it.riderId },
            riderNames = all.map { it.riderName },
            stopNames = stops.map { s ->
                val r = names.getValue(s.tripId)
                (if (s.isPickup) "Pickup · " else "Drop · ") + "${r.riderName} · " + (if (s.isPickup) r.pickupName else r.dropName)
            },
            stopPoints = stops.map { it.point }.flatten(),
            sharedRoute = route.points.simplify().flatten(),
            sharedDistanceM = route.distanceM,
            sharedDurationS = route.durationS,
            totalFare = shares.values.sum(),
            shares = shares,
            soloFares = split.associate { it.riderId to it.soloFare },
            scores = mapOf(me.riderId to 100) + members.associate { m -> byId.getValue(m.candidate.id).riderId to m.score },
        )
        return PoolOffer(members, group, shares[me.riderId] ?: me.soloFare, me.soloFare)
    }

    suspend fun confirm(offer: PoolOffer): RideGroup = backend.formGroup(offer.group)
}

/** A ride group holding only [me] — used for solo rides and when the rider stops waiting for a match. */
fun soloGroup(me: RideRequest) = RideGroup(
    requestIds = listOf(me.id), riderIds = listOf(me.riderId), riderNames = listOf(me.riderName),
    stopNames = listOf("Pickup · ${me.riderName} · ${me.pickupName}", "Drop · ${me.riderName} · ${me.dropName}"),
    stopPoints = listOf(me.pickup, me.drop).flatten(), sharedRoute = me.route,
    sharedDistanceM = me.distanceM, sharedDurationS = me.durationS, totalFare = me.soloFare,
    shares = mapOf(me.riderId to me.soloFare), soloFares = mapOf(me.riderId to me.soloFare), scores = mapOf(me.riderId to 100),
)
