package com.cabfusion.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cabfusion.app.R
import com.cabfusion.app.core.GeoPoint
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.data.model.RideRequest
import com.cabfusion.app.data.model.Status
import com.cabfusion.app.databinding.ActivityDashboardBinding
import com.cabfusion.app.databinding.ItemRideBinding
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {
    private lateinit var b: ActivityDashboardBinding
    private var here: GeoPoint = CIT
    private var hereName = "Chennai Institute of Technology"

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(b.root)
        val user = ServiceLocator.backend.currentUser()
        if (user == null) { go<LoginActivity>(clearTask = true); finish(); return }

        b.tvGreeting.text = "Hi ${user.name.substringBefore(' ')},"
        b.tvMode.text = if (ServiceLocator.backend.isDemo) "DEMO" else "LIVE"
        b.tvPickup.text = hereName
        b.cardRoute.setOnClickListener { openPlanner() }
        b.tvDrop.setOnClickListener { openPlanner() }
        b.bottomNav.selectedItemId = R.id.nav_home
        b.bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_rides -> go<MyRidesActivity>()
                R.id.nav_profile -> go<ProfileActivity>()
            }
            false
        }
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (hasLocationPermission()) locate() else permissions.launch(wanted.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        b.bottomNav.menu.findItem(R.id.nav_home).isChecked = true
        refresh()
    }

    private fun openPlanner() = go<LocationSelectionActivity> {
        putExtra(LocationSelectionActivity.EXTRA_PICKUP_NAME, hereName)
        putExtra(LocationSelectionActivity.EXTRA_PICKUP_LAT, here.lat)
        putExtra(LocationSelectionActivity.EXTRA_PICKUP_LNG, here.lng)
    }

    private fun locate() = lifecycleScope.launch {
        val fix = currentLocation()
        if (fix != null && fix.inServiceArea()) {
            here = fix
            hereName = ServiceLocator.maps.reverse(fix)
            b.tvCurrentLocation.text = hereName
        } else {
            b.tvCurrentLocation.text = if (fix == null) "$hereName (default)" else "Outside Chennai · using $hereName"
        }
        b.tvPickup.text = hereName
    }

    private fun refresh() = lifecycleScope.launch {
        val backend = ServiceLocator.backend
        val user = backend.currentUser() ?: return@launch
        val open = runCatching { backend.openRequests() }.getOrDefault(emptyList()).filter { it.riderId != user.uid }
        b.tvNearby.text = open.size.toString()
        val mine = runCatching { backend.myRequests(user.uid) }.getOrDefault(emptyList())
        b.tvSaved.text = Fmt.rs(mine.filter { it.status == Status.COMPLETED }.sumOf { it.saving })
        b.listRecent.removeAllViews()
        mine.take(3).forEach { b.listRecent.addView(rideRow(layoutInflater, b.listRecent, it) { r -> openRide(r) }) }
        b.tvNoRides.visibility = if (mine.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openRide(r: RideRequest) = go<RideStatusActivity> { putExtra(RideStatusActivity.EXTRA_REQUEST_ID, r.id) }
}

/** One row of the ride history, shared by the dashboard and My Rides. */
fun rideRow(inflater: LayoutInflater, parent: android.view.ViewGroup, r: RideRequest, onClick: (RideRequest) -> Unit): View {
    val v = ItemRideBinding.inflate(inflater, parent, false)
    v.tvRoute.text = "${r.pickupName} → ${r.dropName}"
    val shown = if (r.fareShare > 0) r.fareShare else r.soloFare
    v.tvFare.text = Fmt.rs(shown)
    v.tvMeta.text = buildString {
        append(Fmt.date(r.createdAt)); append(" · "); append(Fmt.km(r.distanceM))
        append(if (r.shared) " · Shared" else " · Solo")
        if (r.saving > 0) append(" · saved ${Fmt.rs(r.saving)}")
    }
    v.tvStatus.text = Status.label(r.status)
    v.root.setOnClickListener { onClick(r) }
    return v.root
}
