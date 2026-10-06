package com.cabfusion.app.data.model

import com.cabfusion.app.core.GeoPoint
import com.google.firebase.firestore.Exclude

object Role { const val RIDER = "rider"; const val DRIVER = "driver" }

/** Lifecycle of a ride request (rider side) and of a ride group (driver side). */
object Status {
    const val SEARCHING = "SEARCHING"      // request open, waiting for compatible riders
    const val MATCHED = "MATCHED"          // request is part of a ride group
    const val FORMED = "FORMED"            // group waiting for a driver
    const val ACCEPTED = "ACCEPTED"        // driver accepted the group
    const val ARRIVING = "ARRIVING"        // driver heading to the first pickup
    const val ON_TRIP = "ON_TRIP"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"

    fun label(s: String) = when (s) {
        SEARCHING -> "Searching"; MATCHED, FORMED -> "Matched"; ACCEPTED -> "Driver assigned"
        ARRIVING -> "Driver arriving"; ON_TRIP -> "On trip"; COMPLETED -> "Completed"
        CANCELLED -> "Cancelled"; else -> s
    }
}

data class UserProfile(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val role: String = Role.RIDER,
    val createdAt: Long = 0L,
)

/** Firestore document `rideRequests/{id}`. The route is stored flattened [lat0, lng0, lat1, lng1, …]. */
data class RideRequest(
    val id: String = "",
    val riderId: String = "",
    val riderName: String = "",
    val pickupName: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val dropName: String = "",
    val dropLat: Double = 0.0,
    val dropLng: Double = 0.0,
    val route: List<Double> = emptyList(),
    val distanceM: Double = 0.0,
    val durationS: Double = 0.0,
    val soloFare: Int = 0,
    val shared: Boolean = true,
    val departureEpochMin: Long = 0L,
    val status: String = Status.SEARCHING,
    val groupId: String? = null,
    val fareShare: Int = 0,
    val matchScore: Int = 0,
    val createdAt: Long = 0L,
) {
    @get:Exclude
    val pickup get() = GeoPoint(pickupLat, pickupLng)
    @get:Exclude
    val drop get() = GeoPoint(dropLat, dropLng)
    @get:Exclude
    val routePoints: List<GeoPoint> get() = route.chunked(2).filter { it.size == 2 }.map { GeoPoint(it[0], it[1]) }
    @get:Exclude
    val saving get() = if (status == Status.CANCELLED || fareShare == 0) 0 else (soloFare - fareShare).coerceAtLeast(0)
}

/** Firestore document `rideGroups/{id}`: riders travelling together in one cab. */
data class RideGroup(
    val id: String = "",
    val requestIds: List<String> = emptyList(),
    val riderIds: List<String> = emptyList(),
    val riderNames: List<String> = emptyList(),
    val stopNames: List<String> = emptyList(),      // "Pickup · Ravi K · Anna Nagar West", in visiting order
    val stopPoints: List<Double> = emptyList(),     // flattened lat/lng of the stops, same order
    val sharedRoute: List<Double> = emptyList(),
    val sharedDistanceM: Double = 0.0,
    val sharedDurationS: Double = 0.0,
    val totalFare: Int = 0,
    val shares: Map<String, Int> = emptyMap(),      // riderId → fare share
    val soloFares: Map<String, Int> = emptyMap(),   // riderId → solo fare
    val scores: Map<String, Int> = emptyMap(),      // riderId → match score with the requester
    val status: String = Status.FORMED,
    val driverId: String? = null,
    val driverName: String? = null,
    val vehicle: String? = null,
    val driverLat: Double = 0.0,
    val driverLng: Double = 0.0,
    val createdAt: Long = 0L,
) {
    @get:Exclude
    val routePoints: List<GeoPoint> get() = sharedRoute.chunked(2).filter { it.size == 2 }.map { GeoPoint(it[0], it[1]) }
    @get:Exclude
    val stops: List<GeoPoint> get() = stopPoints.chunked(2).filter { it.size == 2 }.map { GeoPoint(it[0], it[1]) }
    @get:Exclude
    val firstPickup: GeoPoint? get() = stops.firstOrNull()
}

fun List<GeoPoint>.flatten(): List<Double> = flatMap { listOf(it.lat, it.lng) }

/** Keep at most [max] points so a route fits comfortably in one Firestore document. */
fun List<GeoPoint>.simplify(max: Int = 160): List<GeoPoint> {
    if (size <= max) return this
    val step = (size - 1).toDouble() / (max - 1)
    return (0 until max).map { this[(it * step).toInt().coerceAtMost(size - 1)] }
}
