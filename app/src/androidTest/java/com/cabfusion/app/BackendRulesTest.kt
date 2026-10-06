package com.cabfusion.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cabfusion.app.data.BackendException
import com.cabfusion.app.data.LocalBackend
import com.cabfusion.app.data.MapsRepository
import com.cabfusion.app.data.model.RideGroup
import com.cabfusion.app.data.model.Role
import com.cabfusion.app.data.model.Status
import com.cabfusion.app.data.model.UserProfile
import com.cabfusion.app.domain.MatchingService
import com.cabfusion.app.domain.soloGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Consistency rules of the ride lifecycle (TC12, TC13, TC15 and the two-driver case), checked on
 * the demo backend, which enforces the same rules as the Firestore transactions.
 */
@RunWith(AndroidJUnit4::class)
class BackendRulesTest {
    private lateinit var backend: LocalBackend
    private lateinit var matching: MatchingService

    @Before fun setUp() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("cf_local", Context.MODE_PRIVATE).edit().clear().commit()
        val maps = MapsRepository()
        backend = LocalBackend(ctx, maps)
        matching = MatchingService(backend, maps)
        backend.register("Test Rider", "rider.test@cabfusion.app", "9876543210", "secret123", Role.RIDER)
        Unit
    }

    private suspend fun myRequest(shared: Boolean = true) = backend.openRequests().first { it.riderName == "Sanjay T" }.let { seed ->
        // same trip as a seeded rider, so a match is guaranteed
        backend.createRequest(seed.copy(id = "", riderId = backend.currentUser()!!.uid, riderName = "Test Rider",
            shared = shared, status = Status.SEARCHING, groupId = null))
    }

    @Test fun confirmedOfferMatchesEveryMember() = runBlocking {
        val me = myRequest()
        val offer = matching.buildOffer(me)
        assertNotNull("expected a pooled offer", offer)
        assertTrue("share must not exceed solo", offer!!.myShare <= offer.mySolo)
        val g = matching.confirm(offer)
        for (id in g.requestIds) {
            val r = backend.observeRequest(id).first()!!
            assertEquals(Status.MATCHED, r.status)
            assertEquals(g.id, r.groupId)
            assertTrue(r.fareShare > 0)
        }
    }

    @Test fun riderCannotBeInTwoGroups() = runBlocking {
        val me = myRequest()
        val offer = matching.buildOffer(me)!!
        matching.confirm(offer)
        // a second group that reuses one of the same co-riders must be refused
        val other = backend.createRequest(me.copy(id = "", riderId = "someone_else", riderName = "Other"))
        val clash = offer.group.copy(requestIds = listOf(other.id) + offer.group.requestIds.drop(1))
        try {
            backend.formGroup(clash); fail("second group with an already matched rider was accepted")
        } catch (e: BackendException) {
            assertTrue(e.message!!.contains("just matched"))
        }
    }

    @Test fun twoDriversCannotAcceptTheSameRide() = runBlocking {
        val open = backend.observeOpenGroups().first { it.isNotEmpty() }
        val g = open.first()
        val d1 = UserProfile("drv1", "Driver One", role = Role.DRIVER)
        val d2 = UserProfile("drv2", "Driver Two", role = Role.DRIVER)
        backend.acceptGroup(g.id, d1, "TN 01 AA 1111")
        try {
            backend.acceptGroup(g.id, d2, "TN 02 BB 2222"); fail("second driver accepted the same ride")
        } catch (e: BackendException) {
            assertTrue(e.message!!.contains("already accepted"))
        }
        assertEquals("drv1", backend.observeGroup(g.id).first()!!.driverId)
    }

    @Test fun soloRideChargesSoloFare() = runBlocking {
        val me = myRequest(shared = false)
        val g: RideGroup = backend.formGroup(soloGroup(me))
        assertEquals(listOf(me.id), g.requestIds)
        assertEquals(me.soloFare, g.shares[me.riderId])
        assertEquals(Status.MATCHED, backend.observeRequest(me.id).first()!!.status)
    }
}
