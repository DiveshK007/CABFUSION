package com.cabfusion.app.data

import android.content.Context
import com.cabfusion.app.core.Geo
import com.cabfusion.app.core.GeoPoint
import com.cabfusion.app.core.FareCalculator
import com.cabfusion.app.data.model.RideGroup
import com.cabfusion.app.data.model.RideRequest
import com.cabfusion.app.data.model.Role
import com.cabfusion.app.data.model.Status
import com.cabfusion.app.data.model.UserProfile
import com.cabfusion.app.data.model.flatten
import com.cabfusion.app.data.model.simplify
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

/**
 * On-device demo backend. Accounts are kept in SharedPreferences (passwords stored as salted
 * SHA-256), ride data in memory. Six sample riders around Chennai make matching demonstrable
 * on a single phone, and a simulated driver moves the group through its lifecycle.
 */
class LocalBackend(context: Context, private val maps: MapsRepository) : Backend {
    override val isDemo = true
    private val prefs = context.getSharedPreferences("cf_local", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val fare = FareCalculator()

    private val requests = MutableStateFlow<Map<String, RideRequest>>(emptyMap())
    private val groups = MutableStateFlow<Map<String, RideGroup>>(emptyMap())
    private var seeded = false
    private var current: UserProfile? = null

    private data class Account(val profile: UserProfile, val salt: String, val hash: String)

    private fun accounts(): MutableMap<String, Account> = gson.fromJson<MutableMap<String, Account>>(
        prefs.getString("accounts", null) ?: "{}", object : TypeToken<MutableMap<String, Account>>() {}.type)

    private fun save(a: MutableMap<String, Account>) = prefs.edit().putString("accounts", gson.toJson(a)).apply()

    private fun hash(pw: String, salt: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pw).toByteArray()).joinToString("") { "%02x".format(it) }

    override fun currentUser() = current

    override suspend fun restoreSession(): UserProfile? {
        val email = prefs.getString("session", null) ?: return null
        current = accounts()[email]?.profile
        return current
    }

    override suspend fun register(name: String, email: String, phone: String, password: String, role: String): UserProfile {
        val all = accounts()
        val key = email.lowercase()
        if (key in all) throw BackendException("An account with this email already exists")
        val salt = UUID.randomUUID().toString()
        val p = UserProfile("u_" + UUID.randomUUID().toString().take(10), name, key, phone, role, System.currentTimeMillis())
        all[key] = Account(p, salt, hash(password, salt))
        save(all)
        prefs.edit().putString("session", key).apply()
        return p.also { current = it }
    }

    override suspend fun login(email: String, password: String): UserProfile {
        val a = accounts()[email.lowercase()] ?: throw BackendException("Incorrect email or password")
        if (a.hash != hash(password, a.salt)) throw BackendException("Incorrect email or password")
        prefs.edit().putString("session", a.profile.email).apply()
        return a.profile.also { current = it }
    }

    override suspend fun updateRole(role: String): UserProfile {
        val u = current ?: throw BackendException("Not signed in")
        val all = accounts()
        all[u.email]?.let { all[u.email] = it.copy(profile = it.profile.copy(role = role)); save(all) }
        return u.copy(role = role).also { current = it }
    }

    override fun logout() { prefs.edit().remove("session").apply(); current = null }

    // ------------------------------------------------------------------ requests
    override suspend fun createRequest(request: RideRequest): RideRequest = lock.withLock {
        val r = request.copy(id = "req_" + UUID.randomUUID().toString().take(8), createdAt = System.currentTimeMillis())
        requests.value = requests.value + (r.id to r)
        r
    }

    override fun observeRequest(id: String): Flow<RideRequest?> = requests.map { it[id] }

    override suspend fun openRequests(): List<RideRequest> {
        seedIfNeeded()
        return requests.value.values.filter { it.status == Status.SEARCHING && it.shared }
    }

    override suspend fun cancelRequest(id: String) = update(id) { it.copy(status = Status.CANCELLED) }

    override suspend fun myRequests(uid: String) =
        requests.value.values.filter { it.riderId == uid }.sortedByDescending { it.createdAt }

    // ------------------------------------------------------------------ groups
    override suspend fun formGroup(group: RideGroup): RideGroup {
        val g = lock.withLock {
            val members = group.requestIds.map { requests.value[it] }
            if (members.any { it == null || it.status != Status.SEARCHING })
                throw BackendException("A rider in this group was just matched with someone else. Searching again.")
            val g = group.copy(id = "grp_" + UUID.randomUUID().toString().take(8), createdAt = System.currentTimeMillis())
            groups.value = groups.value + (g.id to g)
            requests.value = requests.value + members.filterNotNull().associate { r ->
                r.id to r.copy(status = Status.MATCHED, groupId = g.id,
                    fareShare = g.shares[r.riderId] ?: 0, matchScore = g.scores[r.riderId] ?: 100)
            }
            g
        }
        if (current?.role != Role.DRIVER) simulateDriver(g.id)
        return g
    }

    override fun observeGroup(id: String): Flow<RideGroup?> = groups.map { it[id] }

    override fun observeOpenGroups(): Flow<List<RideGroup>> {
        scope.launch { seedIfNeeded() }
        return groups.map { m -> m.values.filter { it.status == Status.FORMED }.sortedByDescending { it.createdAt } }
    }

    override suspend fun acceptGroup(groupId: String, driver: UserProfile, vehicle: String) = lock.withLock {
        val g = groups.value[groupId] ?: throw BackendException("Ride not found")
        if (g.status != Status.FORMED) throw BackendException("Another driver already accepted this ride")
        putGroup(g.copy(status = Status.ACCEPTED, driverId = driver.uid, driverName = driver.name, vehicle = vehicle))
        setMembers(g, Status.ACCEPTED)
    }

    override suspend fun updateGroupStatus(groupId: String, status: String) = lock.withLock {
        val g = groups.value[groupId] ?: return@withLock
        putGroup(g.copy(status = status)); setMembers(g, status)
    }

    override suspend fun updateDriverLocation(groupId: String, lat: Double, lng: Double) = lock.withLock {
        groups.value[groupId]?.let { putGroup(it.copy(driverLat = lat, driverLng = lng)) }
        Unit
    }

    private fun putGroup(g: RideGroup) { groups.value = groups.value + (g.id to g) }
    private fun setMembers(g: RideGroup, status: String) {
        requests.value = requests.value + g.requestIds.mapNotNull { requests.value[it] }.associate { it.id to it.copy(status = status) }
    }
    private suspend fun update(id: String, f: (RideRequest) -> RideRequest) = lock.withLock {
        requests.value[id]?.let { requests.value = requests.value + (id to f(it)) }
        Unit
    }

    // ------------------------------------------------------------------ demo data
    private data class Seed(val name: String, val from: String, val to: String)

    private val seeds = listOf(
        Seed("Ravi K", "Anna Nagar West Depot", "T. Nagar"),
        Seed("Priya S", "Koyambedu", "Guindy"),
        Seed("Arun M", "Vadapalani", "Guindy"),
        Seed("Meena R", "Anna Nagar", "Egmore"),
        Seed("Karthik V", "Porur", "Chennai Institute of Technology"),
        Seed("Divya P", "Kodambakkam", "Tidel Park"),
        Seed("Sanjay T", "Koyambedu", "Guindy"),
        Seed("Lakshmi N", "Vadapalani", "Velachery"),
        Seed("Nisha J", "Koyambedu", "Velachery"),
    )

    private val seedLock = Mutex()

    private suspend fun seedIfNeeded() = seedLock.withLock {
        if (seeded) return@withLock
        seed()
        seeded = true
    }

    private suspend fun seed() {
        val now = System.currentTimeMillis() / 60_000
        val made = coroutineScope { seeds.mapIndexed { i, s -> async {
            val a = ChennaiPlaces.byName(s.from).point
            val b = ChennaiPlaces.byName(s.to).point
            val jitter = GeoPoint(a.lat + 0.0012 * (i % 3 - 1), a.lng + 0.0010 * (i % 2))
            val r = withTimeoutOrNull(8_000) { maps.route(listOf(jitter, b)) } ?: maps.estimate(listOf(jitter, b))
            RideRequest(
                id = "seed_$i", riderId = "seed_rider_$i", riderName = s.name,
                pickupName = s.from, pickupLat = jitter.lat, pickupLng = jitter.lng,
                dropName = s.to, dropLat = b.lat, dropLng = b.lng,
                route = r.points.simplify().flatten(), distanceM = r.distanceM, durationS = r.durationS,
                soloFare = fare.quote(r.distanceM, r.durationS).total, shared = true,
                departureEpochMin = now, status = Status.SEARCHING, createdAt = System.currentTimeMillis() - 60_000L * (i + 1),
            )
        } }.awaitAll() }
        lock.withLock { requests.value = requests.value + made.associateBy { it.id } }
        // one ready-made pooled group so driver mode has something to accept
        val p = made[1]; val q = made[2]
        val shared = withTimeoutOrNull(8_000) { maps.route(listOf(p.pickup, q.pickup, p.drop)) }
            ?: maps.estimate(listOf(p.pickup, q.pickup, p.drop))
        val split = fare.split(mapOf(p.riderId to p.distanceM, q.riderId to q.distanceM),
            mapOf(p.riderId to p.durationS, q.riderId to q.durationS), shared.distanceM, shared.durationS)
        formGroupInternal(RideGroup(
            requestIds = listOf(p.id, q.id), riderIds = listOf(p.riderId, q.riderId), riderNames = listOf(p.riderName, q.riderName),
            stopNames = listOf("Pickup · ${p.riderName} · ${p.pickupName}", "Pickup · ${q.riderName} · ${q.pickupName}",
                "Drop · both riders · ${p.dropName}"),
            stopPoints = listOf(p.pickup, q.pickup, p.drop).flatten(),
            sharedRoute = shared.points.simplify().flatten(), sharedDistanceM = shared.distanceM, sharedDurationS = shared.durationS,
            totalFare = split.sumOf { it.share }, shares = split.associate { it.riderId to it.share },
            soloFares = split.associate { it.riderId to it.soloFare }, scores = mapOf(p.riderId to 100, q.riderId to 88)))
    }

    private suspend fun formGroupInternal(g: RideGroup) = lock.withLock {
        val gg = g.copy(id = "grp_seed", createdAt = System.currentTimeMillis())
        putGroup(gg)
        requests.value = requests.value + gg.requestIds.mapNotNull { requests.value[it] }.associate {
            it.id to it.copy(status = Status.MATCHED, groupId = gg.id, fareShare = gg.shares[it.riderId] ?: 0)
        }
    }

    /** Demo driver: accepts after a few seconds, drives to the first pickup, then along the shared route. */
    private fun simulateDriver(groupId: String) = scope.launch {
        delay(5_000)
        val g0 = groups.value[groupId] ?: return@launch
        if (g0.status != Status.FORMED) return@launch
        val start = g0.firstPickup ?: return@launch
        val origin = GeoPoint(start.lat + 0.011, start.lng - 0.008)          // ~1.5 km away
        lock.withLock {
            putGroup(g0.copy(status = Status.ACCEPTED, driverId = "demo_driver", driverName = "Kumar R",
                vehicle = "TN 09 AB 4521 · Swift Dzire", driverLat = origin.lat, driverLng = origin.lng))
            setMembers(g0, Status.ACCEPTED)
        }
        delay(3_000)
        updateGroupStatus(groupId, Status.ARRIVING)
        val approach = Geo.resample(listOf(origin, start), 120.0)
        for (pt in approach) {
            if (groups.value[groupId]?.status != Status.ARRIVING) return@launch
            updateDriverLocation(groupId, pt.lat, pt.lng); delay(900)
        }
        updateGroupStatus(groupId, Status.ON_TRIP)
        val path = groups.value[groupId]?.routePoints.orEmpty()
        val step = (path.size / 40).coerceAtLeast(1)
        for (i in path.indices step step) {
            if (groups.value[groupId]?.status != Status.ON_TRIP) return@launch
            updateDriverLocation(groupId, path[i].lat, path[i].lng); delay(900)
        }
        path.lastOrNull()?.let { updateDriverLocation(groupId, it.lat, it.lng) }
        updateGroupStatus(groupId, Status.COMPLETED)
    }
}
