package com.cabfusion.app.data

import com.cabfusion.app.data.model.RideGroup
import com.cabfusion.app.data.model.RideRequest
import com.cabfusion.app.data.model.Status
import com.cabfusion.app.data.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Firebase Authentication for accounts, Cloud Firestore for users, requests and groups. */
class FirebaseBackend : Backend {
    override val isDemo = false
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val users get() = db.collection("users")
    private val requests get() = db.collection("rideRequests")
    private val groups get() = db.collection("rideGroups")
    private var cached: UserProfile? = null

    override fun currentUser() = cached

    override suspend fun restoreSession(): UserProfile? {
        val u = auth.currentUser ?: return null
        cached = users.document(u.uid).get().await().toObject(UserProfile::class.java)
        return cached
    }

    override suspend fun register(name: String, email: String, phone: String, password: String, role: String): UserProfile =
        guard {
            val res = auth.createUserWithEmailAndPassword(email, password).await()
            val profile = UserProfile(res.user!!.uid, name, email, phone, role, System.currentTimeMillis())
            users.document(profile.uid).set(profile).await()
            profile.also { cached = it }
        }

    override suspend fun login(email: String, password: String): UserProfile = guard {
        val res = auth.signInWithEmailAndPassword(email, password).await()
        users.document(res.user!!.uid).get().await().toObject(UserProfile::class.java)
            ?.also { cached = it } ?: throw BackendException("Profile not found for this account")
    }

    override suspend fun updateRole(role: String): UserProfile {
        val u = cached ?: throw BackendException("Not signed in")
        users.document(u.uid).set(mapOf("role" to role), SetOptions.merge()).await()
        return u.copy(role = role).also { cached = it }
    }

    override fun logout() { auth.signOut(); cached = null }

    override suspend fun createRequest(request: RideRequest): RideRequest {
        val doc = requests.document()
        val r = request.copy(id = doc.id, createdAt = System.currentTimeMillis())
        doc.set(r).await()
        return r
    }

    override fun observeRequest(id: String): Flow<RideRequest?> = callbackFlow {
        val reg = requests.document(id).addSnapshotListener { snap, _ -> trySend(snap?.toObject(RideRequest::class.java)) }
        awaitClose { reg.remove() }
    }

    override suspend fun openRequests(): List<RideRequest> {
        val now = System.currentTimeMillis() / 60_000
        return requests.whereEqualTo("status", Status.SEARCHING).get().await()
            .toObjects(RideRequest::class.java)
            .filter { it.shared && now - it.departureEpochMin <= 45 }   // ignore stale requests
    }

    override suspend fun cancelRequest(id: String) {
        requests.document(id).update("status", Status.CANCELLED).await()
    }

    override suspend fun myRequests(uid: String): List<RideRequest> =
        requests.whereEqualTo("riderId", uid).get().await().toObjects(RideRequest::class.java)
            .sortedByDescending { it.createdAt }

    override suspend fun formGroup(group: RideGroup): RideGroup {
        val doc = groups.document()
        val g = group.copy(id = doc.id, createdAt = System.currentTimeMillis())
        db.runTransaction { tx ->
            val refs = g.requestIds.map { requests.document(it) }
            val snaps = refs.map { tx.get(it) }                       // all reads before writes
            snaps.forEach { s ->
                if (s.getString("status") != Status.SEARCHING)
                    throw BackendException("A rider in this group was just matched with someone else. Searching again.")
            }
            tx.set(doc, g)
            refs.zip(snaps).forEach { (ref, s) ->
                val riderId = s.getString("riderId") ?: ""
                tx.update(ref, mapOf(
                    "status" to Status.MATCHED, "groupId" to g.id,
                    "fareShare" to (g.shares[riderId] ?: 0), "matchScore" to (g.scores[riderId] ?: 100)))
            }
        }.await()
        return g
    }

    override fun observeGroup(id: String): Flow<RideGroup?> = callbackFlow {
        val reg = groups.document(id).addSnapshotListener { snap, _ -> trySend(snap?.toObject(RideGroup::class.java)) }
        awaitClose { reg.remove() }
    }

    override fun observeOpenGroups(): Flow<List<RideGroup>> = callbackFlow {
        val reg = groups.whereEqualTo("status", Status.FORMED).addSnapshotListener { snap, _ ->
            trySend(snap?.toObjects(RideGroup::class.java).orEmpty().sortedByDescending { it.createdAt })
        }
        awaitClose { reg.remove() }
    }

    override suspend fun acceptGroup(groupId: String, driver: UserProfile, vehicle: String) {
        db.runTransaction { tx ->
            val ref = groups.document(groupId)
            val g = tx.get(ref).toObject(RideGroup::class.java) ?: throw BackendException("Ride not found")
            if (g.status != Status.FORMED) throw BackendException("Another driver already accepted this ride")
            tx.update(ref, mapOf("status" to Status.ACCEPTED, "driverId" to driver.uid,
                "driverName" to driver.name, "vehicle" to vehicle))
            g.requestIds.forEach { tx.update(requests.document(it), "status", Status.ACCEPTED) }
        }.await()
    }

    override suspend fun updateGroupStatus(groupId: String, status: String) {
        val g = groups.document(groupId).get().await().toObject(RideGroup::class.java) ?: return
        val batch = db.batch()
        batch.update(groups.document(groupId), "status", status)
        g.requestIds.forEach { batch.update(requests.document(it), "status", status) }
        batch.commit().await()
    }

    override suspend fun updateDriverLocation(groupId: String, lat: Double, lng: Double) {
        groups.document(groupId).update(mapOf("driverLat" to lat, "driverLng" to lng)).await()
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try { block() } catch (e: FirebaseAuthException) {
        throw BackendException(when (e.errorCode) {
            "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists"
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND" -> "Incorrect email or password"
            "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters"
            "ERROR_INVALID_EMAIL" -> "Enter a valid email address"
            else -> e.localizedMessage ?: "Authentication failed"
        })
    }
}
