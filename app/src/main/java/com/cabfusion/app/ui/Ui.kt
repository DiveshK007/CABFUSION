package com.cabfusion.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cabfusion.app.CabFusionApp
import com.cabfusion.app.R
import com.cabfusion.app.core.GeoPoint
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.osmdroid.util.GeoPoint as OsmPoint

object Fmt {
    fun km(m: Double) = if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000.0)
    fun mins(s: Double) = "${(s / 60.0).toInt().coerceAtLeast(1)} min"
    fun rs(v: Int) = "₹$v"
    fun date(ms: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(ms))
    fun initial(name: String) = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** Default map centre / fallback location: Chennai Institute of Technology, Kundrathur. */
val CIT = GeoPoint(12.9724, 80.0434)
val CHENNAI = GeoPoint(13.0500, 80.2121)

fun GeoPoint.osm() = OsmPoint(lat, lng)

fun Activity.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

inline fun <reified T : Activity> Activity.go(clearTask: Boolean = false, extras: Intent.() -> Unit = {}) {
    val i = Intent(this, T::class.java).apply(extras)
    if (clearTask) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    startActivity(i)
}

// ---------------------------------------------------------------- map helpers (osmdroid)
fun MapView.setup(center: GeoPoint = CHENNAI, zoom: Double = 12.5) {
    setTileSource(TileSourceFactory.MAPNIK)
    setMultiTouchControls(true)
    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
    controller.setZoom(zoom)
    controller.setCenter(center.osm())
}

fun MapView.route(points: List<GeoPoint>, color: Int, widthPx: Float = 12f): Polyline =
    Polyline(this).apply {
        setPoints(points.map { it.osm() })
        outlinePaint.color = color
        outlinePaint.strokeWidth = widthPx
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        overlays.add(this)
    }

fun MapView.pin(p: GeoPoint, iconRes: Int, title: String): Marker =
    Marker(this).apply {
        position = p.osm()
        icon = ContextCompat.getDrawable(context, iconRes)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        this.title = title
        overlays.add(this)
    }

fun MapView.fit(points: List<GeoPoint>, paddingPx: Int = 120) {
    if (points.isEmpty()) return
    post {
        if (points.size == 1) { controller.setZoom(15.0); controller.setCenter(points[0].osm()); return@post }
        val box = BoundingBox.fromGeoPoints(points.map { it.osm() })
        runCatching { zoomToBoundingBox(box.increaseByScale(1.25f), false, paddingPx) }
        invalidate()
    }
}

// ---------------------------------------------------------------- location
fun Context.hasLocationPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Current device location, or null if permission is missing or no fix arrives within 6 s. */
@SuppressLint("MissingPermission")
suspend fun Context.currentLocation(): GeoPoint? {
    if (!hasLocationPermission()) return null
    return runCatching {
        withTimeoutOrNull(6_000) {
            LocationServices.getFusedLocationProviderClient(this@currentLocation)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
        }?.let { GeoPoint(it.latitude, it.longitude) }
    }.getOrNull()
}

// ---------------------------------------------------------------- notifications
@SuppressLint("MissingPermission")
fun Context.notifyRide(id: Int, title: String, text: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val n = NotificationCompat.Builder(this, CabFusionApp.CHANNEL_RIDES)
        .setSmallIcon(R.drawable.ic_car)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(this).notify(id, n)
}

/** CabFusion currently serves Chennai; fixes further than this from the city centre fall back to [CIT]. */
const val SERVICE_RADIUS_M = 60_000.0
fun GeoPoint.inServiceArea() = com.cabfusion.app.core.Geo.distanceM(this, CHENNAI) <= SERVICE_RADIUS_M
