package com.cabfusion.app.core

import com.cabfusion.app.data.MapsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC18: when the routing service cannot be reached the app falls back to a labelled estimate. */
class FallbackTest {
    @Test fun estimateIsLabelledAndUsesRoadFactor() {
        val a = GeoPoint(13.0694, 80.1948); val b = GeoPoint(13.0067, 80.2206)
        val r = MapsRepository().estimate(listOf(a, b))
        assertEquals("ESTIMATE", r.source)
        assertEquals(Geo.distanceM(a, b) * 1.3, r.distanceM, 1.0)
        assertEquals(r.distanceM / (22_000.0 / 3600.0), r.durationS, 1.0)
        assertTrue(r.points.size > 2)
    }
}
