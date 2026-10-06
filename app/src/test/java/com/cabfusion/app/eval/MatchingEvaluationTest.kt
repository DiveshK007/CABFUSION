package com.cabfusion.app.eval

import com.cabfusion.app.core.FareCalculator
import com.cabfusion.app.core.Geo
import com.cabfusion.app.core.GeoPoint
import com.cabfusion.app.core.RouteMatcher
import com.cabfusion.app.core.StopPlanner
import com.cabfusion.app.core.TripPlan
import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.random.Random

/**
 * Evaluates the matching engine on real Chennai trips. Road routes come from the public OSRM
 * server when reachable (CI), otherwise from the straight-line estimate. Results are written to
 * CSV files under build/eval and used in Chapter 9 of the report.
 */
class MatchingEvaluationTest {
    private val P = mapOf(
        "Koyambedu" to GeoPoint(13.0694, 80.1948), "Koyambedu (Rohini)" to GeoPoint(13.0718, 80.1960),
        "Vadapalani" to GeoPoint(13.0500, 80.2121), "Guindy" to GeoPoint(13.0067, 80.2206),
        "Velachery" to GeoPoint(12.9815, 80.2180), "Anna Nagar West Depot" to GeoPoint(13.0868, 80.2010),
        "Anna Nagar" to GeoPoint(13.0850, 80.2101), "T. Nagar" to GeoPoint(13.0418, 80.2341),
        "Kodambakkam" to GeoPoint(13.0521, 80.2255), "Tidel Park" to GeoPoint(12.9894, 80.2486),
        "Porur" to GeoPoint(13.0382, 80.1564), "CIT Kundrathur" to GeoPoint(12.9724, 80.0434),
        "Egmore" to GeoPoint(13.0732, 80.2609), "Chennai Central" to GeoPoint(13.0827, 80.2757),
        "Adyar" to GeoPoint(13.0012, 80.2565), "Tambaram" to GeoPoint(12.9249, 80.1000),
        "Chennai Airport" to GeoPoint(12.9941, 80.1709),
    )
    private val matcher = RouteMatcher()
    private val fare = FareCalculator()
    private val out = File("build/eval").apply { mkdirs() }
    private var osrmUsed = 0

    private data class Route(val pts: List<GeoPoint>, val d: Double, val t: Double, val src: String)

    private fun route(points: List<GeoPoint>): Route {
        try {
            val coords = points.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lng, it.lat) }
            val c = URL("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "CabFusion-eval (student project)")
            val j = JsonParser.parseString(c.inputStream.bufferedReader().readText()).asJsonObject
            val r = j.getAsJsonArray("routes")[0].asJsonObject
            val pts = r.getAsJsonObject("geometry").getAsJsonArray("coordinates").map {
                val a = it.asJsonArray; GeoPoint(a[1].asDouble, a[0].asDouble)
            }
            osrmUsed++
            Thread.sleep(250)                                   // be polite to the demo server
            return Route(pts, r.get("distance").asDouble, r.get("duration").asDouble, "OSRM")
        } catch (e: Exception) {
            val d = Geo.lengthM(points) * 1.3
            return Route(Geo.resample(points, 100.0), d, d / (22_000.0 / 3600.0), "ESTIMATE")
        }
    }

    private val cache = HashMap<Pair<String, String>, Route>()
    private fun trip(id: String, name: String, from: String, to: String, depart: Long = 0): Pair<TripPlan, Route> {
        val r = cache.getOrPut(from to to) { route(listOf(P.getValue(from), P.getValue(to))) }
        return TripPlan(id, name, P.getValue(from), P.getValue(to), r.pts, r.d, depart) to r
    }

    @Test fun evaluatePairs() {
        val cases = listOf(
            Triple("Koyambedu" to "Guindy", "Koyambedu (Rohini)" to "Guindy", "same corridor; pickups 300 m apart"),
            Triple("Koyambedu" to "Guindy", "Vadapalani" to "Guindy", "second rider joins on the way"),
            Triple("Koyambedu" to "Guindy", "Koyambedu" to "Velachery", "same start; nearby drops"),
            Triple("Vadapalani" to "Velachery", "Kodambakkam" to "Tidel Park", "parallel southbound trips"),
            Triple("Anna Nagar West Depot" to "T. Nagar", "Koyambedu" to "Guindy", "partial overlap"),
            Triple("Chennai Central" to "Adyar", "Egmore" to "Adyar", "same destination"),
            Triple("Koyambedu" to "Guindy", "Porur" to "CIT Kundrathur", "pickups 4 km apart"),
            Triple("Koyambedu" to "Guindy", "Anna Nagar" to "Egmore", "different direction"),
            Triple("Tambaram" to "Guindy", "Guindy" to "Tambaram", "opposite direction"),
            Triple("Koyambedu" to "Guindy", "Koyambedu" to "Chennai Airport", "drop off the corridor"),
        )
        val rows = mutableListOf("case,rider_a,rider_b,note,score,overlap_pct,direction,pickup_gap_m,detour_a,detour_b,solo_a_km,solo_b_km,pooled_km,solo_fare_a,solo_fare_b,share_a,share_b,saving_a_pct,saving_b_pct,vehicle_km_saved_pct,route_source")
        cases.forEachIndexed { i, (a, b, note) ->
            val (ta, ra) = trip("a$i", "A", a.first, a.second)
            val (tb, rb) = trip("b$i", "B", b.first, b.second)
            val m = matcher.score(ta, tb)
            val label = { x: Pair<String, String> -> "${x.first} -> ${x.second}" }
            if (m == null || m.score < 40) {
                rows += listOf("${i + 1}", label(a), label(b), note, m?.score?.toString() ?: "rejected", m?.let { "%.0f".format(Locale.US, it.overlap * 100) } ?: "",
                    m?.let { "%.2f".format(Locale.US, it.directionSimilarity) } ?: "", "%.0f".format(Locale.US, Geo.distanceM(ta.pickup, tb.pickup)),
                    "", "", "%.1f".format(Locale.US, ra.d / 1000), "%.1f".format(Locale.US, rb.d / 1000), "", "", "", "", "", "", "", "", ra.src).joinToString(",")
                return@forEachIndexed
            }
            val stops = StopPlanner.order(ta, listOf(tb))
            val pooled = route(stops.map { it.point })
            val split = fare.split(mapOf("A" to ra.d, "B" to rb.d), mapOf("A" to ra.t, "B" to rb.t), pooled.d, pooled.t).associateBy { it.riderId }
            val sa = split.getValue("A"); val sb = split.getValue("B")
            rows += listOf("${i + 1}", label(a), label(b), note, m.score.toString(), "%.0f".format(Locale.US, m.overlap * 100),
                "%.2f".format(Locale.US, m.directionSimilarity), "%.0f".format(Locale.US, m.pickupGapM),
                "%.2f".format(Locale.US, m.detourRatioMine), "%.2f".format(Locale.US, m.detourRatioTheirs),
                "%.1f".format(Locale.US, ra.d / 1000), "%.1f".format(Locale.US, rb.d / 1000), "%.1f".format(Locale.US, pooled.d / 1000),
                sa.soloFare.toString(), sb.soloFare.toString(), sa.share.toString(), sb.share.toString(),
                sa.savingPercent.toString(), sb.savingPercent.toString(),
                "%.0f".format(Locale.US, (1 - pooled.d / (ra.d + rb.d)) * 100), pooled.src).joinToString(",")
        }
        File(out, "pairs.csv").writeText(rows.joinToString("\n") + "\n")
        println(rows.joinToString("\n"))
        assertTrue(rows.size == cases.size + 1)
    }

    @Test fun evaluateGroupOfThree() {
        val (me, rm) = trip("me", "Divesh", "Koyambedu", "Guindy")
        val pool = listOf(
            trip("p1", "Sanjay", "Koyambedu (Rohini)", "Guindy"),
            trip("p2", "Lakshmi", "Vadapalani", "Velachery"),
            trip("p3", "Arun", "Vadapalani", "Guindy"),
            trip("p4", "Karthik", "Porur", "CIT Kundrathur"),
            trip("p5", "Meena", "Anna Nagar", "Egmore"),
        )
        val ranked = matcher.rank(me, pool.map { it.first })
        val group = matcher.formGroup(me, pool.map { it.first })
        val members = listOf(me to rm) + group.map { g -> pool.first { it.first.id == g.candidate.id } }
        val stops = StopPlanner.order(me, group.map { it.candidate })
        val pooled = route(stops.map { it.point })
        val split = fare.split(members.associate { it.first.riderName to it.second.d }, members.associate { it.first.riderName to it.second.t }, pooled.d, pooled.t)
        val lines = mutableListOf("section,name,detail,value")
        ranked.forEach { lines += "ranked,${it.candidate.riderName},score|overlap|pickup_gap_m,${it.score}|${"%.0f".format(Locale.US, it.overlap * 100)}|${"%.0f".format(Locale.US, it.pickupGapM)}" }
        stops.forEach { s -> lines += "stop,${s.riderName},${if (s.isPickup) "pickup" else "drop"},${P.entries.first { it.value == s.point }.key}" }
        split.forEach { lines += "fare,${it.riderId},solo|share|saving_pct,${it.soloFare}|${it.share}|${it.savingPercent}" }
        lines += "trip,pooled,km|min,${"%.1f".format(Locale.US, pooled.d / 1000)}|${"%.0f".format(Locale.US, pooled.t / 60)}"
        lines += "trip,solo_sum,km,${"%.1f".format(Locale.US, members.sumOf { it.second.d } / 1000)}"
        File(out, "group3.csv").writeText(lines.joinToString("\n") + "\n")
        println(lines.joinToString("\n"))
        assertTrue(group.isNotEmpty())
    }

    @Test fun matchingLatency() {
        val rnd = Random(7)
        val (me, _) = trip("me", "Me", "Koyambedu", "Guindy")
        val lines = mutableListOf("pool_size,mean_ms,max_ms,compatible")
        for (n in listOf(10, 50, 100, 250, 500, 1000)) {
            val pool = (0 until n).map { k ->
                val a = GeoPoint(13.0694 + rnd.nextDouble(-0.03, 0.03), 80.1948 + rnd.nextDouble(-0.03, 0.03))
                val b = GeoPoint(13.0067 + rnd.nextDouble(-0.04, 0.04), 80.2206 + rnd.nextDouble(-0.04, 0.04))
                val pts = Geo.resample(listOf(a, b), 100.0)
                TripPlan("c$k", "C$k", a, b, pts, Geo.lengthM(pts) * 1.3, rnd.nextLong(-40, 40))
            }
            matcher.rank(me, pool)                               // warm-up
            val times = (1..5).map { val t0 = System.nanoTime(); matcher.rank(me, pool); (System.nanoTime() - t0) / 1e6 }
            lines += "$n,${"%.1f".format(Locale.US, times.average())},${"%.1f".format(Locale.US, times.max())},${matcher.rank(me, pool).size}"
        }
        File(out, "latency.csv").writeText(lines.joinToString("\n") + "\n")
        File(out, "meta.txt").writeText("osrm_calls=$osrmUsed\n")
        println(lines.joinToString("\n"))
    }
}
