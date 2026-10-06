package com.cabfusion.app.ui

import android.Manifest
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cabfusion.app.R
import com.cabfusion.app.core.Geo
import com.cabfusion.app.core.GeoPoint
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.data.model.RideGroup
import com.cabfusion.app.data.model.Status
import com.cabfusion.app.databinding.ActivityDriverBinding
import com.cabfusion.app.databinding.ItemGroupBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.views.overlay.Marker

/** Driver mode: go online, see pooled groups waiting for a cab, accept one and drive it through. */
class DriverActivity : AppCompatActivity() {
    private lateinit var b: ActivityDriverBinding
    private var here: GeoPoint = GeoPoint(13.0600, 80.2000)    // fallback start point near Koyambedu
    private var listJob: Job? = null
    private var tripJob: Job? = null
    private var moveJob: Job? = null
    private var active: RideGroup? = null
    private var carMarker: Marker? = null
    private val prefs by lazy { getSharedPreferences("cf_driver", MODE_PRIVATE) }

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDriverBinding.inflate(layoutInflater)
        setContentView(b.root)
        val user = ServiceLocator.backend.currentUser() ?: run { go<LoginActivity>(clearTask = true); finish(); return }
        b.tvGreeting.text = "Hi ${user.name.substringBefore(' ')},"
        b.etVehicle.setText(prefs.getString("vehicle", ""))
        b.map.setup()
        b.btnProfile.setOnClickListener { go<ProfileActivity>() }
        b.swOnline.setOnCheckedChangeListener { _, on -> setOnline(on) }
        if (hasLocationPermission()) locate()
        else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        prefs.getString("active_group", null)?.let { follow(it) }
    }

    override fun onResume() { super.onResume(); b.map.onResume() }
    override fun onPause() { b.map.onPause(); super.onPause() }

    private fun locate() = lifecycleScope.launch {
        currentLocation()?.takeIf { it.inServiceArea() }?.let { here = it }
    }

    private fun setOnline(on: Boolean) {
        b.tvOnline.text = if (on) "You're online" else "You're offline"
        listJob?.cancel(); listJob = null
        b.listGroups.removeAllViews()
        if (!on) { b.tvEmpty.text = "Go online to see ride requests."; b.tvEmpty.visibility = View.VISIBLE; return }
        b.tvEmpty.text = "Waiting for pooled rides…"
        listJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.backend.observeOpenGroups().collect { render(it) }
            }
        }
    }

    private fun render(groups: List<RideGroup>) {
        b.listGroups.removeAllViews()
        b.tvEmpty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        groups.forEach { g ->
            val v = ItemGroupBinding.inflate(layoutInflater, b.listGroups, false)
            v.tvRiders.text = g.riderNames.joinToString(" + ")
            v.tvTotal.text = Fmt.rs(g.totalFare)
            v.tvStops.text = g.stopNames.joinToString("\n") { "• $it" }
            val toPickup = g.firstPickup?.let { Geo.distanceM(here, it) * 1.3 }
            v.tvMeta.text = "${g.riderIds.size} rider${if (g.riderIds.size > 1) "s" else ""} · ${Fmt.km(g.sharedDistanceM)} · " +
                "${Fmt.mins(g.sharedDurationS)}" + (toPickup?.let { " · first pickup ${Fmt.km(it)} away" } ?: "")
            v.btnAccept.setOnClickListener { accept(g, v) }
            b.listGroups.addView(v.root)
        }
    }

    private fun accept(g: RideGroup, v: ItemGroupBinding) {
        val user = ServiceLocator.backend.currentUser() ?: return
        val vehicle = b.etVehicle.text.toString().trim()
        if (vehicle.length < 4) { b.etVehicle.error = "Enter your vehicle number"; b.etVehicle.requestFocus(); return }
        prefs.edit().putString("vehicle", vehicle).apply()
        v.btnAccept.isEnabled = false
        lifecycleScope.launch {
            try {
                ServiceLocator.backend.acceptGroup(g.id, user, vehicle)
                ServiceLocator.backend.updateDriverLocation(g.id, here.lat, here.lng)
                follow(g.id)
            } catch (e: Exception) {
                toast(e.message ?: "Could not accept this ride"); v.btnAccept.isEnabled = true
            }
        }
    }

    private fun follow(groupId: String) {
        prefs.edit().putString("active_group", groupId).apply()
        b.swOnline.isChecked = false
        b.panelRequests.visibility = View.GONE
        b.panelTrip.visibility = View.VISIBLE
        tripJob?.cancel()
        tripJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.backend.observeGroup(groupId).collect { g -> if (g != null) onTrip(g) else endTrip() }
            }
        }
    }

    private fun onTrip(g: RideGroup) {
        val firstTime = active?.id != g.id
        active = g
        if (firstTime) {
            b.map.overlays.clear(); carMarker = null
            b.map.route(g.routePoints, ContextCompat.getColor(this, R.color.route_line))
            g.stops.forEachIndexed { i, p ->
                val n = g.stopNames.getOrElse(i) { "" }
                b.map.pin(p, if (n.startsWith("Pickup")) R.drawable.marker_green else R.drawable.marker_amber, n)
            }
            b.map.fit(g.routePoints + g.stops)
        }
        if (g.driverLat != 0.0) {
            val p = GeoPoint(g.driverLat, g.driverLng)
            val m = carMarker ?: b.map.pin(p, R.drawable.marker_car, "You").also { carMarker = it }
            m.position = p.osm(); b.map.invalidate()
        }
        b.tvTripStatus.text = Status.label(g.status)
        b.tvTripMeta.text = "${g.riderNames.joinToString(", ")} · ${Fmt.km(g.sharedDistanceM)} · collect ${Fmt.rs(g.totalFare)}"
        val (next, action) = when (g.status) {
            Status.ACCEPTED -> "Next: ${g.stopNames.firstOrNull() ?: ""}" to "Start driving to pickup"
            Status.ARRIVING -> "Next: ${g.stopNames.firstOrNull() ?: ""}" to "Riders picked up · start trip"
            Status.ON_TRIP -> "Next: ${g.stopNames.lastOrNull() ?: ""}" to "Complete trip"
            Status.COMPLETED -> "Trip complete · collect ${Fmt.rs(g.totalFare)}" to "Back to ride requests"
            else -> "Ride ${Status.label(g.status).lowercase()}" to "Back to ride requests"
        }
        b.tvNextStop.text = next
        b.btnTripAction.text = action
        b.btnTripAction.isEnabled = true
        b.btnTripAction.setOnClickListener { advance(g) }
    }

    private fun advance(g: RideGroup) {
        val next = when (g.status) {
            Status.ACCEPTED -> Status.ARRIVING
            Status.ARRIVING -> Status.ON_TRIP
            Status.ON_TRIP -> Status.COMPLETED
            else -> null
        }
        if (next == null) { endTrip(); return }
        b.btnTripAction.isEnabled = false
        lifecycleScope.launch {
            runCatching { ServiceLocator.backend.updateGroupStatus(g.id, next) }.onFailure { toast(it.message ?: "Update failed") }
            startMoving(g.id, next)
        }
    }

    /** Publishes the driver position: real GPS when available in Chennai, otherwise a simulated drive along the route. */
    private fun startMoving(groupId: String, status: String) {
        moveJob?.cancel()
        if (status == Status.COMPLETED) return
        moveJob = lifecycleScope.launch {
            val g = active ?: return@launch
            val path = if (status == Status.ARRIVING) Geo.resample(listOf(here, g.firstPickup ?: here), 80.0) else g.routePoints
            var i = 0
            while (isActive) {
                val gps = currentLocation()?.takeIf { it.inServiceArea() }
                val p = gps ?: path.getOrNull(i)?.also { i += (path.size / 40).coerceAtLeast(1) } ?: break
                here = p
                runCatching { ServiceLocator.backend.updateDriverLocation(groupId, p.lat, p.lng) }
                delay(if (gps != null) 5_000 else 1_000)
            }
        }
    }

    private fun endTrip() {
        moveJob?.cancel(); tripJob?.cancel()
        prefs.edit().remove("active_group").apply()
        active = null
        b.panelTrip.visibility = View.GONE
        b.panelRequests.visibility = View.VISIBLE
        b.swOnline.isChecked = true
    }
}
