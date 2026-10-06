package com.cabfusion.app.data

import com.cabfusion.app.core.Geo
import com.cabfusion.app.core.GeoPoint
import com.cabfusion.app.data.net.OsrmApi
import com.cabfusion.app.data.net.PhotonApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

data class Place(val name: String, val detail: String, val point: GeoPoint) {
    override fun toString() = if (detail.isBlank()) name else "$name, $detail"
}

/** source = "OSRM" for a real road route, "ESTIMATE" when the routing service was unreachable. */
data class RouteResult(val points: List<GeoPoint>, val distanceM: Double, val durationS: Double, val source: String)

/** Routing (OSRM) and place search (Photon) over HTTPS, with offline fallbacks. */
class MapsRepository {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "CabFusion/2.0 (CIT student project)").build())
        }
        .build()

    private val osrm = Retrofit.Builder().baseUrl("https://router.project-osrm.org/").client(http)
        .addConverterFactory(GsonConverterFactory.create()).build().create(OsrmApi::class.java)
    private val photon = Retrofit.Builder().baseUrl("https://photon.komoot.io/").client(http)
        .addConverterFactory(GsonConverterFactory.create()).build().create(PhotonApi::class.java)

    /** Road route through all [points] in order (2 points = A→B, more = pooled trip with stops). */
    suspend fun route(points: List<GeoPoint>): RouteResult {
        require(points.size >= 2)
        return try {
            val coords = points.joinToString(";") { "%.6f,%.6f".format(java.util.Locale.US, it.lng, it.lat) }
            val r = osrm.route(coords).routes?.firstOrNull() ?: error("no route")
            val pts = r.geometry?.coordinates?.map { GeoPoint(it[1], it[0]) }.orEmpty()
            RouteResult(if (pts.size >= 2) pts else points, r.distance, r.duration, "OSRM")
        } catch (e: Exception) {
            estimate(points)
        }
    }

    /** Straight-line legs × 1.3 at an average city speed of 22 km/h. */
    fun estimate(points: List<GeoPoint>): RouteResult {
        val d = Geo.lengthM(points) * 1.3
        return RouteResult(Geo.resample(points, 200.0), d, d / (22_000.0 / 3600.0), "ESTIMATE")
    }

    suspend fun search(query: String): List<Place> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val local = ChennaiPlaces.search(q)
        val remote = try {
            photon.search(q).features.orEmpty().mapNotNull { f ->
                val c = f.geometry?.coordinates ?: return@mapNotNull null
                val p = f.properties ?: return@mapNotNull null
                val name = p.name ?: p.street ?: return@mapNotNull null
                val detail = listOfNotNull(p.district ?: p.locality, p.city).distinct().joinToString(", ")
                Place(name, detail, GeoPoint(c[1], c[0]))
            }
        } catch (e: Exception) { emptyList() }
        return (local + remote).distinctBy { it.name.lowercase() }.take(8)
    }

    suspend fun reverse(p: GeoPoint): String = try {
        val f = photon.reverse(p.lat, p.lng).features?.firstOrNull()?.properties
        listOfNotNull(f?.name ?: f?.street, f?.district ?: f?.locality ?: f?.city).distinct().joinToString(", ")
            .ifBlank { "Current location" }
    } catch (e: Exception) {
        ChennaiPlaces.nearest(p)?.name ?: "Current location"
    }
}

/** Well-known Chennai places, so search works instantly and offline for common trips. */
object ChennaiPlaces {
    val all = listOf(
        Place("Anna Nagar", "Chennai", GeoPoint(13.0850, 80.2101)),
        Place("Anna Nagar West Depot", "Chennai", GeoPoint(13.0868, 80.2010)),
        Place("T. Nagar", "Chennai", GeoPoint(13.0418, 80.2341)),
        Place("Koyambedu", "Chennai", GeoPoint(13.0694, 80.1948)),
        Place("Vadapalani", "Chennai", GeoPoint(13.0500, 80.2121)),
        Place("Kodambakkam", "Chennai", GeoPoint(13.0521, 80.2255)),
        Place("Guindy", "Chennai", GeoPoint(13.0067, 80.2206)),
        Place("Egmore", "Chennai", GeoPoint(13.0732, 80.2609)),
        Place("Chennai Central", "Chennai", GeoPoint(13.0827, 80.2757)),
        Place("Porur", "Chennai", GeoPoint(13.0382, 80.1564)),
        Place("Tidel Park", "Taramani, Chennai", GeoPoint(12.9894, 80.2486)),
        Place("Chennai Airport", "Meenambakkam", GeoPoint(12.9941, 80.1709)),
        Place("Chennai Institute of Technology", "Kundrathur", GeoPoint(12.9724, 80.0434)),
        Place("Velachery", "Chennai", GeoPoint(12.9815, 80.2180)),
        Place("Adyar", "Chennai", GeoPoint(13.0012, 80.2565)),
        Place("Tambaram", "Chennai", GeoPoint(12.9249, 80.1000)),
    )

    fun search(q: String) = all.filter { it.name.contains(q, ignoreCase = true) }
    fun nearest(p: GeoPoint) = all.minByOrNull { Geo.distanceM(p, it.point) }
    fun byName(n: String) = all.first { it.name == n }
}
