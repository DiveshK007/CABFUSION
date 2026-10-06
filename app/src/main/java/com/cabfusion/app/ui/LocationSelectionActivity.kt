package com.cabfusion.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cabfusion.app.R
import com.cabfusion.app.core.FareCalculator
import com.cabfusion.app.core.GeoPoint
import com.cabfusion.app.data.Place
import com.cabfusion.app.data.RouteResult
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.data.model.RideRequest
import com.cabfusion.app.data.model.flatten
import com.cabfusion.app.data.model.simplify
import com.cabfusion.app.databinding.ActivityLocationSelectionBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay

class LocationSelectionActivity : AppCompatActivity() {
    private lateinit var b: ActivityLocationSelectionBinding
    private var pickup: Place? = null
    private var drop: Place? = null
    private var route: RouteResult? = null
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var suppressSearch = false
    private val fare = FareCalculator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLocationSelectionBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.map.setup()

        intent.getStringExtra(EXTRA_PICKUP_NAME)?.let { name ->
            setPlace(true, Place(name, "", GeoPoint(intent.getDoubleExtra(EXTRA_PICKUP_LAT, CIT.lat), intent.getDoubleExtra(EXTRA_PICKUP_LNG, CIT.lng))))
        }
        intent.getStringExtra(EXTRA_DROP_NAME)?.let { name ->
            setPlace(false, Place(name, "", GeoPoint(intent.getDoubleExtra(EXTRA_DROP_LAT, 0.0), intent.getDoubleExtra(EXTRA_DROP_LNG, 0.0))))
        }

        wireSearch(b.etPickup, isPickup = true)
        wireSearch(b.etDrop, isPickup = false)
        b.btnBack.setOnClickListener { finish() }
        b.btnSwap.setOnClickListener {
            val p = pickup; val d = drop
            if (d != null) setPlace(true, d)
            if (p != null) setPlace(false, p)
        }
        b.btnMyLocation.setOnClickListener {
            lifecycleScope.launch {
                val fix = currentLocation()
                if (fix == null || !fix.inServiceArea()) { toast("Location unavailable here — search for your pickup instead"); return@launch }
                setPlace(true, Place(ServiceLocator.maps.reverse(fix), "", fix))
            }
        }
        b.map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: org.osmdroid.util.GeoPoint?) = false
            override fun longPressHelper(p: org.osmdroid.util.GeoPoint?): Boolean {
                p ?: return false
                val g = GeoPoint(p.latitude, p.longitude)
                lifecycleScope.launch { setPlace(false, Place(ServiceLocator.maps.reverse(g), "", g)) }
                return true
            }
        }))
        b.btnShared.setOnClickListener { book(shared = true) }
        b.btnSolo.setOnClickListener { book(shared = false) }
    }

    override fun onResume() { super.onResume(); b.map.onResume() }
    override fun onPause() { b.map.onPause(); super.onPause() }

    private fun wireSearch(field: AutoCompleteTextView, isPickup: Boolean) {
        val adapter = ArrayAdapter<Place>(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        field.setAdapter(adapter)
        field.setOnItemClickListener { parent, _, pos, _ ->
            setPlace(isPickup, parent.getItemAtPosition(pos) as Place)
            field.dismissDropDown()
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressSearch) return
                val q = s?.toString().orEmpty()
                searchJob?.cancel()
                if (q.length < 2) return
                searchJob = lifecycleScope.launch {
                    delay(300)                                   // debounce typing
                    val results = ServiceLocator.maps.search(q)
                    adapter.clear(); adapter.addAll(results); adapter.notifyDataSetChanged()
                    if (results.isNotEmpty() && field.hasFocus()) field.showDropDown()
                }
            }
        })
    }

    private fun setPlace(isPickup: Boolean, p: Place) {
        if (isPickup) pickup = p else drop = p
        suppressSearch = true
        (if (isPickup) b.etPickup else b.etDrop).apply { setText(p.name, false); clearFocus() }
        suppressSearch = false
        if (!isPickup) b.tvMapHint.visibility = View.GONE
        redraw()
    }

    private fun redraw() {
        routeJob?.cancel()
        b.map.overlays.removeAll { it !is MapEventsOverlay }
        val p = pickup; val d = drop
        p?.let { b.map.pin(it.point, R.drawable.marker_green, "Pickup: ${it.name}") }
        d?.let { b.map.pin(it.point, R.drawable.marker_amber, "Drop: ${it.name}") }
        b.map.fit(listOfNotNull(p?.point, d?.point))
        b.map.invalidate()
        route = null
        setButtons(false)
        if (p == null || d == null) return
        if (p.point == d.point) { b.tvRouteSource.text = "Pickup and drop are the same place"; return }
        b.tvRouteSource.text = "Finding the best road route…"
        routeJob = lifecycleScope.launch {
            val r = ServiceLocator.maps.route(listOf(p.point, d.point))
            route = r
            b.map.route(r.points, ContextCompat.getColor(this@LocationSelectionActivity, R.color.route_line))
            b.map.fit(r.points)
            val q = fare.quote(r.distanceM, r.durationS)
            b.tvDistance.text = Fmt.km(r.distanceM)
            b.tvDuration.text = Fmt.mins(r.durationS)
            b.tvFare.text = Fmt.rs(q.total)
            b.tvRouteSource.text = if (r.source == "OSRM") "Road route via OpenStreetMap (OSRM)" else "Estimated route (routing service unreachable)"
            setButtons(true)
        }
    }

    private fun setButtons(on: Boolean) { b.btnShared.isEnabled = on; b.btnSolo.isEnabled = on }

    private fun book(shared: Boolean) {
        val p = pickup ?: return; val d = drop ?: return; val r = route ?: return
        val user = ServiceLocator.backend.currentUser() ?: return
        setButtons(false)
        lifecycleScope.launch {
            try {
                val req = ServiceLocator.backend.createRequest(RideRequest(
                    riderId = user.uid, riderName = user.name,
                    pickupName = p.name, pickupLat = p.point.lat, pickupLng = p.point.lng,
                    dropName = d.name, dropLat = d.point.lat, dropLng = d.point.lng,
                    route = r.points.simplify().flatten(), distanceM = r.distanceM, durationS = r.durationS,
                    soloFare = fare.quote(r.distanceM, r.durationS).total, shared = shared,
                    departureEpochMin = System.currentTimeMillis() / 60_000,
                ))
                go<RideStatusActivity> { putExtra(RideStatusActivity.EXTRA_REQUEST_ID, req.id) }
                finish()
            } catch (e: Exception) {
                toast(e.message ?: "Could not create the ride request"); setButtons(true)
            }
        }
    }

    companion object {
        const val EXTRA_PICKUP_NAME = "pickup_name"
        const val EXTRA_PICKUP_LAT = "pickup_lat"
        const val EXTRA_PICKUP_LNG = "pickup_lng"
        const val EXTRA_DROP_NAME = "drop_name"
        const val EXTRA_DROP_LAT = "drop_lat"
        const val EXTRA_DROP_LNG = "drop_lng"
    }
}
