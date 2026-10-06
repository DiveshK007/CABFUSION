package com.cabfusion.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    // Real Chennai coordinates
    private val annaNagar = GeoPoint(13.0850, 80.2101)
    private val annaNagarWest = GeoPoint(13.0860, 80.2010)
    private val tNagar = GeoPoint(13.0418, 80.2341)
    private val kodambakkam = GeoPoint(13.0521, 80.2255)
    private val tambaram = GeoPoint(12.9249, 80.1000)
    private val ennore = GeoPoint(13.2146, 80.3203)

    private fun line(a: GeoPoint, b: GeoPoint, n: Int = 20) =
        (0..n).map { GeoPoint(a.lat + (b.lat - a.lat) * it / n, a.lng + (b.lng - a.lng) * it / n) }

    private fun trip(id: String, a: GeoPoint, b: GeoPoint, t: Long = 600) =
        line(a, b).let { TripPlan(id, id, a, b, it, Geo.lengthM(it) * 1.3, t) }

    @Test fun haversineMatchesKnownDistance() {
        // Anna Nagar → T. Nagar is about 5.5 km as the crow flies
        val d = Geo.distanceM(annaNagar, tNagar)
        assertTrue("got $d", d in 5_000.0..6_000.0)
    }

    @Test fun nearlyIdenticalTripsScoreHigh() {
        val me = trip("me", annaNagar, tNagar)
        val r = RouteMatcher().score(me, trip("ravi", GeoPoint(13.0868, 80.2075), tNagar))   // ~350 m away
        assertNotNull(r)
        assertTrue("score ${r!!.score}", r.score >= 80)
        assertTrue(r.overlap > 0.8)
    }

    @Test fun partialOverlapScoresLowerThanFullOverlap() {
        val m = RouteMatcher()
        val me = trip("me", annaNagar, tNagar)
        val full = m.score(me, trip("a", annaNagarWest, tNagar))!!.score
        val part = m.score(me, trip("b", annaNagarWest, kodambakkam))!!.score
        assertTrue("full=$full part=$part", full > part)
    }

    @Test fun oppositeDirectionIsRejected() {
        assertNull(RouteMatcher().score(trip("me", annaNagar, tNagar), trip("x", tNagar, annaNagar)))
    }

    @Test fun farPickupIsRejected() {
        assertNull(RouteMatcher().score(trip("me", annaNagar, tNagar), trip("x", ennore, tNagar)))
    }

    @Test fun departureOutsideWindowIsRejected() {
        assertNull(RouteMatcher().score(trip("me", annaNagar, tNagar, 600), trip("x", annaNagarWest, tNagar, 700)))
    }

    @Test fun largeDetourIsRejected() {
        // same start, but the other rider heads far south-west: no useful shared stretch
        val m = RouteMatcher()
        assertTrue(m.rank(trip("me", annaNagar, kodambakkam), listOf(trip("x", annaNagarWest, tambaram))).isEmpty())
    }

    @Test fun rankingIsBestFirstAndGroupRespectsSize() {
        val m = RouteMatcher()
        val me = trip("me", annaNagar, tNagar)
        val pool = listOf(trip("b", annaNagarWest, kodambakkam), trip("a", annaNagarWest, tNagar),
            trip("c", GeoPoint(13.0800, 80.2120), tNagar), trip("x", tNagar, annaNagar))
        val ranked = m.rank(me, pool)
        assertEquals("c", ranked.first().candidate.id)          // closest pickup, same destination
        assertTrue(ranked.indexOfFirst { it.candidate.id == "a" } < ranked.indexOfFirst { it.candidate.id == "b" })
        assertTrue(ranked.zipWithNext().all { (p, q) -> p.score >= q.score })
        val group = m.formGroup(me, pool)
        assertTrue(group.size in 1..2)
        assertTrue(group.none { it.candidate.id == "x" })
    }

    @Test fun fareQuoteAndMinimum() {
        val f = FareCalculator()
        assertEquals(80, f.quote(500.0, 120.0).total)                 // minimum fare applies
        assertEquals(50 + 14 * 7 + 18 * 1.5.toInt() + 9, f.quote(7_000.0, 18 * 60.0).total) // 50+98+27 = 175
    }

    @Test fun splitIsCheaperThanSoloAndProportional() {
        val f = FareCalculator()
        val shares = f.split(mapOf("me" to 7_000.0, "ravi" to 5_000.0),
            mapOf("me" to 1_080.0, "ravi" to 840.0), 8_000.0, 1_320.0)
        val me = shares.first { it.riderId == "me" }
        val ravi = shares.first { it.riderId == "ravi" }
        assertTrue(me.share < me.soloFare && ravi.share < ravi.soloFare)
        assertTrue(me.share > ravi.share)                              // longer trip pays more
        assertTrue(me.savingPercent in 20..70)
    }
}

class StopPlannerTest {
    private fun t(id: String, a: GeoPoint, b: GeoPoint) = TripPlan(id, id, a, b, listOf(a, b), Geo.distanceM(a, b), 0)

    @Test fun pickupsComeBeforeDropsAndRequesterIsFirst() {
        val me = t("me", GeoPoint(13.0850, 80.2101), GeoPoint(13.0418, 80.2341))
        val ravi = t("ravi", GeoPoint(13.0860, 80.2010), GeoPoint(13.0521, 80.2255))
        val stops = StopPlanner.order(me, listOf(ravi))
        assertEquals(listOf("me", "ravi", "ravi", "me"), stops.map { it.tripId })
        assertEquals(listOf(true, true, false, false), stops.map { it.isPickup })
    }
}
