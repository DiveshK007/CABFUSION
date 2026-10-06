package com.cabfusion.app.core

import kotlin.math.max
import kotlin.math.roundToInt

/** A fare quote in whole rupees. */
data class FareQuote(val distanceKm: Double, val durationMin: Double, val total: Int)

/** One rider's share of a pooled trip. */
data class FareShare(val riderId: String, val soloFare: Int, val share: Int) {
    val saving: Int get() = soloFare - share
    val savingPercent: Int get() = if (soloFare == 0) 0 else (saving * 100.0 / soloFare).roundToInt()
}

/**
 * Fare model: base + per-km + per-minute, with a minimum fare and a demand multiplier.
 * Pooled trips are priced on the shared path and split in proportion to each rider's solo
 * distance, so the rider who travels further pays more, and nobody pays more than solo.
 */
class FareCalculator(
    private val baseFare: Double = 50.0,
    private val perKm: Double = 14.0,
    private val perMin: Double = 1.5,
    private val minimumFare: Double = 80.0,
    private val poolDiscount: Double = 0.85,   // operator passes on 15 % for accepting a shared ride
) {
    fun quote(distanceM: Double, durationS: Double, demand: Double = 1.0): FareQuote {
        val km = distanceM / 1000.0
        val min = durationS / 60.0
        val fare = max(minimumFare, (baseFare + perKm * km + perMin * min) * demand.coerceIn(1.0, 2.0))
        return FareQuote(km, min, fare.roundToInt())
    }

    /**
     * @param soloDistances riderId → that rider's solo route length in metres
     * @param sharedDistanceM length of the pooled route
     * @param sharedDurationS duration of the pooled route
     */
    fun split(
        soloDistances: Map<String, Double>,
        soloDurations: Map<String, Double>,
        sharedDistanceM: Double,
        sharedDurationS: Double,
        demand: Double = 1.0,
    ): List<FareShare> {
        require(soloDistances.isNotEmpty())
        val pooledTotal = quote(sharedDistanceM, sharedDurationS, demand).total * poolDiscount
        val weightSum = soloDistances.values.sum()
        return soloDistances.map { (id, d) ->
            val solo = quote(d, soloDurations[id] ?: (d / 1000.0 * 120.0), demand).total
            val share = (pooledTotal * d / weightSum).roundToInt()
            FareShare(id, solo, minOf(share, solo))
        }
    }
}
