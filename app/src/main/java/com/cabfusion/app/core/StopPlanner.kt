package com.cabfusion.app.core

/** A pickup or drop the driver has to visit. */
data class Stop(val tripId: String, val riderName: String, val point: GeoPoint, val isPickup: Boolean)

/**
 * Orders the stops of a ride group: all pickups first (nearest-neighbour from the requester's
 * pickup), then all drops (nearest-neighbour from the last pickup). For groups of two or three
 * this gives the same order a person would choose, and it never drops a rider before pickup.
 */
object StopPlanner {
    fun order(requester: TripPlan, others: List<TripPlan>): List<Stop> {
        val all = listOf(requester) + others
        val pickups = all.map { Stop(it.id, it.riderName, it.pickup, true) }.toMutableList()
        val drops = all.map { Stop(it.id, it.riderName, it.drop, false) }.toMutableList()
        val out = ArrayList<Stop>()
        var cur = pickups.removeAt(0)                      // the requester is collected first
        out += cur
        while (pickups.isNotEmpty()) {
            cur = pickups.minBy { Geo.distanceM(cur.point, it.point) }.also { pickups.remove(it) }
            out += cur
        }
        while (drops.isNotEmpty()) {
            cur = drops.minBy { Geo.distanceM(cur.point, it.point) }.also { drops.remove(it) }
            out += cur
        }
        return out
    }
}
