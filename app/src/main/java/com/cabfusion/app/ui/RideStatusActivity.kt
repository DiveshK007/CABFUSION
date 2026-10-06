package com.cabfusion.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cabfusion.app.R
import com.cabfusion.app.core.Geo
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.data.model.RideGroup
import com.cabfusion.app.data.model.RideRequest
import com.cabfusion.app.data.model.Status
import com.cabfusion.app.databinding.ActivityRideStatusBinding
import com.cabfusion.app.databinding.ItemMatchBinding
import com.cabfusion.app.databinding.ItemStopBinding
import com.cabfusion.app.domain.PoolOffer
import com.cabfusion.app.domain.soloGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.views.overlay.Marker

/**
 * Live ride screen. While the request is SEARCHING it keeps asking the matching engine for a
 * pooled offer; once the request belongs to a group it follows the group: driver assignment,
 * driver position, pickups and drops, completion.
 */
class RideStatusActivity : AppCompatActivity() {
    private lateinit var b: ActivityRideStatusBinding
    private var me: RideRequest? = null
    private var group: RideGroup? = null
    private var offer: PoolOffer? = null
    private var matchJob: Job? = null
    private var groupJob: Job? = null
    private var driverMarker: Marker? = null
    private var lastNotified: String? = null
    private var drawnKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRideStatusBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.map.setup()
        b.btnBack.setOnClickListener { finish() }
        val id = intent.getStringExtra(EXTRA_REQUEST_ID) ?: run { finish(); return }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.backend.observeRequest(id).collect { r -> if (r != null) onRequest(r) }
            }
        }
    }

    override fun onResume() { super.onResume(); b.map.onResume() }
    override fun onPause() { b.map.onPause(); super.onPause() }

    // ------------------------------------------------------------------ request side
    private fun onRequest(r: RideRequest) {
        val first = me == null
        me = r
        b.tvRideType.text = if (r.shared) "Shared ride" else "Solo ride"
        when {
            r.status == Status.CANCELLED -> showCancelled()
            r.groupId != null -> { stopMatching(); followGroup(r.groupId) }
            r.status == Status.SEARCHING && !r.shared -> if (first) startSolo(r)
            r.status == Status.SEARCHING -> { showSearching(r); if (matchJob == null) startMatching() }
        }
    }

    private fun showSearching(r: RideRequest) {
        setStep(1); chip("Searching")
        b.progress.visibility = View.VISIBLE
        if (offer == null) {
            b.tvTitle.text = "Finding riders on your route…"
            b.tvSubtitle.text = "${r.pickupName} → ${r.dropName} · ${Fmt.km(r.distanceM)}. We match riders going the same way, " +
                "leaving within 30 minutes, with no more than a 45% detour."
            b.btnPrimary.text = "Ride solo instead · ${Fmt.rs(r.soloFare)}"
            b.btnPrimary.visibility = View.VISIBLE
            b.btnPrimary.setOnClickListener { goSolo() }
            drawPlan(r)
        }
        b.btnSecondary.visibility = View.VISIBLE
        b.btnSecondary.text = "Cancel request"
        b.btnSecondary.setOnClickListener { cancel() }
    }

    private fun startMatching() {
        matchJob = lifecycleScope.launch {
            while (isActive) {
                val r = me ?: break
                if (r.status != Status.SEARCHING) break
                val o = runCatching { ServiceLocator.matching.buildOffer(r) }.getOrNull()
                if (o != null && o.group.requestIds.toSet() != offer?.group?.requestIds?.toSet()) showOffer(o)
                delay(5_000)
            }
        }
    }

    private fun stopMatching() { matchJob?.cancel(); matchJob = null; offer = null }

    private fun showOffer(o: PoolOffer) {
        offer = o
        val r = me ?: return
        setStep(2); chip("Match found")
        b.progress.visibility = View.GONE
        b.tvTitle.text = "${o.matches.size} rider${if (o.matches.size > 1) "s" else ""} going your way"
        b.tvSubtitle.text = "Shared trip ${Fmt.km(o.group.sharedDistanceM)} · about ${Fmt.mins(o.group.sharedDurationS)}"
        showFare(o.myShare, o.mySolo, o.group)
        b.hdrMatches.visibility = View.VISIBLE
        b.listMatches.removeAllViews()
        o.matches.forEach { m ->
            val v = ItemMatchBinding.inflate(layoutInflater, b.listMatches, false)
            v.tvInitial.text = Fmt.initial(m.candidate.riderName)
            v.tvName.text = m.candidate.riderName
            v.tvDetail.text = "${(m.overlap * 100).toInt()}% route overlap · pickup ${Fmt.km(m.pickupGapM)} away"
            v.tvScore.text = "${m.score}% match"
            b.listMatches.addView(v.root)
        }
        showStops(o.group)
        drawGroup(o.group)
        b.btnPrimary.text = "Confirm shared ride · ${Fmt.rs(o.myShare)}"
        b.btnPrimary.visibility = View.VISIBLE
        b.btnPrimary.isEnabled = true
        b.btnPrimary.setOnClickListener { confirm(o) }
    }

    private fun confirm(o: PoolOffer) {
        b.btnPrimary.isEnabled = false
        lifecycleScope.launch {
            try {
                ServiceLocator.matching.confirm(o)
            } catch (e: Exception) {
                toast(e.message ?: "That match is no longer available")
                offer = null; b.btnPrimary.isEnabled = true
                me?.let { showSearching(it) }
            }
        }
    }

    private fun startSolo(r: RideRequest) = lifecycleScope.launch {
        chip("Booking"); b.tvTitle.text = "Booking your cab…"
        runCatching { ServiceLocator.backend.formGroup(soloGroup(r)) }.onFailure { toast(it.message ?: "Booking failed") }
    }

    private fun goSolo() {
        val r = me ?: return
        stopMatching()
        b.btnPrimary.isEnabled = false
        startSolo(r.copy(shared = false))
    }

    private fun cancel() = lifecycleScope.launch {
        val r = me ?: return@launch
        stopMatching()
        runCatching { ServiceLocator.backend.cancelRequest(r.id) }
    }

    private fun showCancelled() {
        stopMatching()
        setStep(0); chip("Cancelled")
        b.progress.visibility = View.GONE
        b.tvTitle.text = "Ride request cancelled"
        b.tvSubtitle.text = ""
        b.btnPrimary.visibility = View.GONE
        b.btnSecondary.text = "Back to home"
        b.btnSecondary.setOnClickListener { finish() }
    }

    // ------------------------------------------------------------------ group side
    private fun followGroup(groupId: String) {
        if (groupJob != null) return
        groupJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.backend.observeGroup(groupId).collect { g -> if (g != null) onGroup(g) }
            }
        }
    }

    private fun onGroup(g: RideGroup) {
        group = g
        val r = me ?: return
        val myShare = g.shares[r.riderId] ?: r.fareShare
        val solo = g.soloFares[r.riderId] ?: r.soloFare
        b.progress.visibility = if (g.status == Status.FORMED) View.VISIBLE else View.GONE
        showFare(myShare, solo, g)
        showCoRiders(g, r)
        showStops(g)
        if (drawnKey != g.id) { drawGroup(g); drawnKey = g.id }
        b.btnPrimary.visibility = View.GONE
        b.btnSecondary.visibility = View.GONE

        when (g.status) {
            Status.FORMED -> {
                setStep(2); chip("Matched")
                b.tvTitle.text = if (g.riderIds.size > 1) "You're pooled with ${g.riderIds.size - 1} rider${if (g.riderIds.size > 2) "s" else ""}" else "Ride booked"
                b.tvSubtitle.text = "Looking for a driver nearby…"
            }
            Status.ACCEPTED, Status.ARRIVING -> {
                setStep(3); chip(Status.label(g.status))
                b.tvTitle.text = if (g.status == Status.ACCEPTED) "Driver assigned" else "Driver is on the way"
                b.tvSubtitle.text = "First pickup: ${g.stopNames.firstOrNull()?.substringAfterLast(" · ") ?: r.pickupName}"
            }
            Status.ON_TRIP -> {
                setStep(4); chip("On trip")
                b.tvTitle.text = "Enjoy the ride"
                b.tvSubtitle.text = "Heading to ${r.dropName}"
            }
            Status.COMPLETED -> {
                setStep(5); chip("Completed")
                b.tvTitle.text = "You've arrived"
                b.tvSubtitle.text = if (myShare < solo) "You paid ${Fmt.rs(myShare)} instead of ${Fmt.rs(solo)} by sharing." else "Thanks for riding with CabFusion."
                b.btnPrimary.visibility = View.VISIBLE
                b.btnPrimary.isEnabled = true
                b.btnPrimary.text = "Done"
                b.btnPrimary.setOnClickListener { finish() }
            }
            Status.CANCELLED -> showCancelled()
        }
        showDriver(g)
        if (lastNotified != null && lastNotified != g.status) notifyStatus(g)
        lastNotified = g.status
    }

    private fun showDriver(g: RideGroup) {
        val name = g.driverName
        if (name == null) { b.cardDriver.visibility = View.GONE; return }
        b.cardDriver.visibility = View.VISIBLE
        b.tvDriverName.text = name
        b.tvVehicle.text = g.vehicle ?: ""
        val pos = if (g.driverLat != 0.0) com.cabfusion.app.core.GeoPoint(g.driverLat, g.driverLng) else null
        val target = g.firstPickup
        b.tvEta.text = if (pos != null && target != null && g.status in listOf(Status.ACCEPTED, Status.ARRIVING)) {
            val etaS = Geo.distanceM(pos, target) * 1.3 / (22_000.0 / 3600.0)
            Fmt.mins(etaS)
        } else ""
        if (pos != null) {
            val m = driverMarker ?: b.map.pin(pos, R.drawable.marker_car, name).also { driverMarker = it }
            m.position = pos.osm()
            b.map.invalidate()
        }
    }

    private fun notifyStatus(g: RideGroup) {
        val text = when (g.status) {
            Status.ACCEPTED -> "${g.driverName} accepted your ride · ${g.vehicle ?: ""}"
            Status.ARRIVING -> "Your driver is on the way to the pickup"
            Status.ON_TRIP -> "Trip started"
            Status.COMPLETED -> "You've arrived. Thanks for sharing the ride!"
            else -> return
        }
        notifyRide(g.id.hashCode(), "CabFusion", text)
    }

    // ------------------------------------------------------------------ shared rendering
    private fun showFare(share: Int, solo: Int, g: RideGroup) {
        b.cardFare.visibility = View.VISIBLE
        b.tvMyFare.text = Fmt.rs(share)
        val saving = (solo - share).coerceAtLeast(0)
        if (saving > 0) {
            b.tvSoloFare.text = "Solo fare ${Fmt.rs(solo)}"
            b.tvSaving.visibility = View.VISIBLE
            b.tvSaving.text = "Save ${Fmt.rs(saving)} (${saving * 100 / solo.coerceAtLeast(1)}%)"
        } else {
            b.tvSoloFare.text = "Fare for the whole trip"
            b.tvSaving.visibility = View.GONE
        }
        b.tvTripMeta.text = "${g.riderIds.size} rider${if (g.riderIds.size > 1) "s" else ""} · ${Fmt.km(g.sharedDistanceM)} · " +
            "${Fmt.mins(g.sharedDurationS)} · cab total ${Fmt.rs(g.totalFare)}"
    }

    private fun showCoRiders(g: RideGroup, r: RideRequest) {
        val others = g.riderIds.indices.filter { g.riderIds[it] != r.riderId }
        b.hdrMatches.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        b.listMatches.removeAllViews()
        others.forEach { i ->
            val v = ItemMatchBinding.inflate(layoutInflater, b.listMatches, false)
            val name = g.riderNames.getOrElse(i) { "Rider" }
            v.tvInitial.text = Fmt.initial(name)
            v.tvName.text = name
            v.tvDetail.text = "Pays ${Fmt.rs(g.shares[g.riderIds[i]] ?: 0)}"
            val score = g.scores[g.riderIds[i]]
            v.tvScore.visibility = if (score == null) View.GONE else View.VISIBLE
            v.tvScore.text = "$score% match"
            b.listMatches.addView(v.root)
        }
    }

    private fun showStops(g: RideGroup) {
        b.hdrStops.visibility = View.VISIBLE
        b.listStops.removeAllViews()
        g.stopNames.forEach { s ->
            val v = ItemStopBinding.inflate(layoutInflater, b.listStops, false)
            v.dot.setBackgroundResource(if (s.startsWith("Pickup")) R.drawable.marker_green else R.drawable.marker_amber)
            v.tvStop.text = s
            b.listStops.addView(v.root)
        }
    }

    private fun drawPlan(r: RideRequest) {
        if (drawnKey == "plan") return
        drawnKey = "plan"
        b.map.overlays.clear(); driverMarker = null
        b.map.route(r.routePoints, ContextCompat.getColor(this, R.color.route_line))
        b.map.pin(r.pickup, R.drawable.marker_green, r.pickupName)
        b.map.pin(r.drop, R.drawable.marker_amber, r.dropName)
        b.map.fit(r.routePoints)
    }

    private fun drawGroup(g: RideGroup) {
        drawnKey = g.id
        b.map.overlays.clear(); driverMarker = null
        me?.let { b.map.route(it.routePoints, ContextCompat.getColor(this, R.color.route_other), 8f) }
        b.map.route(g.routePoints, ContextCompat.getColor(this, R.color.route_line))
        g.stops.forEachIndexed { i, p ->
            val name = g.stopNames.getOrElse(i) { "" }
            b.map.pin(p, if (name.startsWith("Pickup")) R.drawable.marker_green else R.drawable.marker_amber, name)
        }
        b.map.fit(g.routePoints + g.stops)
    }

    private fun chip(text: String) { b.tvStatusChip.text = text }

    private fun setStep(n: Int) {
        listOf(b.step1, b.step2, b.step3, b.step4, b.step5).forEachIndexed { i, v ->
            v.setBackgroundColor(ContextCompat.getColor(this, if (i < n) R.color.accent_teal_bright else R.color.dark_border))
        }
    }

    companion object { const val EXTRA_REQUEST_ID = "request_id" }
}
